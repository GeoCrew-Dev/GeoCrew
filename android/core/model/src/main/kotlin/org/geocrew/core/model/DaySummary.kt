package org.geocrew.core.model

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * What was recorded on one day in a project: points, samples, photos and
 * track length, plus how many points still lack what the record rules ask.
 * Counts records made that day (by their creation time, in [zone]) whoever
 * made them; samples count by when they were taken, falling back to the
 * record's day.
 */
data class DaySummary(
    val date: LocalDate,
    val points: Int,
    val otherRecords: Int,
    val samples: Int,
    val photos: Int,
    val trackMeters: Double,
    val incomplete: Int,
) {
    val empty: Boolean get() = points == 0 && otherRecords == 0 && samples == 0 && photos == 0 && trackMeters < 1

    /** One sentence, for the chat and for reading aloud. */
    fun text(today: Boolean = true): String {
        val day = if (today) "今天" else "${date.monthValue} 月 ${date.dayOfMonth} 日"
        if (empty) return "${day}还没有记录。"
        val parts = buildList {
            if (points > 0) add("地质点 $points 个")
            if (otherRecords > 0) add("其他记录 $otherRecords 条")
            if (samples > 0) add("样品 $samples 个")
            if (photos > 0) add("照片 $photos 张")
            if (trackMeters >= 1) add("轨迹 " + km(trackMeters))
        }
        val tail = if (incomplete > 0) "；有 $incomplete 个点缺必填项，在「待核对」里" else ""
        return day + "：" + parts.joinToString("，") + tail + "。"
    }

    companion object {
        fun km(m: Double): String = if (m < 1000) "${m.roundToInt()} 米" else "%.1f 公里".format(m / 1000)

        fun of(features: List<GeoFeature>, date: LocalDate, zone: ZoneId, rules: RecordRules = RecordRules()): DaySummary {
            fun onDay(i: java.time.Instant?) = i != null && i.atZone(zone).toLocalDate() == date
            val live = features.filter { !it.deleted }
            val made = live.filter { onDay(it.createdAt) }
            val samples = live.sumOf { f -> f.samples.count { s -> if (s.takenAt != null) onDay(s.takenAt) else onDay(f.createdAt) } }
            val track = made.filter { it.kind == FeatureKind.TRACK }.sumOf { f ->
                when (val g = f.geometry) {
                    is Geometry.LineString -> Geodesy.length(g.positions)
                    else -> 0.0
                }
            }
            val points = made.filter { it.kind == FeatureKind.GEO_POINT }
            return DaySummary(
                date = date,
                points = points.size,
                otherRecords = made.count { it.kind != FeatureKind.GEO_POINT && it.kind != FeatureKind.TRACK },
                samples = samples,
                photos = made.sumOf { it.photos.size },
                trackMeters = track,
                incomplete = points.count { rules.missing(it).isNotEmpty() },
            )
        }
    }
}
