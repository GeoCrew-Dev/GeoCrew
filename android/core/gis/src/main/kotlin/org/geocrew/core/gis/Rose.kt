package org.geocrew.core.gis

import org.geocrew.core.model.Geodesy
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Mean of axial directions such as strikes, where 10° and 190° are the same line. */
data class AxialMean(
    /** Mean direction in [0, 180). */
    val direction: Double,
    /** Mean resultant length of the doubled angles, 0 (no preferred direction) to 1 (all parallel). */
    val resultant: Double,
    val count: Int,
)

/** Rose (circular histogram) statistics for strikes, joint sets and lineation trends. */
object Rose {
    /**
     * Counts axial directions in sectors of [binWidth] degrees over 0–180;
     * sector i covers [i·w, (i+1)·w) and, mirrored, the same plus 180°.
     */
    fun axialBins(directions: List<Double>, binWidth: Int = 10): IntArray {
        require(binWidth in 1..90 && 180 % binWidth == 0) { "binWidth must divide 180" }
        val bins = IntArray(180 / binWidth)
        for (d in directions) {
            val a = Geodesy.normalizeDegrees(d) % 180.0
            bins[(a / binWidth).toInt().coerceAtMost(bins.size - 1)]++
        }
        return bins
    }

    /** Doubled-angle mean of axial directions, or null without data. */
    fun axialMean(directions: List<Double>): AxialMean? {
        if (directions.isEmpty()) return null
        var c = 0.0
        var s = 0.0
        for (d in directions) {
            val t = Math.toRadians(2 * d)
            c += cos(t)
            s += sin(t)
        }
        val r = hypot(c, s) / directions.size
        val mean = Geodesy.normalizeDegrees(Math.toDegrees(atan2(s, c)) / 2) % 180.0
        return AxialMean(mean, r, directions.size)
    }
}
