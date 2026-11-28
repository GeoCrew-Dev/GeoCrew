package org.geocrew.storage

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.geocrew.core.edit.Attachment
import org.geocrew.core.edit.Feature
import org.geocrew.core.edit.Saved
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.quote
import org.geocrew.nativelib.VectorDataset

/**
 * What a project linked to a team's workspace has to send it (protocol v1
 * 8.4, ADR 0008), kept in the project itself so it outlives anything: each
 * feature's row id (a UUID, as the protocol names rows) and the server
 * sequence number it was last seen at ([ProjectFormat.ROWS_TABLE]), and each
 * field edited since, with its value and when it was edited
 * ([ProjectFormat.OUTBOX_TABLE]; one entry a field, the last edit kept). A
 * field goes by its stable id (geocrew_fields), the geometry as "geom", a
 * deletion as "deleted". Written in the save's own transaction, so a save and
 * what it has to send never part. Nothing is kept for a project not linked.
 */
internal object Outbox {
    /** The geometry, one field (protocol v1 6.1). */
    const val GEOMETRY = "geom"

    /** A deletion of the row. */
    const val DELETED = "deleted"

    /** The system table attachment rows are in (protocol v1 9.1): a change's layer for them. */
    const val ATTACHMENTS = "attachments"

    /** The system table of layers, whose merged properties change as a row's fields do (protocol v1 7.1); a layer is a row by its id. */
    const val LAYERS = "layers"

    /** Merged properties of a layer (protocol v1 7.1): how it is drawn (ADR 0006) and its place, 0 drawn on top. */
    const val STYLE = "style"
    const val ORDER = "order"

    /** The project's key in its own table naming the workspace it is linked to. */
    const val WORKSPACE_KEY = "workspace"

    // Edit times in UTC, always to the millisecond, so they sort as text.
    private val TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** Whether the project in [ds] is linked to a workspace. */
    fun linked(ds: VectorDataset): Boolean =
        rows(ds, "SELECT value FROM ${ProjectFormat.PROJECT_TABLE} WHERE key = ${quote(WORKSPACE_KEY)}").isNotEmpty()

    /** Layer [layer]'s fields by name, to their stable ids. */
    fun fieldIds(ds: VectorDataset, layer: String): Map<String, String> =
        rows(ds, "SELECT column_name, id FROM ${ProjectFormat.FIELDS_TABLE} WHERE table_name = ${quote(layer)}")
            .mapNotNull { r -> r["column_name"]?.let { name -> r["id"]?.let { name to it } } }.toMap()

    /**
     * Records [changes] that a save made to layer [layer] (id [layerId]), when
     * the project is linked. A feature the workspace has not had goes whole: a
     * new one, or one made before the project was linked, read with [whole],
     * with all its attachments ([attachmentsOf]).
     */
    fun record(
        ds: VectorDataset,
        layer: String,
        layerId: String,
        changes: List<Saved>,
        whole: (Long) -> Feature?,
        attachmentsOf: (Long) -> List<Attachment> = { emptyList() },
        now: Long = System.currentTimeMillis(),
    ) {
        if (changes.isEmpty() || !linked(ds)) return
        ds.sql(ProjectFormat.ROWS_SCHEMA)
        ds.sql(ProjectFormat.OUTBOX_SCHEMA)
        val ids = fieldIds(ds, layer)
        for (change in changes) {
            val known = if (change.added) null else lookup(ds, layerId, change.fid)
            if (change.deleted) {
                // One the workspace never had: nothing to tell it.
                if (known != null) put(ds, known, layerId, DELETED, JsonPrimitive(true), change.deletedTime ?: now)
                continue
            }
            var sent = change
            if (known == null && !change.added) {
                val f = whole(change.fid) ?: continue
                sent = change.copy(values = f.properties, geometrySet = true, geometry = f.geometry, attachments = attachmentsOf(change.fid), attachmentsBefore = emptyList())
            }
            val row = known ?: newRow(ds, layerId, change.fid)
            // A field with no edit time of its own (one a new feature was not given) has the feature's latest.
            val latest = listOfNotNull(change.geometryTime, change.times.values.maxOrNull()).maxOrNull() ?: now
            for ((name, value) in sent.values) {
                val field = ids[name] ?: continue // a field GeoCrew gave no id cannot be sent
                put(ds, row, layerId, field, value, change.times[name] ?: latest)
            }
            if (sent.geometrySet) put(ds, row, layerId, GEOMETRY, sent.geometry ?: JsonNull, change.geometryTime ?: latest)
            sent.attachments?.let { recordAttachments(ds, layerId, row, sent.attachmentsBefore, it, now) }
        }
    }

