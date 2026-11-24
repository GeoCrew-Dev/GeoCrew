package org.geocrew.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fixing a point by compass bearings, the way it was done before GPS and
 * still is where GPS is poor or the target can't be reached.
 *
 * Forward intersection (前方交会): from two or more known stations, sight the
 * same target; it lies where the sight lines cross. Resection (后方交会):
 * sight two or more known landmarks from where you stand; you are where the
 * back bearings cross. With more than two lines the crossing is the least
 * squares point, and each line's miss tells how well the readings agree.
 */
object BearingFix {
    /** A sight line through [from] towards true azimuth [azimuth] (degrees). */
    data class Ray(val from: LngLat, val azimuth: Double)

    data class Result(
        val position: LngLat,
        /** How far the point lies off each ray, metres, in order. */
        val misses: List<Double>,
        /** The smallest angle between two rays, 0..90°; below about 30° the fix is weak. */
        val minAngle: Double,
        /** True when the point is behind the start of a ray: a bearing read the wrong way round, usually. */
        val behind: Boolean,
        /** Distance from each ray's start to the point, metres. */
        val ranges: List<Double>,
    ) {
        val worstMiss get() = misses.maxOrNull() ?: 0.0
    }

    /** Where [rays] cross; null for fewer than two or for parallel lines. */
    fun intersect(rays: List<Ray>): Result? {
        if (rays.size < 2) return null
        val lat0 = rays.sumOf { it.from.lat } / rays.size
        val lng0 = rays.sumOf { it.from.lng } / rays.size
        val kx = Geodesy.EARTH_RADIUS * PI / 180 * cos(lat0 * PI / 180)
        val ky = Geodesy.EARTH_RADIUS * PI / 180
        fun x(p: LngLat) = (p.lng - lng0) * kx
        fun y(p: LngLat) = (p.lat - lat0) * ky

        // Each ray is the line n·p = n·p0 with n its unit normal; minimise the summed squared distances.
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0
        val lines = rays.map { r ->
            val t = r.azimuth * PI / 180
            val dx = sin(t)
            val dy = cos(t)
            val nx = dy
            val ny = -dx
            val c = nx * x(r.from) + ny * y(r.from)
            a11 += nx * nx; a12 += nx * ny; a22 += ny * ny
            b1 += nx * c; b2 += ny * c
            doubleArrayOf(x(r.from), y(r.from), dx, dy)
        }
        var minAngle = 90.0
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val dot = abs(lines[i][2] * lines[j][2] + lines[i][3] * lines[j][3]).coerceAtMost(1.0)
            minAngle = minOf(minAngle, acos(dot) * 180 / PI)
        }
        val det = a11 * a22 - a12 * a12
        if (minAngle < MIN_ANGLE || abs(det) < 1e-12) return null
        val px = (a22 * b1 - a12 * b2) / det
        val py = (a11 * b2 - a12 * b1) / det
        val misses = lines.map { l -> abs((px - l[0]) * l[3] - (py - l[1]) * l[2]) }
        val along = lines.map { l -> (px - l[0]) * l[2] + (py - l[1]) * l[3] }
        val ranges = lines.map { l -> sqrt((px - l[0]) * (px - l[0]) + (py - l[1]) * (py - l[1])) }
        val pos = LngLat(((lng0 + px / kx + 540) % 360) - 180, (lat0 + py / ky).coerceIn(-90.0, 90.0))
        return Result(pos, misses, minAngle, along.any { it < 0 }, ranges)
    }

    /** Resection: where you stand, from the true bearings you read to known [landmarks]. */
    fun resection(landmarks: List<Pair<LngLat, Double>>): Result? =
        intersect(landmarks.map { (p, az) -> Ray(p, Geodesy.normalizeDegrees(az + 180)) })

    /** Lines closer to parallel than this (degrees) don't give a point at all. */
    const val MIN_ANGLE = 2.0

    /** Below this angle (degrees) between sight lines the fix is weak; 30–150° is the usual advice. */
    const val WEAK_ANGLE = 30.0
}
