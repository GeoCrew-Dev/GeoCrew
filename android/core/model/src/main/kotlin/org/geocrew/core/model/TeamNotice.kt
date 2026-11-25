package org.geocrew.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The workspace's pinned notice (protocol v0 §2.2, key `notice`): one short
 * text a leader posts for the whole team, such as the day's plan or a safety
 * warning. Members see it on the team page and the map; it changes only when
 * a leader posts a new one or clears it.
 */
@Serializable
data class TeamNotice(
    val text: String,
    /** When it was posted, as RFC 3339 text. */
    @SerialName("updated_at") val updatedAt: String,
    /** The poster's nickname, for display only. */
    val by: String? = null,
) {
    val postedAt: Instant? get() = runCatching { Instant.parse(updatedAt) }.getOrNull()

    companion object {
        const val MAX_CHARS = 2000

        /** A notice for [text], or null when there is nothing to show. */
        fun of(text: String, at: Instant, by: String?): TeamNotice? =
            clean(TeamNotice(text, at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), by))

        /** Trims and caps the text and nickname; blank text means no notice. */
        fun clean(n: TeamNotice?): TeamNotice? {
            n ?: return null
            val text = n.text.trim().take(MAX_CHARS)
            if (text.isEmpty()) return null
            return TeamNotice(text, n.updatedAt.trim().take(40), n.by?.trim()?.take(40)?.ifEmpty { null })
        }
    }
}
