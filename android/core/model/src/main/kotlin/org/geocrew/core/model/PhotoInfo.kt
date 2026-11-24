package org.geocrew.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Where and which way a photo was taken (protocol v0 §6.2). Kept per
 * attachment hash in the record's `photo_info` object, so a record with
 * several photos knows each one's direction.
 */
data class PhotoInfo(
    /** True bearing the camera looked along, 0–360°. */
    val azimuth: Double? = null,
    val position: LngLat? = null,
    val takenAt: Instant? = null,
) {
    val isEmpty: Boolean get() = azimuth == null && position == null && takenAt == null

    /** Fills what this one lacks from [other]. */
    fun orElse(other: PhotoInfo?): PhotoInfo = if (other == null) this else PhotoInfo(
        azimuth = azimuth ?: other.azimuth,
        position = position ?: other.position,
        takenAt = takenAt ?: other.takenAt,
    )

    fun toJson(): JsonObject = buildJsonObject {
        azimuth?.let { put("azimuth", round(it, 1)) }
        position?.let { p ->
            put("lon", round(p.lng, 8))
            put("lat", round(p.lat, 8))
            p.alt?.let { put("alt", round(it, 2)) }
        }
        takenAt?.let { put("taken_at", it.toString()) }
    }

    /** Lines stamped onto an exported copy of the photo, most important first. */
    fun stamp(title: String?, project: String?, zone: ZoneId, format: CoordinateFormat = CoordinateFormat.DMS): List<String> = buildList {
        listOfNotNull(title?.takeIf { it.isNotBlank() }, project?.takeIf { it.isNotBlank() }).takeIf { it.isNotEmpty() }
            ?.let { add(it.joinToString(" · ")) }
        position?.let { p ->
            add(CoordinateFormatter.format(p, format) + (p.alt?.let { " · 海拔 %.0f m".fmt(it) } ?: ""))
        }
        azimuth?.let { add("拍摄方向 %.0f°（%s）".fmt(it, SeekGuide.direction(it))) }
        takenAt?.let { add(STAMP_TIME.withZone(zone).format(it)) }
    }

    companion object {
        private val STAMP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun fromJson(o: JsonObject): PhotoInfo {
            val lon = o.double("lon")
            val lat = o.double("lat")
            val pos = if (lon != null && lat != null && lat in -90.0..90.0 && lon in -180.0..180.0) LngLat(lon, lat, o.double("alt")) else null
            return PhotoInfo(
                azimuth = o.double("azimuth")?.takeIf { it.isFinite() }?.let(Geodesy::normalizeDegrees),
                position = pos,
                takenAt = o.string("taken_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }

        private fun round(v: Double, digits: Int): Double {
            val f = Math.pow(10.0, digits.toDouble())
            return Math.round(v * f) / f
        }
    }
}

/** Per-photo information of this record, by attachment hash. */
val GeoFeature.photoInfo: Map<String, PhotoInfo>
    get() = (properties[Props.PHOTO_INFO] as? JsonObject)
        ?.mapNotNull { (sha, v) -> (v as? JsonObject)?.let { sha to PhotoInfo.fromJson(it) } }
        ?.toMap().orEmpty()

/** Sets (or with null, removes) the information of photo [sha]; drops the key when nothing is left. */
fun PropertiesBuilder.photoInfo(sha: String, info: PhotoInfo?) {
    val cur = (map[Props.PHOTO_INFO] as? JsonObject).orEmpty().toMutableMap()
    if (info == null || info.isEmpty) cur.remove(sha) else cur[sha] = info.toJson()
    put(Props.PHOTO_INFO, if (cur.isEmpty()) null else JsonObject(cur))
}

