package org.geocrew.core.model

import kotlin.math.max

/**
 * Guidance towards a target point: how far, which way, and whether we
 * are there yet, given the current fix and (optionally) the direction
 * the phone points.
 */
data class SeekGuide(
    /** Ground distance in metres. */
    val distance: Double,
    /** True bearing from here to the target, 0–360°. */
    val bearing: Double,
    /** Turn needed from the current heading, −180 (left) to +180 (right); null without a heading. */
    val turn: Double?,
    /** Target altitude minus ours, when both are known. */
    val climb: Double?,
    /** Within the arrival radius (never smaller than the fix's own uncertainty). */
    val arrived: Boolean,
    val arrivalRadius: Double,
) {
    companion object {
        /** Default arrival radius: a sampling point is "found" within a few metres. */
        const val ARRIVAL_METRES = 5.0

        fun of(here: LngLat, target: LngLat, heading: Double? = null, accuracy: Double? = null, radius: Double = ARRIVAL_METRES): SeekGuide {
            val d = Geodesy.distance(here, target)
            val b = Geodesy.bearing(here, target)
            val r = max(radius, accuracy ?: 0.0)
            val turn = heading?.let { h -> ((b - h + 540) % 360) - 180 }
            val climb = if (here.alt != null && target.alt != null) target.alt - here.alt else null
            return SeekGuide(d, b, turn, climb, d <= r, r)
        }

        /** Eight-way Chinese direction name for a bearing ("东北"). */
        fun direction(bearing: Double): String {
            val names = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
            return names[(((Geodesy.normalizeDegrees(bearing) + 22.5) / 45).toInt()) % 8]
        }

        /** "左转 30°", "右转 120°", "正前方". */
        fun turnLabel(turn: Double): String = when {
            kotlin.math.abs(turn) < 10 -> "正前方"
            kotlin.math.abs(turn) > 170 -> "在身后"
            turn < 0 -> "向左 %.0f°".fmt(-turn)
            else -> "向右 %.0f°".fmt(turn)
        }
    }
}
