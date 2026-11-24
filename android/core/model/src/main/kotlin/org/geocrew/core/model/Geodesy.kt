package org.geocrew.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical geodesy good to ~0.5% — ample for field sketches, lengths and
 * areas shown on a phone. Survey-grade work belongs in desktop GIS.
 */
object Geodesy {
    /** IUGG mean Earth radius in metres. */
    const val EARTH_RADIUS = 6_371_008.8

    private fun Double.rad() = this * PI / 180
    private fun Double.deg() = this * 180 / PI

    /** Great-circle distance in metres (haversine). */
    fun distance(a: LngLat, b: LngLat): Double {
        val dLat = (b.lat - a.lat).rad()
        val dLng = (b.lng - a.lng).rad()
        val h = sin(dLat / 2).let { it * it } +
            cos(a.lat.rad()) * cos(b.lat.rad()) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], degrees clockwise from north in [0, 360). */
    fun bearing(a: LngLat, b: LngLat): Double {
        val φ1 = a.lat.rad()
        val φ2 = b.lat.rad()
        val Δλ = (b.lng - a.lng).rad()
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return normalizeDegrees(atan2(y, x).deg())
    }

    /** Point reached from [start] after [distance] metres on [bearing] degrees. */
    fun destination(start: LngLat, bearing: Double, distance: Double): LngLat {
        val δ = distance / EARTH_RADIUS
        val θ = bearing.rad()
        val φ1 = start.lat.rad()
        val λ1 = start.lng.rad()
        val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ))
        val λ2 = λ1 + atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2))
        return LngLat(wrapLongitude(λ2.deg()), φ2.deg())
    }

    /** Length of a path in metres. */
    fun length(path: List<LngLat>): Double = path.zipWithNext { a, b -> distance(a, b) }.sum()

    /**
     * Area of a ring in square metres on the sphere
     * (Chamberlain & Duquette, "Some algorithms for polygons on a sphere", 2007).
     * The ring must be open: do not repeat the first vertex at the end.
     */
    fun ringArea(ring: List<LngLat>): Double {
        if (ring.size < 3) return 0.0
        var total = 0.0
        val n = ring.size
        for (i in 0 until n) {
            val p1 = ring[i]
            val p2 = ring[(i + 1) % n]
            val p3 = ring[(i + 2) % n]
            total += (p3.lng.rad() - p1.lng.rad()) * sin(p2.lat.rad())
        }
        return abs(total * EARTH_RADIUS * EARTH_RADIUS / 2)
    }

    /** Area of a polygon (exterior minus holes) in square metres. */
    fun area(polygon: Geometry.Polygon): Double {
        val rings = polygon.rings.map { r -> if (r.size > 1 && r.first().sameXY(r.last())) r.dropLast(1) else r }
        if (rings.isEmpty()) return 0.0
        return (ringArea(rings.first()) - rings.drop(1).sumOf { ringArea(it) }).coerceAtLeast(0.0)
    }

    fun normalizeDegrees(d: Double): Double = ((d % 360) + 360) % 360

    fun wrapLongitude(lng: Double): Double = ((lng + 540) % 360) - 180
}

/** Length in metres for line-like geometries, 0 otherwise. */
fun Geometry.lengthMeters(): Double = when (this) {
    is Geometry.LineString -> Geodesy.length(positions)
    is Geometry.MultiLineString -> lines.sumOf { Geodesy.length(it) }
    is Geometry.Polygon -> Geodesy.length(exterior)
    else -> 0.0
}

/** Area in square metres for polygonal geometries, 0 otherwise. */
fun Geometry.areaSquareMeters(): Double = when (this) {
    is Geometry.Polygon -> Geodesy.area(this)
    is Geometry.MultiPolygon -> polygons.sumOf { Geodesy.area(it) }
    else -> 0.0
}

/** Coordinate display formats offered in settings. */
enum class CoordinateFormat { DECIMAL, DMS }

object CoordinateFormatter {
    fun format(p: LngLat, style: CoordinateFormat): String = when (style) {
        CoordinateFormat.DECIMAL -> "%.6f, %.6f".fmt(p.lng, p.lat)
        CoordinateFormat.DMS -> "${dms(p.lng, "E", "W")} ${dms(p.lat, "N", "S")}"
    }

    fun dms(value: Double, positive: String, negative: String): String {
        val hemi = if (value >= 0) positive else negative
        var a = abs(value)
        var d = floor(a).toInt()
        a = (a - d) * 60
        var m = floor(a).toInt()
        var s = ((a - m) * 60 * 10).roundToInt() / 10.0
        if (s >= 60) { s -= 60; m += 1 }
        if (m >= 60) { m -= 60; d += 1 }
        return "%d°%02d′%04.1f″%s".fmt(d, m, s, hemi)
    }

    fun distance(m: Double): String = when {
        m < 1000 -> "%.1f m".fmt(m)
        else -> "%.2f km".fmt(m / 1000)
    }

    fun area(m2: Double): String = when {
        m2 < 10_000 -> "%.1f m²".fmt(m2)
        m2 < 1_000_000 -> "%.2f ha".fmt(m2 / 10_000)
        else -> "%.3f km²".fmt(m2 / 1_000_000)
    }
}

/** Locale-independent formatting: field notes must read the same on every phone. */
internal fun String.fmt(vararg args: Any?): String = String.format(java.util.Locale.ROOT, this, *args)
