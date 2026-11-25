package org.geocrew.core.model

import kotlinx.serialization.json.JsonObject
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI

/**
 * Orientation of a planar structure (bedding, joint, fault...).
 * Angles in degrees; [strike] follows the right-hand rule, so the plane dips
 * to the right of the strike direction: dipDirection = strike + 90.
 */
data class Attitude(val dipDirection: Double, val dip: Double) {
    init {
        require(dip in 0.0..90.0) { "dip must be within 0..90: $dip" }
    }

    val strike: Double get() = Geodesy.normalizeDegrees(dipDirection - 90)

    /** Conventional field notation, e.g. "125°∠42°" (dip direction ∠ dip). */
    fun format(): String = "%03.0f°∠%02.0f°".fmt(dipDirection, dip)

    /** Upward unit normal of the plane as (east, north, up). */
    fun normal(): DoubleArray {
        val d = dip * PI / 180
        val a = dipDirection * PI / 180
        return doubleArrayOf(kotlin.math.sin(d) * kotlin.math.sin(a), kotlin.math.sin(d) * kotlin.math.cos(a), kotlin.math.cos(d))
    }

    fun writeTo(b: PropertiesBuilder) {
        b[Props.STRIKE] = round1(strike)
        b[Props.DIP_DIRECTION] = round1(dipDirection)
        b[Props.DIP] = round1(dip)
    }

    companion object {
        fun of(dipDirection: Double, dip: Double) = Attitude(Geodesy.normalizeDegrees(dipDirection), dip.coerceIn(0.0, 90.0))

        fun fromStrike(strike: Double, dip: Double) = of(strike + 90, dip)

        fun fromProperties(p: JsonObject): Attitude? {
            val dip = p.double(Props.DIP) ?: return null
            val dd = p.double(Props.DIP_DIRECTION) ?: p.double(Props.STRIKE)?.plus(90) ?: return null
            return runCatching { of(dd, dip) }.getOrNull()
        }

        /**
         * Attitude of the plane the phone's back lies on, from the device
         * z axis expressed in world coordinates (east, north, up) — the third
         * column of Android's rotation matrix. [declination] (degrees, east
         * positive) converts magnetic to true north.
         */
        fun fromDeviceNormal(east: Double, north: Double, up: Double, declination: Double = 0.0): Attitude {
            // Use the upward-pointing normal whichever way the phone faces.
            val s = if (up < 0) -1.0 else 1.0
            val e = east * s
            val n = north * s
            val u = up * s
            val len = kotlin.math.sqrt(e * e + n * n + u * u)
            require(len > 0) { "zero vector" }
            val dip = acos((u / len).coerceIn(-1.0, 1.0)) * 180 / PI
            // The upward normal leans toward the dip direction.
            val dd = if (hypot(e, n) < 1e-9) 0.0 else atan2(e, n) * 180 / PI
            return of(dd + declination, dip)
        }

        /**
         * Mean orientation of several readings, averaging unit normals so that
         * readings around north (359° and 1°) do not cancel out. Null if empty.
         */
        fun mean(readings: List<Attitude>): Attitude? {
            if (readings.isEmpty()) return null
            var e = 0.0
            var n = 0.0
            var u = 0.0
            readings.forEach { r -> r.normal().let { e += it[0]; n += it[1]; u += it[2] } }
            return fromDeviceNormal(e, n, u)
        }

        /** Largest angle in degrees between any reading and [mean]; a steadiness measure. */
        fun spread(readings: List<Attitude>, mean: Attitude): Double {
            val m = mean.normal()
            return readings.maxOfOrNull { r ->
                val v = r.normal()
                acos((v[0] * m[0] + v[1] * m[1] + v[2] * m[2]).coerceIn(-1.0, 1.0)) * 180 / PI
            } ?: 0.0
        }

        private fun round1(v: Double) = Math.round(v * 10) / 10.0
    }
}