    /**
     * The attachment rows of the feature that is row [row] of layer [layerId]
     * (protocol v1 9.1): one new goes whole and its files wait to be uploaded;
     * one changed goes as what changed; one taken off goes deleted, when the
     * workspace has or is to have it. A deleted feature's stay as they are:
     * the row comes back with them if it is restored.
     */
    private fun recordAttachments(ds: VectorDataset, layerId: String, row: String, before: List<Attachment>, after: List<Attachment>, now: Long) {
        ds.sql(ProjectFormat.SYSTEM_ROWS_SCHEMA)
        ds.sql(ProjectFormat.UPLOADS_SCHEMA)
        val had = before.associateBy { it.id }
        for (a in after) {
            val fields = attachmentFields(a, layerId, row)
            val old = had[a.id]?.takeIf { knownAttachment(ds, a.id) }
            if (old == null) {
                ds.sql("INSERT OR IGNORE INTO ${ProjectFormat.SYSTEM_ROWS_TABLE} (uuid) VALUES (${quote(a.id)})")
                upload(ds, a.file, a.type, thumb = false)
                a.thumb?.let { upload(ds, it, "image/jpeg", thumb = true) }
                for ((field, value) in fields) put(ds, a.id, ATTACHMENTS, field, value, now)
            } else {
                val was = attachmentFields(old, layerId, row)
                for ((field, value) in fields) if (was[field] != value) put(ds, a.id, ATTACHMENTS, field, value, now)
            }
        }
        val kept = after.mapTo(HashSet()) { it.id }
        for (a in before) {
            if (a.id in kept || !knownAttachment(ds, a.id)) continue
            // Taken off before its row ever left the phone: its files need not go either, unless another attachment has them.
            if (!sentAttachment(ds, a.id)) listOfNotNull(a.file, a.thumb).filterNot { usedElsewhere(ds, it) }.forEach { uploaded(ds, it) }
            put(ds, a.id, ATTACHMENTS, DELETED, JsonPrimitive(true), now)
        }
    }

    // Whether attachment row [uuid] has gone, or is going, to the server: seen there, or in a change on its way.
    private fun sentAttachment(ds: VectorDataset, uuid: String): Boolean {
        val seq = rows(ds, "SELECT seq FROM ${ProjectFormat.SYSTEM_ROWS_TABLE} WHERE uuid = ${quote(uuid)}").firstOrNull()?.get("seq")?.toLongOrNull() ?: 0
        return seq > 0 || (exists(ds, ProjectFormat.SENDING_TABLE) && rows(ds, "SELECT row FROM ${ProjectFormat.SENDING_TABLE} WHERE row = ${quote(uuid)}").isNotEmpty())
    }

    // Whether an attachment the project still has names file [sha].
    private fun usedElsewhere(ds: VectorDataset, sha: String): Boolean =
        exists(ds, AttachmentTables.TABLE) &&
            rows(ds, "SELECT uuid FROM ${AttachmentTables.TABLE} WHERE file = ${quote(sha)} OR thumb = ${quote(sha)} LIMIT 1").isNotEmpty()

