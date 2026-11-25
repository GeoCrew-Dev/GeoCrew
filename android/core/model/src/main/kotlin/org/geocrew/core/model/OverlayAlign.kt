package org.geocrew.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Lines a picture up with the map from control points: each pair says
 * "this spot of the picture, now drawn at [Pair.from], belongs at [Pair.to]".
 * One pair moves the picture, two also turn and scale it, three or more fit
 * a general affine (least squares, so it may also shear a skewed scan). The
 * fit is done in Web Mercator, the plane the picture is drawn in, so what the
 * user sees lines up where they tapped.
 */
object OverlayAlign {
    data class Pair(val from: LngLat, val to: LngLat)

    /** The picture's new corners, or null when the points can't define a fit (e.g. two at the same spot). */
    fun corners(corners: List<LngLat>, pairs: List<Pair>): List<LngLat>? {
        if (pairs.isEmpty()) return corners
        val src = pairs.map { m(it.from) }
        val dst = pairs.map { m(it.to) }
        val t = when (pairs.size) {
            1 -> Transform(1.0, 0.0, dst[0].x - src[0].x, 0.0, 1.0, dst[0].y - src[0].y)
            2 -> similarity(src, dst)
            else -> affine(src, dst)
        } ?: return null
        return corners.map { c -> m(c).let { t(it) }.let(::ll) }
    }

    /** How far, in metres on the ground, each pair ends up from where it was meant to go after the fit. */
    fun residuals(pairs: List<Pair>): List<Double> {
        if (pairs.size < 3) return pairs.map { 0.0 }
        val src = pairs.map { m(it.from) }
        val dst = pairs.map { m(it.to) }
        val t = affine(src, dst) ?: return pairs.map { Double.NaN }
        return pairs.indices.map { i -> Geodesy.distance(ll(t(src[i])), pairs[i].to) }
    }

    private data class P(val x: Double, val y: Double)

    /** x' = a·x + b·y + c, y' = d·x + e·y + f. */
    private data class Transform(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        operator fun invoke(p: P) = P(a * p.x + b * p.y + c, d * p.x + e * p.y + f)
    }

    private fun similarity(s: List<P>, t: List<P>): Transform? {
        val sx = s[1].x - s[0].x; val sy = s[1].y - s[0].y
        val tx = t[1].x - t[0].x; val ty = t[1].y - t[0].y
        val n = sx * sx + sy * sy
        if (n < 1e-18) return null
        // Complex division (t1 - t0) / (s1 - s0) gives scale and rotation.
        val a = (tx * sx + ty * sy) / n
        val b = (ty * sx - tx * sy) / n
        return Transform(a, -b, t[0].x - (a * s[0].x - b * s[0].y), b, a, t[0].y - (b * s[0].x + a * s[0].y))
    }

    private fun affine(s: List<P>, t: List<P>): Transform? {
        // Normal equations for [x y 1]·[a b c]ᵀ = x', shared by both rows.
        val cx = s.sumOf { it.x } / s.size; val cy = s.sumOf { it.y } / s.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        var xu = 0.0; var yu = 0.0; var xv = 0.0; var yv = 0.0
        val ux = t.sumOf { it.x } / t.size; val vy = t.sumOf { it.y } / t.size
        for (i in s.indices) {
            val x = s[i].x - cx; val y = s[i].y - cy
            val u = t[i].x - ux; val v = t[i].y - vy
            sxx += x * x; sxy += x * y; syy += y * y
            xu += x * u; yu += y * u; xv += x * v; yv += y * v
        }
        val det = sxx * syy - sxy * sxy
        // Points (nearly) on one line leave the fit undetermined across it.
        if (abs(det) < 1e-12 * (sxx + syy) * (sxx + syy) || sxx + syy < 1e-18) return null
        val a = (xu * syy - yu * sxy) / det
        val b = (yu * sxx - xu * sxy) / det
        val d = (xv * syy - yv * sxy) / det
        val e = (yv * sxx - xv * sxy) / det
        return Transform(a, b, ux - a * cx - b * cy, d, e, vy - d * cx - e * cy)
    }

    private fun m(p: LngLat) = P(p.lng * PI / 180, ln(tan(PI / 4 + p.lat.coerceIn(-85.0, 85.0) * PI / 360)))
    private fun ll(p: P) = LngLat(p.x * 180 / PI, (2 * atan(exp(p.y)) - PI / 2) * 180 / PI)
}
