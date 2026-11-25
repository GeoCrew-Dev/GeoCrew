package org.geocrew.core.gis

import org.geocrew.core.model.Elevation
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Hiking
import org.geocrew.core.model.LngLat
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
 * open path (nearest neighbour, improved by 2-opt); with a DEM, [routeBy]
 * orders them by walking time from [walkHours] instead.
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
        val nodes = (start?.let { listOf(it) } ?: emptyList()) + points
        val offset = if (start != null) 1 else 0
        val path = routeBy(nodes.size) { a, b -> hypot(nodes[a].first - nodes[b].first, nodes[a].second - nodes[b].second) }
        return path.drop(offset).map { it - offset }
    }

    /**
     * Order of nodes 0 until [n] for an open walk that starts at node 0, by
     * [cost] from one node to the next, which need not be symmetric (walking
     * time uphill and downhill differ): nearest neighbour, then 2-opt, which
     * for an asymmetric cost compares the whole reversed stretch.
     */
    fun routeBy(n: Int, cost: (Int, Int) -> Double): List<Int> {
        if (n <= 2) return (0 until n).toList()
        val c = Array(n) { a -> DoubleArray(n) { b -> if (a == b) 0.0 else cost(a, b) } }
        val left = (1 until n).toMutableSet()
        val path = mutableListOf(0)
        while (left.isNotEmpty()) {
            val next = left.minBy { c[path.last()][it] }
            path += next
            left -= next
        }
        var improved = true
        var rounds = 0
        while (improved && rounds++ < 100) {
            improved = false
            for (i in 1 until path.size - 1) {
                for (j in i + 1 until path.size) {
                    var before = c[path[i - 1]][path[i]]
                    var after = c[path[i - 1]][path[j]]
                    for (k in i until j) {
                        before += c[path[k]][path[k + 1]]
                        after += c[path[k + 1]][path[k]]
                    }
                    if (j + 1 < path.size) {
                        before += c[path[j]][path[j + 1]]
                        after += c[path[i]][path[j + 1]]
                    }
                    if (after < before - 1e-9) {
                        path.subList(i, j + 1).reverse()
                        improved = true
                    }
                }
            }
        }
        return path
    }

    /** Total [cost] of visiting [path] in order. */
    fun cost(path: List<Int>, cost: (Int, Int) -> Double): Double = path.zipWithNext().sumOf { (a, b) -> cost(a, b) }

    /**
     * Walking hours from every stop to every other along the straight line
     * between them, over the DEM by Tobler's function times [factor]; [a][b]
     * and [b][a] differ when the ground rises. Each leg is sampled at about
     * [legPoints] heights at most, so a score of stops costs a few thousand
     * lookups. Throws IOException when the heights can't be fetched; null
     * when the DEM has no heights here at all.
     */
    fun walkHours(stops: List<LngLat>, dem: Elevation, factor: Double, legPoints: Int = 40, cancelled: () -> Boolean = { false }): Array<DoubleArray>? {
        val n = stops.size
        val legs = ArrayList<Triple<Int, Int, List<LngLat>>>()
        for (a in 0 until n) for (b in a + 1 until n) {
            val d = Geodesy.distance(stops[a], stops[b])
            legs += Triple(a, b, Hiking.densify(listOf(stops[a], stops[b]), max(Hiking.STEP_M, d / legPoints)))
        }
        val all = legs.flatMap { it.third }
        val heights = ArrayList<Double?>(all.size)
        for (chunk in all.chunked(500)) {
            if (cancelled()) throw ToolCancelled()
            heights += dem.at(chunk)
        }
        if (heights.all { it == null }) return null
        val out = Array(n) { DoubleArray(n) }
        var at = 0
        for ((a, b, pts) in legs) {
            val hs = heights.subList(at, at + pts.size)
            at += pts.size
            out[a][b] = Hiking.estimate(pts, hs, factor).hours
            out[b][a] = Hiking.estimate(pts.reversed(), hs.reversed(), factor).hours
        }
        return out
    }

    fun length(points: List<Pair<Double, Double>>): Double =
        points.zipWithNext().sumOf { (a, b) -> hypot(a.first - b.first, a.second - b.second) }
}
