package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.buildProperties
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Contour lines (等值线) through a regular grid of estimates, by marching
 * squares: values sit on grid nodes ([values] by row then column, NaN for no
 * data), each square between four nodes is crossed by the level along
 * linearly interpolated edges, and the pieces are joined into polylines.
 * Ambiguous saddle squares are settled by the mean of their four corners.
 * Coordinates come back in grid units: x is the column, y the row.
 */
internal object Contours {
    data class Line(val level: Double, val points: List<Pair<Double, Double>>)

    /** At most this many levels, so a tiny interval can't flood the map. */
    const val MAX_LEVELS = 40

    /** Levels strictly inside (lo, hi): multiples of [interval], or about eight "nice" steps (1, 2 or 5 × 10ⁿ). */
    fun levels(lo: Double, hi: Double, interval: Double? = null): List<Double> {
        if (!(hi > lo) || !lo.isFinite() || !hi.isFinite()) return emptyList()
        val step = interval?.takeIf { it > 0 } ?: nice((hi - lo) / 8)
        if ((hi - lo) / step > MAX_LEVELS) return emptyList()
        val out = ArrayList<Double>()
        var k = ceil(lo / step)
        while (k * step < hi) {
            val v = k * step
            if (v > lo) out += clean(v, step)
            k += 1
        }
        return out
    }

    /** The step itself, for labels: as many decimals as it needs. */
    fun label(v: Double): String = v.toBigDecimal().stripTrailingZeros().toPlainString()

    private fun nice(x: Double): Double {
        val e = 10.0.pow(floor(log10(x)))
        val f = x / e
        return e * when {
            f < 1.5 -> 1.0
            f < 3.5 -> 2.0
            f < 7.5 -> 5.0
            else -> 10.0
        }
    }

    // 0.30000000000000004 → 0.3, keeping the step's precision.
    private fun clean(v: Double, step: Double): Double {
        val digits = (-floor(log10(step))).toInt().coerceIn(0, 12) + 2
        return v.toBigDecimal().setScale(digits, java.math.RoundingMode.HALF_UP).toDouble()
    }

    /**
     * Contour lines of a tool's grid as map features. Node (row, col) is the
     * centre of grid cell (row, col): x = minX + (col + ½)·size, likewise y.
     * [interval] null picks the levels, 0 or less draws none. Returns the
     * features and the interval used, or null when nothing was drawn.
     */
    fun features(
        values: Array<DoubleArray>,
        plane: LocalPlane,
        minX: Double,
        minY: Double,
        size: Double,
        tool: String,
        key: String,
        interval: Double?,
    ): Pair<List<OutputFeature>, List<Double>>? {
        if (interval != null && interval <= 0) return null
        val all = values.flatMap { r -> r.filter(Double::isFinite) }
        if (all.isEmpty()) return null
        val levels = levels(all.min(), all.max(), interval)
        if (levels.isEmpty()) return null
        val out = ArrayList<OutputFeature>()
        for (level in levels) for (line in trace(values, level)) {
            out += OutputFeature(
                FeatureKind.LINE,
                Geometry.LineString(line.points.map { (x, y) -> plane.toLngLat(minX + (x + 0.5) * size, minY + (y + 0.5) * size) }),
                buildProperties {
                    this[OutputProps.TOOL] = tool
                    this[OutputProps.VALUE] = level
                    this[Props.TITLE] = "$key = ${label(level)}"
                    style(stroke = "#37474F", strokeWidth = 1.5)
                },
            )
        }
        return if (out.isEmpty()) null else out to levels
    }

    fun trace(values: Array<DoubleArray>, level: Double): List<Line> {
        val rows = values.size
        val cols = values.firstOrNull()?.size ?: 0
        if (rows < 2 || cols < 2) return emptyList()
        // Edge ids: horizontal edge from node (r, c) to (r, c+1) is 2·(r·cols + c), vertical to (r+1, c) is that + 1.
        fun h(r: Int, c: Int) = 2L * (r.toLong() * cols + c)
        fun v(r: Int, c: Int) = 2L * (r.toLong() * cols + c) + 1
        val at = HashMap<Long, Pair<Double, Double>>()
        val segments = ArrayList<LongArray>()
        fun point(edge: Long, r: Int, c: Int, horizontal: Boolean) {
            if (edge in at) return
            val a = values[r][c]
            val b = if (horizontal) values[r][c + 1] else values[r + 1][c]
            val t = if (b == a) 0.5 else ((level - a) / (b - a)).coerceIn(0.0, 1.0)
            at[edge] = if (horizontal) (c + t) to r.toDouble() else c.toDouble() to (r + t)
        }
        for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
            val a = values[r][c]; val b = values[r][c + 1]; val cc = values[r + 1][c + 1]; val d = values[r + 1][c]
            if (a.isNaN() || b.isNaN() || cc.isNaN() || d.isNaN()) continue
            val idx = (if (a >= level) 8 else 0) or (if (b >= level) 4 else 0) or (if (cc >= level) 2 else 0) or (if (d >= level) 1 else 0)
            if (idx == 0 || idx == 15) continue
            val top = h(r, c); val bottom = h(r + 1, c); val left = v(r, c); val right = v(r, c + 1)
            val centreAbove = (a + b + cc + d) / 4 >= level
            val pairs: List<Pair<Long, Long>> = when (idx) {
                1, 14 -> listOf(left to bottom)
                2, 13 -> listOf(bottom to right)
                3, 12 -> listOf(left to right)
                4, 11 -> listOf(top to right)
                6, 9 -> listOf(top to bottom)
                7, 8 -> listOf(left to top)
                5 -> if (centreAbove) listOf(left to top, bottom to right) else listOf(top to right, left to bottom)
                10 -> if (centreAbove) listOf(top to right, left to bottom) else listOf(left to top, bottom to right)
                else -> emptyList()
            }
            for ((p, q) in pairs) {
                for (e in longArrayOf(p, q)) {
                    val horizontal = e % 2 == 0L
                    val node = e / 2
                    point(e, (node / cols).toInt(), (node % cols).toInt(), horizontal)
                }
                segments += longArrayOf(p, q)
            }
        }
        // Join segments sharing an edge point into polylines.
        val byEdge = HashMap<Long, MutableList<Int>>()
        segments.forEachIndexed { i, s -> for (e in s) byEdge.getOrPut(e) { ArrayList(2) } += i }
        val used = BooleanArray(segments.size)
        val lines = ArrayList<Line>()
        for (start in segments.indices) {
            if (used[start]) continue
            used[start] = true
            val chain = ArrayDeque<Long>()
            chain.addLast(segments[start][0]); chain.addLast(segments[start][1])
            fun grow(atEnd: Boolean) {
                while (true) {
                    val tip = if (atEnd) chain.last() else chain.first()
                    val next = byEdge[tip]?.firstOrNull { !used[it] } ?: return
                    used[next] = true
                    val s = segments[next]
                    val other = if (s[0] == tip) s[1] else s[0]
                    if (atEnd) chain.addLast(other) else chain.addFirst(other)
                }
            }
            grow(true); grow(false)
            val pts = chain.map { at.getValue(it) }
            if (pts.size >= 2 && length(pts) > 1e-9) lines += Line(level, pts)
        }
        return lines
    }

    private fun length(pts: List<Pair<Double, Double>>) = pts.zipWithNext().sumOf { (p, q) -> abs(p.first - q.first) + abs(p.second - q.second) }
}
