package org.geocrew.core.gis

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where to sample next and in what order (补点规划).
 *
 * The kriging variance depends only on where the samples are, not on their
 * values, so the effect of a planned sample is known before it is taken.
 * [pick] uses that: after each pick the point joins the samples and the
 * variance is recomputed around it, so the next pick goes where the
 * uncertainty is still largest rather than right next to the first. With a
 * [travel] weight above zero each candidate's standard deviation is divided
 * by (1 + travel · distance from the previous stop / [scale]), trading some
 * information for a shorter walk. [route] then orders the picks into a short
 * open path (nearest neighbour, improved by 2-opt).
 */
internal object SamplingPlan {
    data class Pick(val x: Double, val y: Double, val sd: Double)

    fun pick(
        xs: DoubleArray,
        ys: DoubleArray,
        variogram: Variogram,
        cells: List<Pair<Double, Double>>,
        wanted: Int,
        /** Kriging variance of each cell from the samples alone, when already computed. */
        initial: DoubleArray? = null,
        start: Pair<Double, Double>? = null,
        travel: Double = 0.0,
        scale: Double = 1.0,
        cancelled: () -> Boolean = { false },
    ): List<Pick> {
        if (wanted <= 0 || cells.isEmpty()) return emptyList()
        val px = xs.toMutableList()
        val py = ys.toMutableList()
        fun kriging() = OrdinaryKriging(px.toDoubleArray(), py.toDoubleArray(), DoubleArray(px.size), variogram)
        var ok = kriging()
        val variance = initial?.copyOf() ?: DoubleArray(cells.size) { i -> ok.estimate(cells[i].first, cells[i].second).variance }
        val taken = BooleanArray(cells.size)
        // A new sample only changes the variance within about the variogram's range of it.
        val reach = max(variogram.range, 1e-9) * 2
        val picks = mutableListOf<Pick>()
        var from = start
        while (picks.size < wanted) {
            if (cancelled()) throw ToolCancelled()
            var best = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in cells.indices) {
                val v = variance[i]
                if (taken[i] || !v.isFinite()) continue
                val sd = sqrt(v)
                val d = from?.let { hypot(cells[i].first - it.first, cells[i].second - it.second) } ?: 0.0
                val score = if (travel > 0) sd / (1 + travel * d / scale) else sd
                if (score > bestScore) { bestScore = score; best = i }
            }
            if (best < 0 || variance[best] <= 1e-12) break
            val (bx, by) = cells[best]
            picks += Pick(bx, by, sqrt(variance[best]))
            taken[best] = true
            from = bx to by
            px += bx
            py += by
            ok = kriging()
            for (i in cells.indices) {
                if (taken[i] || hypot(cells[i].first - bx, cells[i].second - by) > reach) continue
                variance[i] = ok.estimate(cells[i].first, cells[i].second).variance
            }
        }
        return picks
    }

    /** Visiting order of [points] as indices, starting from [start] when given, else from the first point. */
    fun route(points: List<Pair<Double, Double>>, start: Pair<Double, Double>? = null): List<Int> {
        if (points.size <= 1) return points.indices.toList()
        // Work on a path whose node 0 is the fixed start.
        val nodes = (start?.let { listOf(it) } ?: emptyList()) + points
        val offset = if (start != null) 1 else 0
        fun d(a: Int, b: Int) = hypot(nodes[a].first - nodes[b].first, nodes[a].second - nodes[b].second)
        val left = (1 until nodes.size).toMutableSet()
        val path = mutableListOf(0)
        while (left.isNotEmpty()) {
            val next = left.minBy { d(path.last(), it) }
            path += next
            left -= next
        }
        // 2-opt on an open path with a fixed first node: reverse path[i..j] when it shortens the walk.
        var improved = true
        var rounds = 0
        while (improved && rounds++ < 100) {
            improved = false
            for (i in 1 until path.size - 1) {
                for (j in i + 1 until path.size) {
                    val before = d(path[i - 1], path[i]) + if (j + 1 < path.size) d(path[j], path[j + 1]) else 0.0
                    val after = d(path[i - 1], path[j]) + if (j + 1 < path.size) d(path[i], path[j + 1]) else 0.0
                    if (after < before - 1e-9) {
                        path.subList(i, j + 1).reverse()
                        improved = true
                    }
                }
            }
        }
        return path.drop(offset).map { it - offset }
    }

    fun length(points: List<Pair<Double, Double>>): Double =
        points.zipWithNext().sumOf { (a, b) -> hypot(a.first - b.first, a.second - b.second) }
}
