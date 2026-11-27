package org.geocrew.storage

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.edit.FieldType
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.quote
import org.geocrew.core.sync.FieldDef
import org.geocrew.core.sync.FieldValue
import org.geocrew.core.sync.LayerSpec
import org.geocrew.core.sync.PulledConflict
import org.geocrew.core.sync.PulledLayer
import org.geocrew.core.sync.PulledRow
import org.geocrew.core.sync.PushResult
import org.geocrew.core.sync.SyncClient

/**
 * A project and the team workspace it syncs with (protocol v1 8.4, ADR
 * 0008), through [client], signed in as [account]: sharing the project, then
 * sending what it has to send.
 */
internal class ProjectSync(private val project: Project, private val client: SyncClient, private val account: SyncAccount) {
    /** A layer the workspace cannot take: one whose features are of mixed kinds (backlog: GEOMETRY layers). */
    class Unsupported(val layer: String) : Exception("layer $layer mixes points, lines and areas")

    /** What a push did: changes the server has, changes it refused (all so far), and conflicts it recorded. */
    data class Pushed(val sent: Int, val rejected: Int, val conflicts: Int)

    /** What a pull did: rows written, layers made, conflict records kept. */
    data class Pulled(val rows: Int, val layers: Int, val conflicts: Int)

    /**
     * Shares the project with the workspace (protocol v1 5.6): makes each layer
     * there with the project's ids, links the project, and queues every feature
     * as a new row. It can run again after it was cut short: layers already
     * there and features already queued stay as they are.
     */
    fun share() {
        val specs = specs(project)
        for (spec in specs) client.createLayer(account.workspace, account.token, spec)
        project.link(account.workspace)
        project.data { ds ->
            ds.begin()
            try {
                for (layer in project.layers) Outbox.enqueueNew(ds, layer.name, layer.id, project.target(layer.name).store.features())
                ds.commit()
            } catch (e: Exception) {
                runCatching { ds.rollback() }
                throw e
            }
        }
    }

    /** Sends what the project has to send, at most [batch] changes a request, until nothing is left or the network fails. */
    fun push(batch: Int): Pushed {
        project.data(Sending::prepare)
        var sent = 0
        var conflicts = 0
        while (true) {
            val changes = project.data { Sending.next(it, batch.coerceAtLeast(1)) }
            if (changes.isEmpty()) break
            val (results, _) = client.push(account.workspace, account.token, Instant.now(), changes)
            project.data { Sending.done(it, results) }
            sent += results.count { it.status != PushResult.REJECTED }
            conflicts += results.sumOf { it.conflicts.size }
            // A server that answers for none of them would be asked again and again.
            if (results.none { r -> changes.any { it.id == r.id } }) break
        }
        return Pushed(sent, project.data(Sending::counts).second, conflicts)
    }

    /**
     * Brings the workspace's changes since the last pull into the project
     * (protocol v1 8.2, 8.4), page by page: a shared layer the project lacks is
     * made, rows are written into their layers, conflict records kept. Rows go
     * straight into the file, not through an edit buffer: they are not this
     * phone's edits, so nothing goes to the outbox. Fields this phone still has
     * to send keep its values. Each row's base seq becomes the page's. Call it
     * with nothing unsaved: edit buffers know nothing of what it writes.
     */
    fun pull(batch: Int): Pulled {
        project.data { ds ->
            ds.sql(ProjectFormat.ROWS_SCHEMA)
            ds.sql(ProjectFormat.CONFLICTS_SCHEMA)
        }
        var since = project.data { ds ->
            rows(ds, "SELECT value FROM ${ProjectFormat.PROJECT_TABLE} WHERE key = ${quote(ProjectFormat.PULLED_KEY)}").firstOrNull()?.get("value")?.toLongOrNull() ?: 0L
        }
        var done = Pulled(0, 0, 0)
        do {
            val page = client.pull(account.workspace, account.token, since, batch)
            val layers = makeLayers(page.layers)
            val rows = writeRows(page.rows, page.seq)
            keepConflicts(page.conflicts)
            done = Pulled(done.rows + rows, done.layers + layers, done.conflicts + page.conflicts.size)
            since = page.seq
            // After the page is written: a pull cut short writes it again, to the same end.
            project.data { ds -> ds.sql("INSERT OR REPLACE INTO ${ProjectFormat.PROJECT_TABLE} (key, value) VALUES (${quote(ProjectFormat.PULLED_KEY)}, ${quote(since.toString())})") }
        } while (page.more)
        return done
    }

    // The shared layers the project lacks, made with their ids; how many. A deleted one is left out (backlog).
    private fun makeLayers(layers: List<PulledLayer>): Int {
        var made = 0
        for (l in layers) {
            if (l.deleted || project.layers.any { it.id == l.id }) continue
            val geometry = when (l.geometryType) {
                "Point" -> "POINT"
                "LineString" -> "LINESTRING"
                "Polygon" -> "POLYGON"
                "MultiPoint" -> "MULTIPOINT"
                "MultiLineString" -> "MULTILINESTRING"
                "MultiPolygon" -> "MULTIPOLYGON"
                else -> "NONE"
            }
            val fields = l.fields.map { f ->
                val type = when (f.type) {
                    "integer" -> FieldType.INTEGER64
                    "real" -> FieldType.REAL
                    "boolean" -> FieldType.BOOLEAN
                    "date" -> FieldType.DATE
                    "datetime" -> FieldType.DATETIME
                    else -> FieldType.STRING // string, and json kept as its text
                }
                Triple(f.id, f.name, type)
            }
            project.createSharedLayer(l.id, project.freeLayerName(l.name ?: "layer"), geometry, l.crs, fields)
            made++
        }
        return made
    }

