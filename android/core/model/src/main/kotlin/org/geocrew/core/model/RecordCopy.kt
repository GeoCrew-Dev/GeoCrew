package org.geocrew.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * Copies of records for another project, e.g. points logged in the wrong
 * project or a few reference points reused in a new one. Each copy gets a
 * new id and starts its own history; references between the copied
 * records (a route's stops, a section's points) are pointed at the copies.
 * What only made sense in the old workspace goes: the leader's review and
 * the task hand-off.
 */
object RecordCopy {
    /** Keys that belong to the old workspace's people. */
    val DROPPED = setOf(Props.REVIEW, Props.ASSIGNEE, Props.ASSIGNEE_NAME, Props.TASK_STATUS)

    fun copies(records: List<GeoFeature>, projectId: String, now: Instant, newId: () -> String): List<GeoFeature> {
        val live = records.filter { !it.deleted }
        val ids = live.associate { it.id to newId() }
        return live.map { f ->
            var text = Json.encodeToString(JsonObject.serializer(), JsonObject(f.properties.filterKeys { it !in DROPPED }))
            // Ids are UUIDs, so a plain replacement cannot hit anything else.
            for ((old, new) in ids) if (text.contains(old)) text = text.replace(old, new)
            f.copy(
                id = ids.getValue(f.id),
                projectId = projectId,
                properties = Json.parseToJsonElement(text).jsonObject,
                sync = SyncInfo(updatedAt = now),
            )
        }
    }
}
