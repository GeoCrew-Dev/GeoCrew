package org.geocrew.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Checking out before going off alone (protocol v0 §8.3): where to, and
 * when to expect you back. Past that time with no word, the team is told.
 */
object Outing {
    /** The furthest ahead a return time may be set; the server refuses more. */
    val LONGEST: Duration = Duration.ofDays(7)

    /** A return time offered with one tap. */
    data class Choice(val label: String, val at: Instant)

    /**
     * One to four hours from [now], rounded up to five minutes, then sunset
     * at [position] when it is still more than half an hour away.
     */
    fun choices(now: Instant, zone: ZoneId, position: LngLat?): List<Choice> {
        val out = (1..4).map { h -> Choice("$h 小时", roundUp(now.plus(Duration.ofHours(h.toLong())))) }.toMutableList()
        val sunset = position?.let { SunTimes.of(LocalDate.ofInstant(now, zone), it).sunset }
        if (sunset != null && sunset > now.plus(Duration.ofMinutes(30))) {
            out += Choice("日落前", sunset.truncatedTo(ChronoUnit.MINUTES))
        }
        return out.sortedBy { it.at }
    }

    /** A typed "HH:mm": today, or tomorrow once today's is past. Null if not a time. */
    fun typed(text: String, now: Instant, zone: ZoneId): Instant? {
        val m = Regex("""^\s*(\d{1,2})\s*[:：.点]\s*(\d{1,2})?\s*分?\s*$""").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h > 23 || min > 59) return null
        val today = LocalDate.ofInstant(now, zone).atTime(h, min).atZone(zone).toInstant()
        return if (today > now) today else today.plus(Duration.ofDays(1))
    }

    /** "还有 1 小时 20 分", or "已超时 15 分钟" once [backBy] has passed. */
    fun due(backBy: Instant, now: Instant): String {
        val text = Rendezvous.countdown(backBy, now)
        return if (backBy < now && text.startsWith("已过 ")) "已超时 " + text.removePrefix("已过 ") else if (backBy < now) "已到时间" else text
    }

    private fun roundUp(t: Instant): Instant {
        val step = 5 * 60L
        val s = t.epochSecond
        return Instant.ofEpochSecond(((s + step - 1) / step) * step)
    }
}
