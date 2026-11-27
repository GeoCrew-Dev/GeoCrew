package org.geocrew.core.sync

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** A field's value as the server keeps it: who set it, when (on the server's clock) and the sequence number of that change. */
data class FieldValue(val value: JsonElement, val by: String, val at: Instant, val seq: Long)

/** A row as the server keeps it (protocol v1 6.1): its fields by id, and whether it is deleted (a deleted row keeps its fields). */
data class SyncRow(val fields: Map<String, FieldValue>, val deleted: FieldValue? = null)

/** One field's new value from a client, with the client's edit time. */
data class FieldEdit(val value: JsonElement, val editedAt: Instant)

/**
 * One row's edits from one client (protocol v1 6.2) since the version it last
 * saw ([baseSeq], 0 for a row it created), with the client's clock when it
 * sent them; [delete] true deletes the row, false restores it.
 */
data class RowChange(
    val by: String,
    val baseSeq: Long,
    val clientNow: Instant,
    val set: Map<String, FieldEdit> = emptyMap(),
    val delete: FieldEdit? = null,
)

/** A value that did not make it into the row (protocol v1 6.5), so nothing is lost. */
data class Conflict(val kind: String, val field: String? = null, val kept: FieldValue? = null, val lost: FieldValue? = null) {
    companion object {
        /** Both changed the same field; [lost] is the losing value. */
        const val VALUE = "value"

        /** The field was removed from the layer; [lost] is the edit. */
        const val DELETED_FIELD = "deleted_field"

        /** Someone else deleted the row and this change edited it: the edit wins and the row comes back. */
        const val DELETE_OVERRIDDEN = "delete_overridden"

        /** This change deleted the row and someone else edited it meanwhile: the edits win. */
        const val DELETE_REJECTED = "delete_rejected"
    }
}

/**
 * The merge rules of protocol v1 (6.4; ADR 0008), field by field, as the
 * server applies them: the same as server/internal/merge, checked against the
 * cases in protocol/testdata/merge. Pure: the caller stores the row and the
 * conflicts.
 */
object Merge {
    /** A client's edit time on the server's clock: server now − (client now − edit time), never later than server now. */
    fun editTime(editedAt: Instant, clientNow: Instant, serverNow: Instant): Instant {
        val t = serverNow.minusNanos(java.time.Duration.between(editedAt, clientNow).toNanos())
        return if (t.isAfter(serverNow)) serverNow else t
    }

    /**
     * [change] merged into [row] (null for a new row): the new row and the
     * conflicts. [live] holds the layer's current field ids; [seq] is the
     * sequence number the server gives this change, [serverNow] when it came.
     */
    fun apply(row: SyncRow?, change: RowChange, live: Set<String>, seq: Long, serverNow: Instant): Pair<SyncRow, List<Conflict>> {
        val fields = LinkedHashMap(row?.fields.orEmpty())
        var deleted = row?.deleted
        val conflicts = ArrayList<Conflict>()
        fun concurrent(f: FieldValue) = f.seq > change.baseSeq

        // Conflicts come out in a stable order.
        for (id in change.set.keys.sorted()) {
            val e = change.set.getValue(id)
            val incoming = FieldValue(e.value, change.by, editTime(e.editedAt, change.clientNow, serverNow), seq)
            if (id !in live) {
                conflicts += Conflict(Conflict.DELETED_FIELD, id, lost = incoming)
                continue
            }
            val current = fields[id]
            when {
                current == null || !concurrent(current) -> fields[id] = incoming
                same(current.value, incoming.value) -> Unit // both made the same change: nothing to resolve
                incoming.at.isBefore(current.at) -> conflicts += Conflict(Conflict.VALUE, id, kept = current, lost = incoming)
                else -> { // the later edit wins; on equal times, the one that arrived later
                    fields[id] = incoming
                    conflicts += Conflict(Conflict.VALUE, id, kept = incoming, lost = current)
                }
            }
        }

        // Fields someone else changed since this client's base.
        val editedOthers = row?.fields?.values?.any(::concurrent) == true
        val isDeleted = (deleted?.value as? JsonPrimitive)?.booleanOrNull == true
        val del = change.delete
        val wanted = (del?.value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        when {
            del != null && wanted == null -> Unit // not a boolean: ignored here, refused by the request's checks
            wanted == true -> {
                val incoming = FieldValue(JsonPrimitive(true), change.by, editTime(del!!.editedAt, change.clientNow, serverNow), seq)
                if (editedOthers && !isDeleted) {
                    conflicts += Conflict(Conflict.DELETE_REJECTED, lost = incoming)
                } else if (!isDeleted) {
                    deleted = incoming
                }
            }
            wanted == false -> if (isDeleted) {
                deleted = FieldValue(JsonPrimitive(false), change.by, editTime(del!!.editedAt, change.clientNow, serverNow), seq)
            }
            isDeleted && concurrent(deleted!!) && change.set.isNotEmpty() -> {
                conflicts += Conflict(Conflict.DELETE_OVERRIDDEN, lost = deleted)
                deleted = FieldValue(JsonPrimitive(false), change.by, serverNow, seq)
            }
        }
        return SyncRow(fields, deleted) to conflicts
    }

    /** Whether two JSON values are the same: object keys in any order, numbers by value (114 and 114.0 alike). */
    fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
        a is JsonPrimitive && b is JsonPrimitive -> when {
            a.isString || b.isString -> a.isString == b.isString && a.content == b.content
            a.doubleOrNull != null && b.doubleOrNull != null -> a.doubleOrNull == b.doubleOrNull
            else -> a.content == b.content
        }
        else -> false
    }
}
