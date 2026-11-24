package org.geocrew.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** When the group meets at this point, if it is a meeting point (protocol §6.8). */
val GeoFeature.meetAt: Instant?
    get() = if (geometry is Geometry.Point) properties.string(Props.MEET_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() } else null

/**
 * Meeting points: a point record with a `meet_at` time is where the group
 * gathers, to set off together, to eat or to be picked up. The next one is
 * shown on everyone's map with how far and which way it is.
 */
object Rendezvous {
    /** A meeting stays on screen this long after its time, for whoever is late. */
    val GRACE: Duration = Duration.ofHours(1)

    /** How far ahead a meeting is worth showing. */
    val HORIZON: Duration = Duration.ofHours(24)

    data class Next(val feature: GeoFeature, val at: Instant, val position: LngLat)

    /** The soonest meeting from [GRACE] ago to [HORIZON] ahead of [now]; for meetings already due, the latest. */
    fun next(features: List<GeoFeature>, now: Instant): Next? = features.asSequence()
        .filter { !it.deleted }
        .mapNotNull { f ->
            val at = f.meetAt ?: return@mapNotNull null
            val p = (f.geometry as? Geometry.Point)?.position ?: return@mapNotNull null
            Next(f, at, p)
        }
        .filter { it.at >= now.minus(GRACE) && it.at <= now.plus(HORIZON) }
        // A meeting just past beats one hours away, and of two past ones the later.
        .minByOrNull { Duration.between(now, it.at).abs() }

    /**
     * The instant for "HH:mm" typed in [zone]: today, or tomorrow when that
     * time today was more than [GRACE] ago. Null if the text isn't a time.
     */
    fun timeToday(text: String, now: Instant, zone: ZoneId): Instant? {
        val m = Regex("""^\s*(\d{1,2})\s*[:：.点]\s*(\d{1,2})\s*分?\s*$""").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val today = LocalDate.ofInstant(now, zone)
        val at = today.atTime(LocalTime.of(h, min)).atZone(zone).toInstant()
        return if (at < now.minus(GRACE)) today.plusDays(1).atTime(LocalTime.of(h, min)).atZone(zone).toInstant() else at
    }

    /** "还有 1 小时 20 分", "5 分钟后", "已过 10 分钟". */
    fun countdown(at: Instant, now: Instant): String {
        val d = Duration.between(now, at)
        val mins = Math.abs(d.toMinutes())
        val text = if (mins >= 60) "${mins / 60} 小时" + (if (mins % 60 > 0) " ${mins % 60} 分" else "") else "$mins 分钟"
        return when {
            d.isNegative && mins >= 1 -> "已过 $text"
            mins < 1 -> "就是现在"
            else -> "还有 $text"
        }
    }
}
