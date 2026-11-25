package org.geocrew.core.model

import kotlinx.serialization.json.JsonObject
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orientation of a linear structure (线理, 擦痕, 褶皱枢纽, 交面线…) as
 * [trend] (倾伏向: azimuth of its downward direction, 0–360) and [plunge]
 * (倾伏角: angle below the horizontal, 0–90), protocol v0 §6.12. A
 * horizontal line has two equal trends; the one given is kept.
 */
data class Plunge(val trend: Double, val plunge: Double) {
    init {
        require(plunge in 0.0..90.0) { "plunge must be within 0..90: $plunge" }
    }

    /** Field notation, e.g. "235°∠30°" (trend ∠ plunge). */
    fun format(): String = "%03.0f°∠%02.0f°".fmt(trend, plunge)

    /** Downward unit vector as (east, north, up). */
    fun vector(): DoubleArray {
        val p = plunge * PI / 180
        val t = trend * PI / 180
        return doubleArrayOf(cos(p) * sin(t), cos(p) * cos(t), -sin(p))
    }

    /**
     * Angle (°) between this line and [plane]: 0 when the line lies in the
     * plane, as a striation on its fault should.
     */
    fun offPlane(plane: Attitude): Double {
        val v = vector()
        val n = plane.normal()
        return asin(kotlin.math.abs(v[0] * n[0] + v[1] * n[1] + v[2] * n[2]).coerceIn(0.0, 1.0)) * 180 / PI
    }

    /**
     * Rake (侧伏角): angle in [plane] from its strike direction (right-hand
     * rule) to this line, 0–180, after projecting the line onto the plane.
     * Null when the line is nearly perpendicular to the plane.
     */
    fun rakeOn(plane: Attitude): Double? {
        val v = vector()
        val n = plane.normal()
        val d = v[0] * n[0] + v[1] * n[1] + v[2] * n[2]
        val p = doubleArrayOf(v[0] - d * n[0], v[1] - d * n[1], v[2] - d * n[2])
        val len = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
        if (len < 0.1) return null
        val s = plane.strike * PI / 180
        val strike = doubleArrayOf(sin(s), cos(s), 0.0)
        val c = ((p[0] * strike[0] + p[1] * strike[1]) / len).coerceIn(-1.0, 1.0)
        return acos(c) * 180 / PI
    }

    fun writeTo(b: PropertiesBuilder) {
        b[Props.TREND] = round1(trend)
        b[Props.PLUNGE] = round1(plunge)
    }

    companion object {
        fun of(trend: Double, plunge: Double): Plunge {
            // A line given upward is the same line pointing the other way, downward.
            return if (plunge < 0) Plunge(Geodesy.normalizeDegrees(trend + 180), (-plunge).coerceAtMost(90.0))
            else Plunge(Geodesy.normalizeDegrees(trend), plunge.coerceAtMost(90.0))
        }

        fun fromProperties(p: JsonObject): Plunge? {
            val t = p.double(Props.TREND) ?: return null
            val pl = p.double(Props.PLUNGE) ?: return null
            if (!t.isFinite() || !pl.isFinite() || pl !in 0.0..90.0) return null
            return of(t, pl)
        }

        /**
         * The line along which a device axis points, from that axis in world
         * coordinates (east, north, up) — for the phone's long edge, the
         * second column of Android's rotation matrix. The downward end is
         * taken, so it does not matter which way the top of the phone points.
         */
        fun fromVector(east: Double, north: Double, up: Double, declination: Double = 0.0): Plunge {
            val len = sqrt(east * east + north * north + up * up)
            require(len > 0) { "zero vector" }
            val s = if (up > 0) -1.0 else 1.0
            val e = east * s / len
            val n = north * s / len
            val u = up * s / len
            val plunge = asin((-u).coerceIn(-1.0, 1.0)) * 180 / PI
            val trend = if (hypot(e, n) < 1e-9) 0.0 else atan2(e, n) * 180 / PI
            return of(trend + declination, plunge)
        }

        /** Mean of several readings of the same line (axial: opposite readings agree). Null if empty. */
        fun mean(readings: List<Plunge>): Plunge? {
            if (readings.isEmpty()) return null
            val ref = readings.first().vector()
            var e = 0.0
            var n = 0.0
            var u = 0.0
            for (r in readings) {
                val v = r.vector()
                val sign = if (v[0] * ref[0] + v[1] * ref[1] + v[2] * ref[2] < 0) -1.0 else 1.0
                e += v[0] * sign; n += v[1] * sign; u += v[2] * sign
            }
            if (sqrt(e * e + n * n + u * u) < 1e-9) return readings.first()
            return fromVector(e, n, u)
        }

        /** Largest angle (°) of a reading from [mean]; small means the phone was held still. */
        fun spread(readings: List<Plunge>, mean: Plunge): Double {
            val m = mean.vector()
            return readings.maxOfOrNull { r ->
                val v = r.vector()
                acos(kotlin.math.abs(v[0] * m[0] + v[1] * m[1] + v[2] * m[2]).coerceIn(0.0, 1.0)) * 180 / PI
            } ?: 0.0
        }

        private fun round1(v: Double) = Math.round(v * 10) / 10.0
    }
}
