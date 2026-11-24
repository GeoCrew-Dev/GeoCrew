package org.geocrew.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Averages a run of GPS fixes taken while standing still, the usual way to
 * pin a sample site or survey mark more steadily than one fix allows.
 *
 * Fixes are weighted by 1/accuracy², so a 3 m fix counts four times as much
 * as a 6 m one; fixes without an accuracy get the weight of the worst one
 * seen. Fixes worse than [maxAccuracy] are ignored, which drops the wild
 * first readings a receiver gives while it settles.
 *
 * The result does not claim the √n gain textbooks promise: GPS errors over a
 * minute are strongly correlated, so [Result.accuracy] stays at the median
 * reported accuracy, and [Result.spread] shows how much the fixes wandered.
 */
class PositionAverage(private val maxAccuracy: Double = 30.0) {

    private data class Sample(val p: LngLat, val accuracy: Double?)

    private val samples = mutableListOf<Sample>()

    /** Fixes taken so far, including ignored ones. */
    var seen: Int = 0
        private set

    /** Adds one fix; returns whether it was used. */
    fun add(position: LngLat, accuracy: Double?): Boolean {
        seen++
        if (accuracy != null && (accuracy.isNaN() || accuracy > maxAccuracy)) return false
        samples += Sample(position, accuracy?.coerceAtLeast(0.5))
        return true
    }

    fun clear() {
        samples.clear()
        seen = 0
    }

    data class Result(
        val position: LngLat,
        /** Fixes that went into the average. */
        val used: Int,
        /** Root-mean-square distance of the fixes from the average, metres. */
        val spread: Double,
        /** Median accuracy the receiver reported, metres; null if none did. */
        val accuracy: Double?,
    )

    /** The average so far, or null before the first usable fix. */
    fun result(): Result? {
        if (samples.isEmpty()) return null
        val worst = samples.mapNotNull { it.accuracy }.maxOrNull() ?: 1.0
        val weights = samples.map { s -> (s.accuracy ?: worst).let { 1.0 / (it * it) } }
        val total = weights.sum()
        // Average in a local plane around the first fix; a minute of fixes
        // spans metres, where the plane is exact enough.
        val o = samples.first().p
        val mx = Geodesy.EARTH_RADIUS * PI / 180 * cos(o.lat * PI / 180)
        val my = Geodesy.EARTH_RADIUS * PI / 180
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var zw = 0.0
        samples.forEachIndexed { i, s ->
            x += weights[i] * (s.p.lng - o.lng) * mx
            y += weights[i] * (s.p.lat - o.lat) * my
            s.p.alt?.let { z += weights[i] * it; zw += weights[i] }
        }
        x /= total
        y /= total
        var sq = 0.0
        samples.forEach { s ->
            val dx = (s.p.lng - o.lng) * mx - x
            val dy = (s.p.lat - o.lat) * my - y
            sq += dx * dx + dy * dy
        }
        val acc = samples.mapNotNull { it.accuracy }.sorted().let { a ->
            if (a.isEmpty()) null else if (a.size % 2 == 1) a[a.size / 2] else (a[a.size / 2 - 1] + a[a.size / 2]) / 2
        }
        return Result(
            position = LngLat(o.lng + x / mx, o.lat + y / my, if (zw > 0) z / zw else null),
            used = samples.size,
            spread = sqrt(sq / samples.size),
            accuracy = acc,
        )
    }
}
