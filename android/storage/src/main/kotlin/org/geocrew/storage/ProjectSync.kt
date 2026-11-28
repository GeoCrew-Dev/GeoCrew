package org.geocrew.storage

import android.util.Log
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * sending what it has to send. A change of a layer's fields here (one the team
 * made, or a name a field of this phone's must give up) goes by way of
 * [changing]: it makes the change with no layer being edited and opens the
 * project's file again, giving the project as it is then, which [project] is
 * from then on; or, while edits are unsaved, it changes nothing and gives null.
 */
internal class ProjectSync(
    project: Project,
    private val client: SyncClient,
    private val account: SyncAccount,
    private val changing: (Project, () -> Unit) -> Project? = { p, change -> change(); p },
) {
    /** The project, as it is after any change of fields. */
    var project: Project = project
        private set

    /** A field of this phone's that gave its name up to one of the team's (protocol v1 7.4), [from] it [to] another. */
    data class Renamed(val layer: String, val from: String, val to: String)

    /** The fields renamed so, to tell the user. */
    val renamed = mutableListOf<Renamed>()

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
        for (spec in specs) {
            val made = client.createLayer(account.workspace, account.token, spec)
            // The layer's row as the workspace has it: its style or place changed here later goes from this seq.
            project.data {
                Outbox.seenSystemRow(it, spec.id, made.seq)
                SharedSchema.put(it, spec.id, made.schemaVersion, made.fields)
            }
        }
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
        // The structure first (protocol v1 7.4): what the workspace has not taken waits, the rest goes.
        sendStructure()
        project.data { Sending.prepare(it, SharedSchema.unsent(it, project)) }
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
            followDeletions(page.layers)
            val layers = makeLayers(page.layers)
            restructure(page.layers)
            writeLayerProps(page.layers)
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
            ds.sql(ProjectFormat.SYSTEM_ROWS_SCHEMA)
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
        // A feature deleted here: a photo waits for it to come back (its fid may go to another); a deletion has nothing to do.
        if (store.feature(fid) == null) return deleted
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
        Outbox.seenSystemRow(ds, id, seq)
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

    // The merged properties of the shared layers as the workspace has them (protocol v1 7.1): how each is drawn and its
    // place. A layer this phone still has a change of to send stands as it is here; its name stays (backlog).
    private fun writeLayerProps(layers: List<PulledLayer>) {
        if (layers.isEmpty()) return
        val waiting = project.data(Outbox::waiting)
        val places = ArrayList<Pair<String, Int>>()
        for (l in layers) {
            if (l.deleted || l.id in waiting || project.layers.none { it.id == l.id }) continue
            l.style?.let { style -> if ((project.styles[l.id] ?: JsonNull) != style) project.data { project.applyStyle(l.id, style) } }
            l.order?.let { places += l.id to it.toInt() }
            project.data { Outbox.seenSystemRow(it, l.id, l.seq) }
        }
        if (places.isEmpty()) return
        // A place counts among the shared layers: each to the one it came with, the lowest first (the sender's order),
        // into the slots the shared layers have here; layers only this phone has stay where they are.
        val all = project.layers.map { it.id }
        val shared = project.data(Outbox::systemRows)
        val ranked = all.filter { it in shared }.toMutableList()
        for ((id, at) in places.sortedBy { it.second }) {
            ranked.remove(id)
            ranked.add(at.coerceIn(0, ranked.size), id)
        }
        val next = ranked.iterator()
        val ids = all.map { if (it in shared) next.next() else it }
        if (ids != all) project.setOrder(ids.map { id -> project.layers.first { it.id == id }.name })
    }

    // The shared layers the project lacks, made with their ids; how many. A deleted one is left out (backlog).
    private fun makeLayers(layers: List<PulledLayer>): Int {
        var made = 0
        for (l in layers) {
            if (l.deleted || project.layers.any { it.id == l.id } || l.id in project.hiddenLayers()) continue
            val geometry = when (l.geometryType) {
                "Point" -> "POINT"
                "LineString" -> "LINESTRING"
                "Polygon" -> "POLYGON"
                "MultiPoint" -> "MULTIPOINT"
                "MultiLineString" -> "MULTILINESTRING"
                "MultiPolygon" -> "MULTIPOLYGON"
                else -> "NONE"
            }
            val fields = l.fields.map { f -> Triple(f.id, SharedSchema.localName(f.name), SharedSchema.localType(f.type)) }
            project.createSharedLayer(l.id, project.freeLayerName(l.name ?: "layer"), geometry, l.crs, fields)
            project.data { SharedSchema.put(it, l.id, l.schemaVersion, l.fields) }
            made++
        }
        return made
    }

    /**
     * Shared layers the team deleted, hidden here with their features, and
     * ones it restored, shown again (protocol v1 5.6). What a hidden one has
     * to send goes all the same: the server refuses it, and it stays as not
     * accepted (4.6).
     */
    private fun followDeletions(layers: List<PulledLayer>) {
        val hidden = project.hiddenLayers()
        val hide = layers.filter { l -> l.deleted && project.layers.any { it.id == l.id } }.map { it.id }
        val show = layers.filter { !it.deleted && it.id in hidden }.map { it.id }
        if (hide.isEmpty() && show.isEmpty()) return
        // Nothing is unsaved while a pull comes in; were it, the page would come again with the next pull.
        project = changing(project) {
            hide.forEach { project.hideLayer(it, true) }
            show.forEach { project.hideLayer(it, false) }
        } ?: throw IllegalStateException("edits are unsaved")
    }

    /**
     * The fields of the shared layers in [layers] as the team has them now
     * (protocol v1 7.4): one it added, added here with its id; one it renamed,
     * renamed; one it deleted, deleted; the structure is kept as the
     * workspace's then. A field of this phone's own, not sent yet, with a name
     * one of the team's has takes the team's id when that one is new here and
     * of one kind with it, and otherwise gives its name up.
     */
    private fun restructure(layers: List<PulledLayer>) {
        val here = layers.filter { l -> !l.deleted && project.layers.any { it.id == l.id } }
        val changes = here.filter(::differs)
        if (changes.isNotEmpty()) {
            // Nothing is unsaved while a pull comes in; were it, the page would come again with the next pull.
            project = changing(project) { changes.forEach(::follow) } ?: throw IllegalStateException("edits are unsaved")
        }
        for (l in here) project.data { SharedSchema.put(it, l.id, l.schemaVersion, l.fields) }
    }

    /**
     * Field [field] of shared layer [layer] renamed [to] another name, or with
     * null deleted, with the team (protocol v1 7.3): online, from the version
     * of the layer the phone has; then the field here follows the workspace's
     * answer. The team having changed the layer meanwhile is a [ServerError]
     * stale, and the layer comes in as it is now, to do it again on; a name
     * another field there has is name_taken.
     */
    fun changeField(layer: String, field: String, to: String?) {
        val l = project.layers.single { it.name == layer }
        val id = project.fieldIds(layer).getValue(field)
        val version = project.data { SharedSchema.of(it, l.id) }?.version ?: 0
        val now = try {
            if (to == null) {
                client.deleteField(account.workspace, account.token, l.id, version, id)
            } else {
                client.renameField(account.workspace, account.token, l.id, version, id, to)
            }
        } catch (e: ServerError) {
            if (e.code == SyncActions.STALE) client.layerIn(e)?.let { restructure(listOf(it)) }
            throw e
        }
        restructure(listOf(now))
    }

    /** Shared layer [layer] deleted with the team (protocol v1 5.6): a tombstone there. One gone there already is too. */
    fun deleteLayer(layer: String) {
        val id = project.layers.single { it.name == layer }.id
        try {
            client.deleteLayer(account.workspace, account.token, id)
        } catch (e: ServerError) {
            if (e.code != "not_found") throw e
        }
    }

    // Whether layer [l]'s fields here are other than the team's: one gone there, one new or renamed there, or one of this
    // phone's own with a name one of the team's has.
    private fun differs(l: PulledLayer): Boolean {
        val ids = project.fieldIds(project.layers.first { it.id == l.id }.name) // name -> id
        val team = l.fields.associateBy { it.id }
        val before = project.data { SharedSchema.of(it, l.id) }?.fields.orEmpty()
        return before.any { it.id !in team && it.id in ids.values } ||
            l.fields.any { f -> ids.none { (name, id) -> id == f.id && name == SharedSchema.localName(f.name) } } ||
            ids.any { (name, id) -> id !in team && l.fields.any { SharedSchema.sameName(it.name, name) } }
    }

    // Layer [l]'s fields here made the team's, as [restructure] has it, while the project's file is being changed.
    private fun follow(l: PulledLayer) {
        val layer = project.layers.first { it.id == l.id }.name
        val team = l.fields.associateBy { it.id }
        // The team had them and has them no more: deleted there, deleted here. Ones it never had are this phone's own.
        for (gone in project.data { SharedSchema.of(it, l.id) }?.fields.orEmpty().filter { it.id !in team }) {
            project.fieldIds(layer).entries.firstOrNull { it.value == gone.id }?.let { project.deleteField(layer, it.key) }
        }
        val types = project.target(layer).store.fields.associate { it.name to it.type }
        for (f in l.fields) {
            val ids = project.fieldIds(layer)
            val mine = ids.entries.firstOrNull { (name, id) -> id !in team && SharedSchema.sameName(name, f.name) }?.key ?: continue
            if (f.id !in ids.values && sameKind(types[mine], f)) {
                project.data { project.adoptFieldId(layer, l.id, mine, f.id) }
            } else {
                giveUp(layer, mine, l.fields)
            }
        }
        // Renamed there: by way of names no field has, so that names two fields swapped go too.
        val moving = project.fieldIds(layer).mapNotNull { (name, id) ->
            val to = team[id]?.let { SharedSchema.localName(it.name) }
            if (to == null || to == name) null else Triple(name, "geocrew_" + UUID.randomUUID().toString().replace("-", ""), to)
        }
        for ((from, via, _) in moving) project.renameField(layer, from, via)
        for ((_, via, to) in moving) project.renameField(layer, via, to)
        // New there: added with their ids.
        val have = project.fieldIds(layer).values.toSet()
        for (f in l.fields) if (f.id !in have) project.addField(layer, SharedSchema.localName(f.name), SharedSchema.localType(f.type), f.id)
    }

    // Whether a field of this phone's of [type] and the team's [f] are one (protocol v1 7.4): of one type, with no options.
    private fun sameKind(type: FieldType?, f: FieldDef): Boolean =
        type != null && SharedSchema.remoteType(type) == f.type && f.options.isNullOrEmpty()

    // Field [field] of [layer], this phone's own, takes a name no field of the layer has, here or in [team]: "岩性 2"…
    private fun giveUp(layer: String, field: String, team: List<FieldDef>) {
        val taken = project.fieldIds(layer).keys + team.map { SharedSchema.localName(it.name) }
        val base = field.take(ProjectFormat.MAX_NAME - 4).trimEnd()
        val to = generateSequence(2) { it + 1 }.map { "$base $it" }.first { n -> taken.none { SharedSchema.sameName(it, n) } }
        project.renameField(layer, field, to)
        renamed += Renamed(layer, field, to)
    }

    /**
     * What the workspace has not taken of the project's structure goes
     * (protocol v1 7.1, 7.3, 7.4): layers made here since it was shared, made
     * there; fields added here, added there. A layer or field the server
     * refuses (a member who may not change the structure) stays here, its
     * values with it; the rest of the sync goes on.
     */
    private fun sendStructure() {
        val shared = project.data(Outbox::systemRows)
        for (layer in project.layers.filter { it.id !in shared }) {
            val spec = runCatching { spec(project, layer) }.getOrNull() ?: continue // a layer the workspace cannot take
            val made = try {
                client.createLayer(account.workspace, account.token, spec)
            } catch (e: ServerError) {
                client.layerIn(e) ?: continue // there already, otherwise: as it is there; refused: stays here
            }
            // Its features go as new rows, as when a project is shared: ones not edited here since (imported, copied with
            // the layer) are not queued yet; ones that are stay as they are.
            val store = project.target(layer.name).store
            project.data { ds ->
                ds.begin()
                try {
                    Outbox.seenSystemRow(ds, made.id, made.seq)
                    SharedSchema.put(ds, made.id, made.schemaVersion, made.fields)
                    Outbox.enqueueNew(ds, layer.name, layer.id, store.features(), attachmentsOf = { store.attachments(it) })
                    ds.commit()
                } catch (e: Exception) {
                    runCatching { ds.rollback() }
                    throw e
                }
            }
        }
        for (layer in project.layers) project.data { SharedSchema.of(it, layer.id) }?.let { sendFields(layer, it) }
    }

    /**
     * The fields of [layer] the workspace, with [remote] of it, has not got,
     * sent as adds: so from any version. A field of the team's with a name one
     * of them has (protocol v1 7.4) takes that one over, values and all, when
     * the two are of one kind; otherwise this phone's gives its name up and
     * goes again under another, unless edits are unsaved: then it waits.
     */
    private fun sendFields(layer: Project.Layer, remote: SharedSchema.Layer) {
        var known = remote
        repeat(3) {
            val types = project.target(layer.name).store.fields.associate { it.name to it.type }
            val adds = project.fieldIds(layer.name).filter { (_, id) -> known.fields.none { it.id == id } }
                .map { (name, id) -> FieldDef(id, name, SharedSchema.remoteType(types[name] ?: FieldType.STRING)) }
            if (adds.isEmpty()) return
            val theirs = try {
                val l = client.addFields(account.workspace, account.token, layer.id, known.version, adds)
                project.data { SharedSchema.put(it, l.id, l.schemaVersion, l.fields) }
                return
            } catch (e: ServerError) {
                client.fieldIn(e) ?: return // refused otherwise: they stay here
            }
            val mine = adds.firstOrNull { SharedSchema.sameName(it.name, theirs.name) } ?: return
            known = known.copy(fields = known.fields.filter { it.id != theirs.id } + theirs)
            if (theirs.id !in project.fieldIds(layer.name).values && sameKind(types[mine.name], theirs)) {
                // One field made twice offline: this phone's values go into the team's.
                project.data {
                    project.adoptFieldId(layer.name, layer.id, mine.name, theirs.id)
                    SharedSchema.put(it, layer.id, known.version, known.fields)
                }
            } else {
                project = changing(project) { giveUp(layer.name, mine.name, known.fields) } ?: return
            }
        }
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
                val type = SharedSchema.remoteType(f.type)
                FieldDef(id, f.name, type)
            }
            return LayerSpec(
                layer.id, layer.name, geometry, layer.srs ?: Crs.LONGITUDE_LATITUDE, fields,
                style = project.styles[layer.id], order = project.layers.indexOf(layer).toDouble(),
            )
        }
    }
}
