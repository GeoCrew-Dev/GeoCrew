package org.geocrew.storage

import android.util.Log
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.edit.Attachment
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
import org.geocrew.core.sync.Received
import org.geocrew.core.sync.Sent
import org.geocrew.core.sync.ServerError
import org.geocrew.core.sync.SyncClient
import org.geocrew.core.sync.receiveFile
import org.geocrew.core.sync.sendFile

/**
 * A project and the team workspace it syncs with (protocol v1 8.4, ADR
 * 0008), through [client], signed in as [account]: sharing the project, then
 * sending what it has to send.
 */
internal class ProjectSync(private val project: Project, private val client: SyncClient, private val account: SyncAccount) {
    /** A layer the workspace cannot take: one whose features are of mixed kinds (backlog: GEOMETRY layers). */
    class Unsupported(val layer: String) : Exception("layer $layer mixes points, lines and areas")

    /** What a push did: changes the server has, changes it refused (all so far), conflicts it recorded, and files still to upload. */
    data class Pushed(val sent: Int, val rejected: Int, val conflicts: Int, val files: Int = 0)

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
                for (layer in project.layers) {
                    val store = project.target(layer.name).store
                    Outbox.enqueueNew(ds, layer.name, layer.id, store.features(), attachmentsOf = { store.attachments(it) })
                }
                ds.commit()
            } catch (e: Exception) {
                runCatching { ds.rollback() }
                throw e
            }
        }
    }

    /**
     * Sends what the project has to send, at most [batch] changes a request,
     * until nothing is left or the network fails: the thumbnails first, for
     * [thumbsFor] at most, so the team sees a photo as soon as its row is in
     * (protocol v1 9.3); then the changes; then the original files for
     * [originalsFor], the rest going on from where it stopped the next time.
     */
    fun push(batch: Int, thumbsFor: Long = THUMBS_MS, originalsFor: Long = ORIGINALS_MS): Pushed {
        project.data(Sending::prepare)
        sendFiles(thumbs = true, until = System.currentTimeMillis() + thumbsFor)
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
        sendFiles(thumbs = false, until = System.currentTimeMillis() + originalsFor)
        return Pushed(sent, project.data(Sending::counts).second, conflicts, project.data(Outbox::filesWaiting))
    }

    // The files waiting, thumbnails or originals, until the clock passes [until]. One gone from the phone, or not
    // the file its hash names, can never go: it waits no more.
    private fun sendFiles(thumbs: Boolean, until: Long) {
        for ((sha, type) in project.data { Outbox.uploads(it, thumbs) }) {
            if (System.currentTimeMillis() > until) return
            val file = AttachmentFiles.fileOf(project.attachments, sha, type)
            val sent = if (!file.isFile) {
                Sent.BROKEN
            } else {
                try {
                    client.sendFile(account.workspace, account.token, file, sha, type, until)
                } catch (e: ServerError) {
                    // Refused (a member who may edit nothing, an archived workspace): the files wait, the changes and the pull go on.
                    Log.w(TAG, "uploads refused: ${e.code}")
                    return
                }
            }
            if (sent == Sent.LATER) return
            if (sent == Sent.BROKEN) Log.w(TAG, "file $sha cannot be sent: missing, or not what its name says")
            project.data { Outbox.uploaded(it, sha) }
        }
    }

    /**
     * Brings the workspace's changes since the last pull into the project
     * (protocol v1 8.2, 8.4), page by page: a shared layer the project lacks is
     * made, rows are written into their layers, attachments onto their features,
     * conflict records kept. Rows go straight into the file, not through an edit
     * buffer: they are not this phone's edits, so nothing goes to the outbox.
     * Fields this phone still has to send keep its values, and their row keeps
     * its base seq till they are sent, so the server can tell what changed
     * alongside them; every other row's base becomes the page's seq. Then the
     * thumbnails the project lacks come, for [thumbsFor] at most. Call it with
     * nothing unsaved: edit buffers know nothing of what it writes.
     */
    fun pull(batch: Int, thumbsFor: Long = THUMBS_MS): Pulled {
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
            writeAttachments(page.rows.filter { it.layer == Outbox.ATTACHMENTS }, page.seq, rowsCame = rows > 0)
            keepConflicts(page.conflicts)
            done = Pulled(done.rows + rows, done.layers + layers, done.conflicts + page.conflicts.size)
            since = page.seq
            // After the page is written: a pull cut short writes it again, to the same end.
            project.data { ds -> ds.sql("INSERT OR REPLACE INTO ${ProjectFormat.PROJECT_TABLE} (key, value) VALUES (${quote(ProjectFormat.PULLED_KEY)}, ${quote(since.toString())})") }
        } while (page.more)
        fetchThumbs(until = System.currentTimeMillis() + thumbsFor)
        return done
    }

    // The attachment rows of a page onto the features they hang on (protocol v1 9.1): new, changed or taken off. One
    // this phone still has a change of to send stands as it is here; one whose feature is not here yet waits for it
    // ([ProjectFormat.PENDING_ATTACHMENTS_TABLE]), and those waiting are tried again once rows have come.
    private fun writeAttachments(rows: List<PulledRow>, seq: Long, rowsCame: Boolean) {
        val waiting = project.data(Outbox::waiting)
        project.data { ds ->
            ds.sql(ProjectFormat.ATTACHMENT_ROWS_SCHEMA)
            ds.sql(ProjectFormat.PENDING_ATTACHMENTS_SCHEMA)
            val pending = if (rowsCame) rows(ds, "SELECT uuid, row, seq FROM ${ProjectFormat.PENDING_ATTACHMENTS_TABLE}") else emptyList()
            if (rows.isEmpty() && pending.isEmpty()) return@data
            ds.begin()
            try {
                for (p in pending) {
                    val o = Json.parseToJsonElement(p.getValue("row")!!).jsonObject
                    val values = (o["fields"] as? JsonObject).orEmpty()
                    val deleted = (o["deleted"] as? JsonPrimitive)?.booleanOrNull == true
                    if (writeAttachment(ds, p.getValue("uuid")!!, values, deleted, p["seq"]?.toLongOrNull() ?: 0)) {
                        ds.sql("DELETE FROM ${ProjectFormat.PENDING_ATTACHMENTS_TABLE} WHERE uuid = ${quote(p.getValue("uuid")!!)}")
                    }
                }
                for (r in rows) {
                    if (r.id in waiting) continue
                    val values = r.fields.mapValues { it.value.value }
                    if (writeAttachment(ds, r.id, values, r.deleted, seq)) {
                        ds.sql("DELETE FROM ${ProjectFormat.PENDING_ATTACHMENTS_TABLE} WHERE uuid = ${quote(r.id)}")
                    } else {
                        val row = buildJsonObject { put("fields", JsonObject(values)); put("deleted", r.deleted) }
                        ds.sql("INSERT OR REPLACE INTO ${ProjectFormat.PENDING_ATTACHMENTS_TABLE} (uuid, row, seq) VALUES (${quote(r.id)}, ${quote(row.toString())}, $seq)")
                    }
                }
                ds.commit()
            } catch (e: Exception) {
                runCatching { ds.rollback() }
                throw e
            }
        }
    }

    // One attachment row onto its feature, seen at [seq]; false when the feature is not here (yet).
    private fun writeAttachment(ds: org.geocrew.nativelib.VectorDataset, id: String, values: Map<String, JsonElement>, deleted: Boolean, seq: Long): Boolean {
        fun text(field: String) = (values[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val layer = project.layers.firstOrNull { it.id == text("target_layer") } ?: return false
        val fid = Outbox.fidOf(ds, layer.id, text("target_row") ?: return false) ?: return false
        val store = project.target(layer.name).store
        val had = store.attachments(fid)
        val now = if (deleted) {
            had.filterNot { it.id == id }
        } else {
            // A row that is no attachment (a field missing or wrong) is left as it was.
            val a = runCatching {
                Attachment(
                    id = id,
                    file = text("file")!!,
                    type = text("type")!!,
                    size = (values["size"] as? JsonPrimitive)?.longOrNull ?: 0,
                    name = text("name").orEmpty(),
                    thumb = text("thumb"),
                    meta = values["meta"] as? JsonObject,
                )
            }.getOrNull() ?: return true
            if (had.any { it.id == id }) had.map { if (it.id == id) a else it } else had + a
        }
        if (now != had) store.setAttachments(fid, now)
        Outbox.seenAttachment(ds, id, seq)
        return true
    }

    // The thumbnails of the project's attachments that are not here yet, until the clock passes [until]; one not
    // uploaded yet, or that cannot be had, comes another time. The pull is done by now: a failure here is not its.
    private fun fetchThumbs(until: Long) {
        for (thumb in project.data(AttachmentTables::thumbs)) {
            if (System.currentTimeMillis() > until) return
            val file = AttachmentFiles.fileOf(project.attachments, thumb, "image/jpeg")
            if (file.isFile) continue
            try {
                client.receiveFile(account.workspace, account.token, thumb, file)
            } catch (e: ServerError) {
                continue
            } catch (e: IOException) {
                return
            }
        }
    }

    /**
     * Fetches the file [sha] of type [type] into the project's attachment
     * folder (protocol v1 9.2), going on from where an earlier try stopped:
     * the original of a photo someone else took, when it is opened.
     */
    fun fetch(sha: String, type: String): Received = client.receiveFile(account.workspace, account.token, sha, AttachmentFiles.fileOf(project.attachments, sha, type))

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
        // A row with fields still to send keeps the base they were edited from: the server must see what changed alongside.
        if (mine.isEmpty()) project.data { Outbox.seen(it, row.id, seq) }
        return wrote
    }

    /**
     * Settles conflict [conflict] on the server (protocol v1 8.3): keeps the
     * current value ([SyncClient.KEPT]) or puts the losing one back
     * ([SyncClient.LOST]), which comes in with the next pull; the record is
     * kept as the server now has it.
     */
    fun resolve(conflict: String, choose: String) {
        project.data { it.sql(ProjectFormat.CONFLICTS_SCHEMA) }
        keepConflicts(listOf(client.resolve(account.workspace, account.token, conflict, choose)))
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
        // How long a sync spends on files: thumbnails, then originals (a phone in the field may have little signal).
        private const val THUMBS_MS = 30_000L
        private const val ORIGINALS_MS = 20_000L
        private const val TAG = "GeoCrewSync"

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
