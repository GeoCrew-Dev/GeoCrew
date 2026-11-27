package org.geocrew.storage

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
     * new one, or one made before the project was linked, read with [whole].
     */
    fun record(ds: VectorDataset, layer: String, layerId: String, changes: List<Saved>, whole: (Long) -> Feature?, now: Long = System.currentTimeMillis()) {
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
                sent = change.copy(values = f.properties, geometrySet = true, geometry = f.geometry)
            }
            val row = known ?: newRow(ds, layerId, change.fid)
            // A field with no edit time of its own (one a new feature was not given) has the feature's latest.
            val latest = listOfNotNull(change.geometryTime, change.times.values.maxOrNull()).maxOrNull() ?: now
            for ((name, value) in sent.values) {
                val field = ids[name] ?: continue // a field GeoCrew gave no id cannot be sent
                put(ds, row, layerId, field, value, change.times[name] ?: latest)
            }
            if (sent.geometrySet) put(ds, row, layerId, GEOMETRY, sent.geometry ?: JsonNull, change.geometryTime ?: latest)
        }
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
