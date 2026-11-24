package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The kinds of attachment a record can refer to (protocol v0 §5–6): which
 * property lists them, what they are called inside a project package and
 * how they are uploaded. A new kind is one entry here; sync, packages and
 * storage clean-up go through [GeoFeature.attachments] and follow.
 */
enum class AttachmentKind(val property: String, val dir: String, val ext: String, val mime: String) {
    PHOTO(Props.PHOTOS, "photos", ".jpg", "image/jpeg"),
    AUDIO(Props.AUDIO, "audio", ".m4a", "audio/mp4"),
}

/** One attachment a record refers to. */
data class AttachmentRef(val sha: String, val kind: AttachmentKind)

private val SHA256 = Regex("^[0-9a-f]{64}$")

/** Whether [s] is a lowercase hex SHA-256, the only form of attachment id. */
fun isAttachmentId(s: String): Boolean = SHA256.matches(s)

/** Hashes listed under [kind]'s property, in order, without anything malformed. */
fun GeoFeature.attachmentIds(kind: AttachmentKind): List<String> =
    (properties[kind.property] as? JsonArray)?.mapNotNull { it.stringOrNull()?.takeIf(::isAttachmentId) }.orEmpty()

/** Every attachment of this record, kind by kind, each hash once. */
val GeoFeature.attachments: List<AttachmentRef>
    get() {
        val seen = HashSet<String>()
        return AttachmentKind.entries.flatMap { k -> attachmentIds(k).filter(seen::add).map { AttachmentRef(it, k) } }
    }

/** Voice notes of this record, oldest first. */
val GeoFeature.audio: List<String> get() = attachmentIds(AttachmentKind.AUDIO)

/** Length, time and place of a voice note (protocol v0 §6.3). */
data class AudioInfo(
    val durationSeconds: Double? = null,
    val recordedAt: Instant? = null,
    val position: LngLat? = null,
) {
    val isEmpty: Boolean get() = durationSeconds == null && recordedAt == null && position == null

    fun toJson(): JsonObject = buildJsonObject {
        durationSeconds?.let { put("duration_s", Math.round(it * 10) / 10.0) }
        recordedAt?.let { put("recorded_at", it.toString()) }
        position?.let { p ->
            put("lon", Math.round(p.lng * 1e8) / 1e8)
            put("lat", Math.round(p.lat * 1e8) / 1e8)
            p.alt?.let { put("alt", Math.round(it * 100) / 100.0) }
        }
    }

    companion object {
        fun fromJson(o: JsonObject): AudioInfo {
            val lon = o.double("lon")
            val lat = o.double("lat")
            return AudioInfo(
                durationSeconds = o.double("duration_s")?.takeIf { it.isFinite() && it >= 0 },
                recordedAt = o.string("recorded_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                position = if (lon != null && lat != null && lat in -90.0..90.0 && lon in -180.0..180.0) LngLat(lon, lat, o.double("alt")) else null,
            )
        }

        /** "1:05" style length for lists. */
        fun clock(seconds: Double): String {
            val s = Math.round(seconds).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".fmt(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".fmt(s / 60, s % 60)
        }
    }
}

/** Per-clip information of this record, by attachment hash. */
val GeoFeature.audioInfo: Map<String, AudioInfo>
    get() = (properties[Props.AUDIO_INFO] as? JsonObject)
        ?.mapNotNull { (sha, v) -> (v as? JsonObject)?.let { sha to AudioInfo.fromJson(it) } }
        ?.toMap().orEmpty()

/** Sets (or with null, removes) the information of clip [sha]; drops the key when nothing is left. */
fun PropertiesBuilder.audioInfo(sha: String, info: AudioInfo?) {
    val cur = (map[Props.AUDIO_INFO] as? JsonObject).orEmpty().toMutableMap()
    if (info == null || info.isEmpty) cur.remove(sha) else cur[sha] = info.toJson()
    put(Props.AUDIO_INFO, if (cur.isEmpty()) null else JsonObject(cur))
}