    // An attachment as its row has it (protocol v1 9.1); a name of more than 255 characters is cut.
    private fun attachmentFields(a: Attachment, layerId: String, row: String): Map<String, JsonElement> = linkedMapOf(
        "target_layer" to JsonPrimitive(layerId),
        "target_row" to JsonPrimitive(row),
        "file" to JsonPrimitive(a.file),
        "thumb" to (a.thumb?.let(::JsonPrimitive) ?: JsonNull),
        "type" to JsonPrimitive(a.type),
        "size" to JsonPrimitive(a.size),
        "name" to (a.name.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it.take(255)) } ?: JsonNull),
        "meta" to (a.meta ?: JsonNull),
    )

    private fun knownAttachment(ds: VectorDataset, uuid: String): Boolean =
        rows(ds, "SELECT uuid FROM ${ProjectFormat.SYSTEM_ROWS_TABLE} WHERE uuid = ${quote(uuid)}").isNotEmpty()

    // A file to upload; the same one named twice goes once.
    private fun upload(ds: VectorDataset, sha: String, type: String, thumb: Boolean) {
        ds.sql("INSERT OR IGNORE INTO ${ProjectFormat.UPLOADS_TABLE} (sha, type, thumb) VALUES (${quote(sha)}, ${quote(type)}, ${if (thumb) 1 else 0})")
    }

    /**
     * Merged property [field] of layer [layerId] changed here (protocol v1
     * 7.1): it goes as a change of the layer's row in the system table
     * layers, when the project is linked.
     */
    fun recordLayer(ds: VectorDataset, layerId: String, field: String, value: JsonElement, now: Long = System.currentTimeMillis()) {
        if (!linked(ds)) return
        ds.sql(ProjectFormat.OUTBOX_SCHEMA)
        ds.sql(ProjectFormat.SYSTEM_ROWS_SCHEMA)
        ds.sql("INSERT OR IGNORE INTO ${ProjectFormat.SYSTEM_ROWS_TABLE} (uuid) VALUES (${quote(layerId)})")
        put(ds, layerId, LAYERS, field, value, now)
    }

    /** The system-table rows the workspace has or is to have: attachments, and the layers it shares. */
    fun systemRows(ds: VectorDataset): Set<String> =
        if (!exists(ds, ProjectFormat.SYSTEM_ROWS_TABLE)) emptySet() else rows(ds, "SELECT uuid FROM ${ProjectFormat.SYSTEM_ROWS_TABLE}").mapNotNullTo(HashSet()) { it["uuid"] }

    /** Attachment row [uuid] was last seen at [seq]: its next change's base seq; the workspace has it. */
    fun seenSystemRow(ds: VectorDataset, uuid: String, seq: Long) {
        ds.sql(ProjectFormat.SYSTEM_ROWS_SCHEMA)
        ds.sql("INSERT OR REPLACE INTO ${ProjectFormat.SYSTEM_ROWS_TABLE} (uuid, seq) VALUES (${quote(uuid)}, $seq)")
    }

    /** How many attachment files wait to be uploaded. */
    fun filesWaiting(ds: VectorDataset): Int =
        if (!exists(ds, ProjectFormat.UPLOADS_TABLE)) 0 else rows(ds, "SELECT COUNT(*) AS n FROM ${ProjectFormat.UPLOADS_TABLE}").first()["n"]?.toIntOrNull() ?: 0

    /** The files waiting to be uploaded, thumbnails or originals, oldest first: hash and type. */
    fun uploads(ds: VectorDataset, thumbs: Boolean): List<Pair<String, String>> =
        if (!exists(ds, ProjectFormat.UPLOADS_TABLE)) {
            emptyList()
        } else {
            rows(ds, "SELECT sha, type FROM ${ProjectFormat.UPLOADS_TABLE} WHERE thumb = ${if (thumbs) 1 else 0} ORDER BY rowid").map { it.getValue("sha")!! to it.getValue("type")!! }
        }

    /** File [sha] is up, or can never go: no longer waiting. */
    fun uploaded(ds: VectorDataset, sha: String) {
        ds.sql("DELETE FROM ${ProjectFormat.UPLOADS_TABLE} WHERE sha = ${quote(sha)}")
    }

    /**
     * Queues the features of layer [layer] (id [layerId]) that the workspace
     * has not had, whole, as new rows: the project joins a workspace (protocol
     * v1 5.6), with their attachments ([attachmentsOf]). Features already
     * queued or sent keep their rows, so it can run again after a share cut short.
     */
    fun enqueueNew(
        ds: VectorDataset,
        layer: String,
        layerId: String,
        features: List<Feature>,
        attachmentsOf: (Long) -> List<Attachment> = { emptyList() },
        now: Long = System.currentTimeMillis(),
    ) {
        ds.sql(ProjectFormat.ROWS_SCHEMA)
        ds.sql(ProjectFormat.OUTBOX_SCHEMA)
        val ids = fieldIds(ds, layer)
        for (f in features) {
            if (lookup(ds, layerId, f.fid) != null) continue
            val row = newRow(ds, layerId, f.fid)
            for ((name, value) in f.properties) ids[name]?.let { put(ds, row, layerId, it, value, now) }
            put(ds, row, layerId, GEOMETRY, f.geometry ?: JsonNull, now)
            attachmentsOf(f.fid).takeIf { it.isNotEmpty() }?.let { recordAttachments(ds, layerId, row, emptyList(), it, now) }
        }
    }

    /** The feature of layer [layerId] that row [uuid] is, if it has been here. */
    // Named otherwise: GDAL takes a result's column called fid for the result feature's own id, not a value of it.
    fun fidOf(ds: VectorDataset, layerId: String, uuid: String): Long? =
        rows(ds, "SELECT fid AS feature FROM ${ProjectFormat.ROWS_TABLE} WHERE layer_id = ${quote(layerId)} AND uuid = ${quote(uuid)}").firstOrNull()?.get("feature")?.toLongOrNull()

    /** Row [uuid] is feature [fid] of layer [layerId], seen at [seq]: in place of where it was before (a row back after a deletion). */
    fun map(ds: VectorDataset, layerId: String, fid: Long, uuid: String, seq: Long) {
        ds.sql("INSERT OR REPLACE INTO ${ProjectFormat.ROWS_TABLE} (layer_id, fid, uuid, seq) VALUES (${quote(layerId)}, $fid, ${quote(uuid)}, $seq)")
    }

    /** Row [uuid] was last seen at [seq]: the base seq of its next change (protocol v1 8.2). */
    fun seen(ds: VectorDataset, uuid: String, seq: Long) {
        ds.sql("UPDATE ${ProjectFormat.ROWS_TABLE} SET seq = $seq WHERE uuid = ${quote(uuid)}")
    }

    /**
     * The fields each row still has to send, by row: in the outbox, or in a
     * change on its way the server has not refused. A pull leaves them as they
     * are here (protocol v1 8.4).
     */
    fun waiting(ds: VectorDataset): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        if (exists(ds, ProjectFormat.OUTBOX_TABLE)) {
            for (r in rows(ds, "SELECT row, field FROM ${ProjectFormat.OUTBOX_TABLE}")) {
                out.getOrPut(r.getValue("row")!!) { HashSet() } += r.getValue("field")!!
            }
        }
        if (exists(ds, ProjectFormat.SENDING_TABLE)) {
            for (r in rows(ds, "SELECT row, body FROM ${ProjectFormat.SENDING_TABLE} WHERE rejected IS NULL")) {
                val body = Json.parseToJsonElement(r.getValue("body")!!).jsonObject
                val fields = out.getOrPut(r.getValue("row")!!) { HashSet() }
                (body["set"] as? JsonObject)?.keys?.let(fields::addAll)
                if ("delete" in body) fields += DELETED
            }
        }
        return out
    }

    /** The row id of feature [fid] of layer [layerId], if it has one. */
    fun rowId(ds: VectorDataset, layerId: String, fid: Long): String? =
        if (exists(ds, ProjectFormat.ROWS_TABLE)) lookup(ds, layerId, fid) else null

    /** What the project in [ds] has to send, oldest edit first: row id, layer id, field id, value (JSON) and edit time. */
    fun pending(ds: VectorDataset): List<Map<String, String?>> {
        if (!exists(ds, ProjectFormat.OUTBOX_TABLE)) return emptyList()
        return rows(ds, "SELECT row, layer_id, field, value, edited_at FROM ${ProjectFormat.OUTBOX_TABLE} ORDER BY edited_at, row, field")
    }

    // The row id of feature [fid], the table being there.
    private fun lookup(ds: VectorDataset, layerId: String, fid: Long): String? =
        rows(ds, "SELECT uuid FROM ${ProjectFormat.ROWS_TABLE} WHERE layer_id = ${quote(layerId)} AND fid = $fid").firstOrNull()?.get("uuid")

    private fun exists(ds: VectorDataset, table: String): Boolean =
        rows(ds, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ${quote(table)}").isNotEmpty()

    // A new row id for feature [fid], in place of any it had: the fid of a feature gone may come back.
    private fun newRow(ds: VectorDataset, layerId: String, fid: Long): String {
        val uuid = UUID.randomUUID().toString()
        ds.sql("INSERT OR REPLACE INTO ${ProjectFormat.ROWS_TABLE} (layer_id, fid, uuid) VALUES (${quote(layerId)}, $fid, ${quote(uuid)})")
        return uuid
    }

    // One field's latest edit, in place of any before it.
    private fun put(ds: VectorDataset, row: String, layerId: String, field: String, value: JsonElement, editedAt: Long) {
        ds.sql(
            "INSERT OR REPLACE INTO ${ProjectFormat.OUTBOX_TABLE} (row, layer_id, field, value, edited_at) VALUES " +
                "(${quote(row)}, ${quote(layerId)}, ${quote(field)}, ${quote(value.toString())}, ${quote(TIME.format(Instant.ofEpochMilli(editedAt)))})",
        )
    }
}
