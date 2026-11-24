package org.geocrew.core.model

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Map scale for the scale bar: how many metres one density-independent
 * pixel covers in a Web Mercator map, a round bar length, and the
 * approximate representative fraction (e.g. 约 1:2.5万) geologists think in.
 */
object MapScale {
    /** Equatorial circumference of the Web Mercator sphere, m. */
    private const val CIRCUMFERENCE = 40_075_016.686

    /** One dp is 1/160 inch on screen, in metres. */
    private const val DP_METERS = 0.0254 / 160

    /** Ground metres per dp at [lat] for a MapLibre [zoom] (512-dp world at zoom 0). */
    fun metersPerDp(lat: Double, zoom: Double): Double =
        CIRCUMFERENCE * cos(Math.toRadians(lat.coerceIn(-85.0, 85.0))) / (512.0 * 2.0.pow(zoom))

    data class Bar(val meters: Double, val dp: Double)

    /** The longest round length (1, 2 or 5 × 10ⁿ m) that fits in [maxDp]. */
    fun bar(metersPerDp: Double, maxDp: Double): Bar {
        val max = metersPerDp * maxDp
        if (!(max > 0) || !max.isFinite()) return Bar(0.0, 0.0)
        val p = 10.0.pow(floor(log10(max)))
        val m = listOf(5.0, 2.0, 1.0).map { it * p }.first { it <= max * (1 + 1e-9) }
        return Bar(m, m / metersPerDp)
    }

    fun lengthLabel(m: Double): String = when {
        m >= 1000 -> trim(m / 1000) + " km"
        m >= 1 -> trim(m) + " m"
        else -> trim(m * 100) + " cm"
    }

    /** Scale denominator for this screen, rounded to two significant digits. */
    fun denominator(metersPerDp: Double): Long {
        val d = metersPerDp / DP_METERS
        if (!(d >= 1) || !d.isFinite()) return 1
        val p = 10.0.pow(floor(log10(d)) - 1)
        return ((d / p).roundToLong() * p).roundToLong()
    }

    /** "1:5000", "1:2.5万", "1:100万", "1:1.2亿". */
    fun ratioLabel(denominator: Long): String = "1:" + when {
        denominator >= 100_000_000 -> trim(denominator / 1e8) + "亿"
        denominator >= 10_000 -> trim(denominator / 1e4) + "万"
        else -> denominator.toString()
    }

    private fun trim(v: Double): String {
        val r = (v * 100).roundToLong() / 100.0
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }
}
