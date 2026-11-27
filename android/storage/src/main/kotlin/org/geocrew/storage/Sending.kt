package org.geocrew.storage

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.quote
import org.geocrew.core.sync.FieldEdit
import org.geocrew.core.sync.PushChange
import org.geocrew.core.sync.PushResult
import org.geocrew.nativelib.VectorDataset

/**
 * Changes on their way to a linked project's workspace (protocol v1 8.1, 8.4;
 * ADR 0008): what the outbox holds becomes one change a row, with an id of its
 * own and the row's base seq ([ProjectFormat.SENDING_TABLE]). A change goes
 * again, the same, until the server has it, so a push cut short is never
 * applied twice; one the server refused stays on the phone with why, sent no
 * more (protocol 4.6). A row with a change on its way gets its next change
 * once the server has that one.
 */
internal object Sending {
    /** Takes what the outbox holds into changes, in one transaction; edits made after this go to the outbox again. */
    fun prepare(ds: VectorDataset) {
        if (!exists(ds, ProjectFormat.OUTBOX_TABLE)) return
        val pending = rows(ds, "SELECT row, layer_id, field, value, edited_at FROM ${ProjectFormat.OUTBOX_TABLE} ORDER BY row, field")
        if (pending.isEmpty()) return
        ds.sql(ProjectFormat.SENDING_SCHEMA)
        val waiting = rows(ds, "SELECT row FROM ${ProjectFormat.SENDING_TABLE} WHERE rejected IS NULL").mapNotNull { it["row"] }.toSet()
        val seqs = if (exists(ds, ProjectFormat.ROWS_TABLE)) {
            rows(ds, "SELECT uuid, seq FROM ${ProjectFormat.ROWS_TABLE}").associate { it["uuid"] to (it["seq"]?.toLongOrNull() ?: 0L) }
        } else {
            emptyMap()
        }
        ds.begin()
        try {
            for ((row, entries) in pending.groupBy { it.getValue("row")!! }) {
                if (row in waiting) continue
                val body = buildJsonObject {
                    val set = entries.filter { it["field"] != Outbox.DELETED }
                    if (set.isNotEmpty()) put("set", JsonObject(set.associate { it.getValue("field")!! to edit(it) }))
                    entries.firstOrNull { it["field"] == Outbox.DELETED }?.let { put("delete", edit(it)) }
                }
                ds.sql(
                    "INSERT INTO ${ProjectFormat.SENDING_TABLE} (change, row, layer_id, base_seq, body) VALUES " +
                        "(${quote(UUID.randomUUID().toString())}, ${quote(row)}, ${quote(entries.first().getValue("layer_id")!!)}, ${seqs[row] ?: 0}, ${quote(body.toString())})",
                )
                ds.sql("DELETE FROM ${ProjectFormat.OUTBOX_TABLE} WHERE row = ${quote(row)}")
            }
            ds.commit()
        } catch (e: Exception) {
            runCatching { ds.rollback() }
            throw e
        }
    }

    /** At most [max] changes to send, oldest first: those the server has not refused. */
    fun next(ds: VectorDataset, max: Int): List<PushChange> {
        if (!exists(ds, ProjectFormat.SENDING_TABLE)) return emptyList()
        return rows(ds, "SELECT change, row, layer_id, base_seq, body FROM ${ProjectFormat.SENDING_TABLE} WHERE rejected IS NULL ORDER BY rowid LIMIT $max").map { r ->
            val body = Json.parseToJsonElement(r.getValue("body")!!).jsonObject
            PushChange(
                id = r.getValue("change")!!,
                layer = r.getValue("layer_id")!!,
                row = r.getValue("row")!!,
                baseSeq = r["base_seq"]?.toLongOrNull() ?: 0,
                set = (body["set"] as? JsonObject).orEmpty().mapValues { (_, e) -> fieldEdit(e.jsonObject) },
                delete = (body["delete"] as? JsonObject)?.let(::fieldEdit),
            )
        }
    }

    /** What became of sent changes: applied or duplicate ones leave; refused ones stay, with why. */
    fun done(ds: VectorDataset, results: List<PushResult>) {
        ds.begin()
        try {
            for (r in results) {
                when (r.status) {
                    PushResult.APPLIED, PushResult.DUPLICATE -> ds.sql("DELETE FROM ${ProjectFormat.SENDING_TABLE} WHERE change = ${quote(r.id)}")
                    PushResult.REJECTED -> {
                        val why = listOfNotNull(r.error, r.message).joinToString(": ").ifEmpty { "rejected" }
                        ds.sql("UPDATE ${ProjectFormat.SENDING_TABLE} SET rejected = ${quote(why)} WHERE change = ${quote(r.id)}")
                    }
                }
            }
            ds.commit()
        } catch (e: Exception) {
            runCatching { ds.rollback() }
            throw e
        }
    }

    /** How many changes wait to be sent, and how many the server refused. */
    fun counts(ds: VectorDataset): Pair<Int, Int> {
        val queued = if (exists(ds, ProjectFormat.OUTBOX_TABLE)) {
            rows(ds, "SELECT COUNT(DISTINCT row) AS n FROM ${ProjectFormat.OUTBOX_TABLE}").firstOrNull()?.get("n")?.toIntOrNull() ?: 0
        } else {
            0
        }
        if (!exists(ds, ProjectFormat.SENDING_TABLE)) return queued to 0
        val sending = rows(ds, "SELECT COUNT(*) AS n FROM ${ProjectFormat.SENDING_TABLE} WHERE rejected IS NULL").firstOrNull()?.get("n")?.toIntOrNull() ?: 0
        val rejected = rows(ds, "SELECT COUNT(*) AS n FROM ${ProjectFormat.SENDING_TABLE} WHERE rejected IS NOT NULL").firstOrNull()?.get("n")?.toIntOrNull() ?: 0
        return queued + sending to rejected
    }

    // One outbox entry as a change's edit: its value (JSON) and when it was edited.
    private fun edit(entry: Map<String, String?>) = buildJsonObject {
        put("value", Json.parseToJsonElement(entry.getValue("value")!!))
        put("edited_at", entry.getValue("edited_at")!!)
    }

    private fun fieldEdit(o: JsonObject) =
        FieldEdit(o["value"] ?: JsonNull, Instant.parse((o["edited_at"] as? JsonPrimitive)?.contentOrNull ?: error("an edit without its time")))

    private fun exists(ds: VectorDataset, table: String): Boolean =
        rows(ds, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ${quote(table)}").isNotEmpty()
}
