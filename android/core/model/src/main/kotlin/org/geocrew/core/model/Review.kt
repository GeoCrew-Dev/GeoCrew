package org.geocrew.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * A leader's review of a record (protocol §6.9): passed, or sent back to the
 * recorder with what to fix. Only leaders set it; the server keeps the stored
 * review on members' pushes, so the app only hides the controls.
 */
data class Review(
    val state: State,
    /** Member id of the leader who reviewed. */
    val by: String?,
    /** The leader's nickname then, so the review reads without a network call. */
    val byName: String? = null,
    val at: Instant?,
    val note: String = "",
) {
    enum class State(val wire: String, val label: String) {
        APPROVED("approved", "已通过"),
        REDO("redo", "退回重做"),
    }

    /** True when the record was edited after the review, so the leader may want another look. */
    fun changedSince(f: GeoFeature): Boolean = at != null && f.sync.updatedAt.isAfter(at.plusSeconds(1))

    fun toJson(): JsonObject = buildProperties {
        this["state"] = state.wire
        this["by"] = by
        this["by_name"] = byName
        this["at"] = at?.toString()
        if (note.isNotBlank()) this["note"] = note.trim()
    }

    companion object {
        /** The review in [value]; null when there is none or its state isn't one this app knows. */
        fun of(value: JsonElement?): Review? {
            val o = value as? JsonObject ?: return null
            val state = State.entries.firstOrNull { it.wire == o.string("state") } ?: return null
            return Review(
                state = state,
                by = o.string("by")?.takeIf { it.isNotBlank() },
                byName = o.string("by_name")?.takeIf { it.isNotBlank() },
                at = o.string("at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                note = o.string("note")?.trim().orEmpty(),
            )
        }
    }
}

/** The leader's review of this record, if any. */
val GeoFeature.review: Review? get() = Review.of(properties[Props.REVIEW])

/** Sets or clears the review in a properties edit. */
fun PropertiesBuilder.review(r: Review?) = put(Props.REVIEW, r?.toJson())

