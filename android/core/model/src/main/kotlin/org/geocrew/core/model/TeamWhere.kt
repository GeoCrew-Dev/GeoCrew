package org.geocrew.core.model

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * "Where is everyone": each teammate's last shared position as distance and
 * direction from me, with how old it is. Used by the assistant's
 * 「大家在哪」, which works offline on the last positions received.
 */
object TeamWhere {
    data class Mate(val name: String, val at: LngLat, val time: Instant?, val online: Boolean)

    data class Row(
        val name: String,
        val at: LngLat,
        /** Metres from me, when my position is known. */
        val distance: Double?,
        val bearing: Double?,
        val ageSeconds: Long?,
        val online: Boolean,
    )

    /** Nearest first when [me] is known, otherwise freshest first. */
    fun rows(me: LngLat?, mates: List<Mate>, now: Instant): List<Row> {
        val rows = mates.map { m ->
            Row(
                m.name.ifBlank { "队友" }, m.at,
                me?.let { Geodesy.distance(it, m.at) }, me?.let { Geodesy.bearing(it, m.at) },
                m.time?.let { maxOf(0, Duration.between(it, now).seconds) }, m.online,
            )
        }
        return if (me != null) rows.sortedBy { it.distance } else rows.sortedBy { it.ageSeconds ?: Long.MAX_VALUE }
    }

    fun distanceText(m: Double): String = when {
        m < 1000 -> "${(m / 10).roundToInt() * 10} 米"
        m < 10_000 -> "%.1f 公里".format(m / 1000)
        else -> "${(m / 1000).roundToInt()} 公里"
    }

    fun ageText(s: Long): String = when {
        s < 60 -> "刚刚"
        s < 3600 -> "${s / 60} 分钟前"
        else -> "${s / 3600} 小时前"
    }

    /** One sentence, for the chat and for reading aloud. */
    fun text(rows: List<Row>, haveMe: Boolean): String {
        if (rows.isEmpty()) return "还没有队友共享位置。"
        val parts = rows.map { r ->
            buildString {
                append(r.name)
                if (r.distance != null && r.bearing != null) {
                    append(" 在").append(SeekGuide.direction(r.bearing)).append(' ').append(distanceText(r.distance))
                }
                val notes = listOfNotNull(if (!r.online) "已离线" else null, r.ageSeconds?.let(::ageText))
                if (notes.isNotEmpty()) append("（").append(notes.joinToString("，")).append("）")
            }
        }
        val head = if (haveMe) "${rows.size} 位队友：" else "拿不到你的位置，只说队友最后的位置时间：${rows.size} 位队友，"
        return head + parts.joinToString("；") + "。"
    }
}
