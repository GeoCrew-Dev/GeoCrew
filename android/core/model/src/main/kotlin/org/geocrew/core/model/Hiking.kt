package org.geocrew.core.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Walking time over terrain with Tobler's hiking function: about 5 km/h on
 * the flat, fastest on a gentle descent, slow on steep ground either way.
 * Tobler's own figure for walking off paths is 0.6 of the speed on a path.
 */
object Hiking {
    /** Speed multiplier for walking without a path (Tobler). */
    const val OFF_PATH = 0.6

    /** Spacing of height samples along a route, about the public DEM's resolution. */
    const val STEP_M = 30.0

    data class Estimate(val distance: Double, val ascent: Double, val descent: Double, val hours: Double, val missingHeights: Boolean = false)

    /** Tobler: km/h for a slope of rise over run (negative downhill). */
    fun speedKmh(slope: Double): Double = 6.0 * exp(-3.5 * abs(slope + 0.05))

    /** Points every [step] metres along [line], its vertices kept. */
    fun densify(line: List<LngLat>, step: Double = STEP_M): List<LngLat> {
        if (line.size < 2) return line
        val out = ArrayList<LngLat>()
        for ((a, b) in line.zipWithNext()) {
            val n = max(1, ceil(Geodesy.distance(a, b) / step).toInt())
            for (i in 0 until n) out += LngLat(a.lng + (b.lng - a.lng) * i / n, a.lat + (b.lat - a.lat) * i / n)
        }
        out += line.last()
        return out
    }

    /**
     * Time to walk through [points] with [heights] (one per point; gaps are
     * filled from their neighbours), [factor] times Tobler's path speed.
     * Climb and descent ignore wiggles under [hysteresis] metres, which on a
     * 30 m DEM are mostly noise.
     */
    fun estimate(points: List<LngLat>, heights: List<Double?>, factor: Double = 1.0, hysteresis: Double = 3.0): Estimate {
        require(points.size == heights.size)
        if (points.size < 2) return Estimate(0.0, 0.0, 0.0, 0.0)
        val known = heights.indices.filter { heights[it] != null }
        val missing = known.size < heights.size
        if (known.isEmpty()) {
            val d = points.zipWithNext().sumOf { (a, b) -> Geodesy.distance(a, b) }
            return Estimate(d, 0.0, 0.0, d / 1000 / (speedKmh(0.0) * factor), missingHeights = true)
        }
        val z = DoubleArray(heights.size) { i ->
            heights[i] ?: run {
                val before = known.lastOrNull { it < i }
                val after = known.firstOrNull { it > i }
                when {
                    before == null -> heights[after!!]!!
                    after == null -> heights[before]!!
                    else -> heights[before]!! + (heights[after]!! - heights[before]!!) * (i - before) / (after - before)
                }
            }
        }
        var dist = 0.0
        var hours = 0.0
        for (i in 1 until points.size) {
            val d = Geodesy.distance(points[i - 1], points[i])
            if (d <= 0) continue
            dist += d
            hours += d / 1000 / (speedKmh((z[i] - z[i - 1]) / d) * factor)
        }
        var up = 0.0
        var down = 0.0
        var ref = z[0]
        for (h in z) {
            if (h - ref >= hysteresis) { up += h - ref; ref = h } else if (ref - h >= hysteresis) { down += ref - h; ref = h }
        }
        return Estimate(dist, up, down, hours, missing)
    }

    /** "约 2 小时 15 分", "约 40 分钟". */
    fun format(hours: Double): String {
        val mins = max(1, (hours * 60 / 5).roundToInt() * 5)
        return if (mins < 60) "约 $mins 分钟" else "约 ${mins / 60} 小时" + if (mins % 60 > 0) " ${mins % 60} 分" else ""
    }
}
