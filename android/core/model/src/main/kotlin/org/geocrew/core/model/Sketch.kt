package org.geocrew.core.model

import kotlin.math.hypot

/**
 * One pen stroke of a field sketch. Points are in fractions of the
 * drawing's width and height (0–1), and [width] is a fraction of its
 * width, so the same strokes render at any resolution: on screen while
 * drawing and full size when the sketch is saved as an image.
 */
data class SketchStroke(val points: List<Pair<Float, Float>>, val color: Long, val width: Float)

object Sketch {
    /** Pens offered while sketching: name and ARGB colour. */
    val COLORS = listOf("黑" to 0xFF212121, "红" to 0xFFD32F2F, "蓝" to 0xFF1565C0, "绿" to 0xFF2E7D32, "褐" to 0xFF6D4C41)

    /** Pen widths, in fractions of the drawing's width. */
    val WIDTHS = listOf("细" to 0.003f, "中" to 0.006f, "粗" to 0.012f)

    /** Size of a blank sketch when saved, pixels; 4:3 like most phone cameras. */
    const val BLANK_WIDTH = 1600
    const val BLANK_HEIGHT = 1200

    /**
     * Drops points that stray less than [tolerance] (same units as the
     * points) from the line through their neighbours (Douglas–Peucker), so a
     * stroke keeps its shape with a fraction of the points a finger reports.
     */
    fun simplify(points: List<Pair<Float, Float>>, tolerance: Float = 0.0015f): List<Pair<Float, Float>> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntRange>()
        stack.addLast(0..points.size - 1)
        while (stack.isNotEmpty()) {
            val r = stack.removeLast()
            if (r.last - r.first < 2) continue
            val a = points[r.first]; val b = points[r.last]
            var worst = -1f; var at = -1
            for (i in r.first + 1 until r.last) {
                val d = distance(points[i], a, b)
                if (d > worst) { worst = d; at = i }
            }
            if (worst > tolerance) {
                keep[at] = true
                stack.addLast(r.first..at)
                stack.addLast(at..r.last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distance(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = b.first - a.first; val dy = b.second - a.second
        val len = hypot(dx, dy)
        if (len == 0f) return hypot(p.first - a.first, p.second - a.second)
        return kotlin.math.abs(dy * p.first - dx * p.second + b.first * a.second - b.second * a.first) / len
    }
}