    // [rows] into their layers, each layer in one transaction; how many were written.
    private fun writeRows(rows: List<PulledRow>, seq: Long): Int {
        val waiting = project.data(Outbox::waiting)
        var written = 0
        for ((layerId, layerRows) in rows.groupBy { it.layer }) {
            val layer = project.layers.firstOrNull { it.id == layerId } ?: continue // a layer deleted, or not made here
            val names = project.fieldIds(layer.name).entries.associate { (name, id) -> id to name }
            val store = project.target(layer.name).store
            store.begin()
            try {
                for (row in layerRows) {
                    if (write(store, layerId, names, row, waiting[row.id].orEmpty(), seq)) written++
                }
                store.commit()
            } catch (e: Exception) {
                runCatching { store.rollback() }
                throw e
            }
        }
        return written
    }

    // One row into the layer: made, changed, deleted or brought back; the fields in [mine] as this phone has them.
    private fun write(store: org.geocrew.core.edit.LayerStore, layerId: String, names: Map<String, String>, row: PulledRow, mine: Set<String>, seq: Long): Boolean {
        val fid = project.data { Outbox.fidOf(it, layerId, row.id) }
        val existing = fid?.let(store::feature)
        val values = row.fields.entries.filter { (id, _) -> id != Outbox.GEOMETRY && id !in mine }
            .mapNotNull { (id, v) -> names[id]?.let { it to v.value } }.toMap()
        val geometry = row.fields[Outbox.GEOMETRY]?.value as? JsonObject
        val takeGeometry = Outbox.GEOMETRY in row.fields && Outbox.GEOMETRY !in mine
        val wrote = when {
            // A deletion or restoration of this phone's own is on its way: what is here stands.
            Outbox.DELETED in mine && existing == null -> false
            !(Outbox.DELETED in mine) && row.deleted -> existing?.let {
                // Its attachments go with it, as when this phone deletes one (EditBuffer.save).
                store.setAttachments(it.fid, emptyList())
                store.delete(it.fid)
                true
            } ?: false
            existing == null -> {
                val made = store.add(if (takeGeometry) geometry else null, JsonObject(values))
                project.data { Outbox.map(it, layerId, made, row.id, seq) }
                true
            }
            else -> {
                val changed = values.filter { (name, v) -> existing.properties[name] != v }
                val moved = takeGeometry && existing.geometry != geometry
                if (changed.isNotEmpty() || moved) store.update(existing.fid, JsonObject(changed), moved, geometry)
                changed.isNotEmpty() || moved
            }
        }
        project.data { Outbox.seen(it, row.id, seq) }
        return wrote
    }

    // Conflict records as the pull gives them, new or changed (resolved), for the page that lists them.
    private fun keepConflicts(conflicts: List<PulledConflict>) {
        if (conflicts.isEmpty()) return
        project.data { ds ->
            for (c in conflicts) {
                fun json(v: FieldValue?) = v?.let {
                    buildJsonObject {
                        put("value", it.value)
                        put("by", it.by)
                        put("at", it.at.toString())
                        put("seq", it.seq)
                    }.toString()
                }
                ds.sql(
                    "INSERT OR REPLACE INTO ${ProjectFormat.CONFLICTS_TABLE} (id, layer_id, row, field, kind, kept, lost, resolved) VALUES (" +
                        "${quote(c.id)}, ${quote(c.layer)}, ${quote(c.row)}, ${c.conflict.field?.let(::quote) ?: "NULL"}, ${quote(c.conflict.kind)}, " +
                        "${json(c.conflict.kept)?.let(::quote) ?: "NULL"}, ${json(c.conflict.lost)?.let(::quote) ?: "NULL"}, ${if (c.resolved) 1 else 0})",
                )
            }
        }
    }

    companion object {
        /** Each layer of [project] as the workspace will have it; [Unsupported] for one it cannot take, before anything is made. */
        fun specs(project: Project): List<LayerSpec> = project.layers.map { spec(project, it) }

        // A layer as the workspace has it: its kind of geometry, CRS and fields, by the ids the project keeps (protocol v1 7.1, 7.2).
        private fun spec(project: Project, layer: Project.Layer): LayerSpec {
            val geometry = when (layer.geometry?.uppercase()?.removeSuffix(" Z")?.removeSuffix(" M")?.removeSuffix(" ZM")?.trimEnd('Z', 'M')) {
                null, "", "NONE" -> "None"
                "POINT" -> "Point"
                "LINESTRING" -> "LineString"
                "POLYGON" -> "Polygon"
                "MULTIPOINT" -> "MultiPoint"
                "MULTILINESTRING" -> "MultiLineString"
                "MULTIPOLYGON" -> "MultiPolygon"
                else -> throw Unsupported(layer.name)
            }
            val ids = project.fieldIds(layer.name)
            val fields = project.target(layer.name).store.fields.mapNotNull { f ->
                val id = ids[f.name] ?: return@mapNotNull null
                val type = when (f.type) {
                    FieldType.INTEGER, FieldType.INTEGER64 -> "integer"
                    FieldType.REAL -> "real"
                    FieldType.STRING -> "string"
                    FieldType.DATE -> "date"
                    FieldType.DATETIME -> "datetime"
                    FieldType.BOOLEAN -> "boolean"
                    FieldType.OTHER -> "json"
                }
                FieldDef(id, f.name, type)
            }
            return LayerSpec(layer.id, layer.name, geometry, layer.srs ?: Crs.LONGITUDE_LATITUDE, fields)
        }
    }
}
