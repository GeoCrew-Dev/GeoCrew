package org.geocrew.core.model

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The kilometre grid (公里网) of a Gauss-Krüger zone over a map view, the
 * way it is printed on Chinese topographic maps: lines of equal northing
 * (X) and easting (Y), labelled in kilometres, with the zone number in
 * front of the easting. Lines are curves in longitude and latitude, so each
 * is sampled densely enough to follow the projection.
 */
object PlaneGrid {
    /** One grid line. [anchor] is where its label goes, just inside the view's west or south edge. */
    data class Line(val positions: List<LngLat>, val label: String, val northing: Boolean, val anchor: LngLat?)

    data class Grid(val zone: Int, val width: Int, val spacing: Int, val lines: List<Line>)

    /** Spacings in metres, from 1:1 万 detail to a whole-province view. */
    val SPACINGS = listOf(100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000)

    /** Widest view (degrees of longitude) a grid is drawn for: beyond that a zone's lines bend too much to read. */
    const val MAX_SPAN_DEGREES = 6.0

    private const val SAMPLES = 16
    private const val INSET = 0.04

    /** The smallest spacing that gives at most about [target] lines across the shorter side of a view [shorterMeters] wide. */
    fun spacing(shorterMeters: Double, target: Int = 6): Int =
        SPACINGS.firstOrNull { shorterMeters / it <= target } ?: SPACINGS.last()

    /**
     * The grid over [view] in the [width]° zone of the view's centre, or null
     * when the view is too wide or too close to a pole for a grid to make sense.
     */
    fun of(view: Bounds, width: Int, ellipsoid: Ellipsoid = Ellipsoid.CGCS2000): Grid? {
        if (view.east - view.west > MAX_SPAN_DEGREES || view.east <= view.west || view.north <= view.south) return null
        if (view.south < -80 || view.north > 84) return null
        val zone = GaussKruger.zone(view.center.lng, width)
        val tm = TransverseMercator(ellipsoid, GaussKruger.centralMeridian(zone, width))
        // The view's extent in plane coordinates, from its edges sampled finely.
        var minE = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE
        var minN = Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        for (i in 0..SAMPLES) {
            val t = i.toDouble() / SAMPLES
            val lng = view.west + (view.east - view.west) * t
            val lat = view.south + (view.north - view.south) * t
            listOf(LngLat(lng, view.south), LngLat(lng, view.north), LngLat(view.west, lat), LngLat(view.east, lat)).forEach { p ->
                val (e, n) = tm.forward(p)
                minE = min(minE, e); maxE = max(maxE, e); minN = min(minN, n); maxN = max(maxN, n)
            }
        }
        val step = spacing(min(maxE - minE, maxN - minN)).toDouble()
        val decimals = if (step < 1_000) 1 else 0
        val lines = mutableListOf<Line>()
        var n = ceil(minN / step) * step
        while (n <= maxN) {
            val pts = (0..SAMPLES).map { i -> tm.inverse(minE + (maxE - minE) * i / SAMPLES, n) }
            lines += Line(pts, km(n, decimals), northing = true, anchor = along(pts, view.west + (view.east - view.west) * INSET) { it.lng })
            n += step
        }
        var e = ceil(minE / step) * step
        while (e <= maxE) {
            val pts = (0..SAMPLES).map { i -> tm.inverse(e, minN + (maxN - minN) * i / SAMPLES) }
            lines += Line(pts, "$zone " + km(e, decimals).padStart(3 + if (decimals > 0) 2 else 0, '0'), northing = false,
                anchor = along(pts, view.south + (view.north - view.south) * INSET) { it.lat })
            e += step
        }
        return Grid(zone, width, step.toInt(), lines)
    }

    private fun km(metres: Double, decimals: Int): String {
        val k = metres / 1000
        return if (decimals == 0) Math.round(k).toString() else "%.1f".format(java.util.Locale.ROOT, k)
    }

    /** The point of [pts] where [coord] reaches [target], or null when the line never gets there. */
    private fun along(pts: List<LngLat>, target: Double, coord: (LngLat) -> Double): LngLat? {
        for (i in 0 until pts.size - 1) {
            val a = coord(pts[i]); val b = coord(pts[i + 1])
            if ((a - target) * (b - target) <= 0 && a != b) {
                val t = (target - a) / (b - a)
                return LngLat(pts[i].lng + (pts[i + 1].lng - pts[i].lng) * t, pts[i].lat + (pts[i + 1].lat - pts[i].lat) * t)
            }
        }
        return null
    }
}
