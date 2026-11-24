package org.geocrew.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One leg of a measured traverse across a bed: the tape or rangefinder
 * reading [slopeDistance] (m) along [azimuth] (degrees from north), with
 * [slopeAngle] positive uphill and negative downhill.
 */
data class TraverseLeg(val azimuth: Double, val slopeAngle: Double, val slopeDistance: Double) {
    /** The leg as a vector (east, north, up) in metres. */
    fun vector(): DoubleArray {
        val b = slopeAngle * PI / 180
        val a = azimuth * PI / 180
        val h = slopeDistance * cos(b)
        return doubleArrayOf(h * sin(a), h * cos(a), slopeDistance * sin(b))
    }

    companion object {
        /**
         * The leg from [a] to [b] (for example the two contact points), using
         * their elevations when both have one and treating the ground as level
         * otherwise.
         */
        fun between(a: LngLat, b: LngLat): TraverseLeg {
            val h = Geodesy.distance(a, b)
            val dz = if (a.alt != null && b.alt != null) b.alt - a.alt else 0.0
            val slope = if (h == 0.0 && dz == 0.0) 0.0 else kotlin.math.atan2(dz, h) * 180 / PI
            return TraverseLeg(if (h > 0) Geodesy.bearing(a, b) else 0.0, slope, kotlin.math.hypot(h, dz))
        }
    }
}

/**
 * Bed thickness from a traverse between its two contacts. The thickness is
 * the traverse's component along the bed's normal, which covers every case
 * the textbook formulas split into (slope with or against the dip, traverse
 * oblique to the strike, several legs) with one dot product.
 */
data class ThicknessResult(
    /** Perpendicular (true) thickness, m. */
    val trueThickness: Double,
    /** Thickness measured straight down (in a vertical borehole); null for vertical beds. */
    val verticalThickness: Double?,
    /** Outcrop width on flat ground across the strike; null for horizontal beds. */
    val horizontalWidth: Double?,
    /** Each leg's signed share of the thickness, m. */
    val perLeg: List<Double>,
    /**
     * True when the traverse walks towards the dip side, i.e. into the beds
     * above in an upright sequence.
     */
    val towardsDip: Boolean,
    /** Legs pulling in opposite directions; the traverse crossed back over part of the bed. */
    val mixedDirections: Boolean,
) {
    companion object {
        fun of(attitude: Attitude, legs: List<TraverseLeg>): ThicknessResult {
            val n = attitude.normal()
            val parts = legs.map { l -> l.vector().let { v -> v[0] * n[0] + v[1] * n[1] + v[2] * n[2] } }
            val total = parts.sum()
            val t = abs(total)
            val d = attitude.dip * PI / 180
            val eps = 1e-9
            return ThicknessResult(
                trueThickness = t,
                verticalThickness = if (cos(d) > eps) t / cos(d) else null,
                horizontalWidth = if (sin(d) > eps) t / sin(d) else null,
                perLeg = parts,
                towardsDip = total > 0,
                mixedDirections = parts.any { it > 0.01 } && parts.any { it < -0.01 },
            )
        }
    }
}
