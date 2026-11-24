package org.geocrew.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Reference ellipsoids used for plane coordinates in China and worldwide. */
enum class Ellipsoid(val label: String, val a: Double, val inverseFlattening: Double) {
    CGCS2000("CGCS2000", 6378137.0, 298.257222101),
    WGS84("WGS 84", 6378137.0, 298.257223563),
    XIAN80("西安 80（IAG 1975）", 6378140.0, 298.257),
    BEIJING54("北京 54（克拉索夫斯基）", 6378245.0, 298.3),
    ;

    val f: Double get() = 1 / inverseFlattening
}

/**
 * Transverse Mercator on an ellipsoid with Krüger's series to sixth order
 * in n (Karney 2011), accurate to well under a millimetre within several
 * degrees of the central meridian, which covers every 3° and 6° zone.
 *
 * Returns and takes easting and northing in metres, false origin included.
 */
class TransverseMercator(
    val ellipsoid: Ellipsoid,
    val centralMeridian: Double,
    val scale: Double = 1.0,
    val falseEasting: Double = 500_000.0,
    val falseNorthing: Double = 0.0,
) {
    private val n = ellipsoid.f / (2 - ellipsoid.f)
    private val e = sqrt(ellipsoid.f * (2 - ellipsoid.f))
    private val radius: Double
    private val alpha: DoubleArray
    private val beta: DoubleArray

    init {
        val n2 = n * n; val n3 = n2 * n; val n4 = n3 * n; val n5 = n4 * n; val n6 = n5 * n
        radius = ellipsoid.a / (1 + n) * (1 + n2 / 4 + n4 / 64 + n6 / 256)
        alpha = doubleArrayOf(
            n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800,
            13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360,
            61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440,
            49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600,
            34729 * n5 / 80640 - 3418889 * n6 / 1995840,
            212378941 * n6 / 319334400,
        )
        beta = doubleArrayOf(
            n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360 - 81 * n5 / 512 + 96199 * n6 / 604800,
            n2 / 48 + n3 / 15 - 437 * n4 / 1440 + 46 * n5 / 105 - 1118711 * n6 / 3870720,
            17 * n3 / 480 - 37 * n4 / 840 - 209 * n5 / 4480 + 5569 * n6 / 90720,
            4397 * n4 / 161280 - 11 * n5 / 504 - 830251 * n6 / 7257600,
            4583 * n5 / 161280 - 108847 * n6 / 3991680,
            20648693 * n6 / 638668800,
        )
    }

    /** (easting, northing) of [p]. */
    fun forward(p: LngLat): Pair<Double, Double> {
        val phi = p.lat * DEG
        val lambda = Geodesy.wrapLongitude(p.lng - centralMeridian) * DEG
        val tau = tan(phi)
        val sigma = sinh(e * atanh(e * tau / hypot(1.0, tau)))
        val tauP = tau * hypot(1.0, sigma) - sigma * hypot(1.0, tau) // conformal latitude, as tan
        val xiP = atan2(tauP, cos(lambda))
        val etaP = asinh(sin(lambda) / hypot(tauP, cos(lambda)))
        var xi = xiP
        var eta = etaP
        for (j in 1..6) {
            xi += alpha[j - 1] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += alpha[j - 1] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        return Pair(falseEasting + scale * radius * eta, falseNorthing + scale * radius * xi)
    }

    fun inverse(easting: Double, northing: Double): LngLat {
        val xi = (northing - falseNorthing) / (scale * radius)
        val eta = (easting - falseEasting) / (scale * radius)
        var xiP = xi
        var etaP = eta
        for (j in 1..6) {
            xiP -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val tauP = sin(xiP) / hypot(sinh(etaP), cos(xiP))
        val lambda = atan2(sinh(etaP), cos(xiP))
        // Newton's method for the geodetic latitude whose conformal one is tauP.
        val e2m = 1 - e * e
        var tau = tauP / e2m
        for (i in 0 until 6) {
            val sigma = sinh(e * atanh(e * tau / hypot(1.0, tau)))
            val tauI = tau * hypot(1.0, sigma) - sigma * hypot(1.0, tau)
            val d = (tauP - tauI) / hypot(1.0, tauI) * (1 + e2m * tau * tau) / (e2m * hypot(1.0, tau))
            tau += d
            if (abs(d) < 1e-14) break
        }
        return LngLat(Geodesy.wrapLongitude(centralMeridian + lambda / DEG), atan(tau) / DEG)
    }

    private companion object {
        const val DEG = PI / 180
        fun atanh(x: Double) = 0.5 * kotlin.math.ln((1 + x) / (1 - x))
        fun asinh(x: Double) = kotlin.math.asinh(x)
    }
}

/**
 * A Gauss-Krüger plane position as Chinese maps and survey tables write
 * it: X is northing, Y easting with the 500 km false easting; [yWithZone]
 * puts the zone number in front ("39 447924.48").
 */
data class PlanePosition(
    val x: Double,
    val y: Double,
    val zone: Int,
    val width: Int,
    val centralMeridian: Double,
    val ellipsoid: Ellipsoid,
) {
    val yWithZone: Double get() = zone * 1_000_000.0 + y
}

/** 3° and 6° Gauss-Krüger zones (GB/T 17798, the national convention). */
object GaussKruger {
    val WIDTHS = listOf(3, 6)

    /** Zone of a longitude: 3° zones centre on multiples of 3°, 6° zones on 3° + 6°·k. */
    fun zone(lng: Double, width: Int): Int = when (width) {
        3 -> (lng / 3).roundToInt().let { if (it <= 0) it + 120 else it }
        6 -> floor(lng / 6).toInt().let { if (it < 0) it + 60 else it } + 1
        else -> throw IllegalArgumentException("zone width must be 3 or 6")
    }

    fun centralMeridian(zone: Int, width: Int): Double = when (width) {
        3 -> Geodesy.wrapLongitude(3.0 * zone)
        6 -> Geodesy.wrapLongitude(6.0 * zone - 3)
        else -> throw IllegalArgumentException("zone width must be 3 or 6")
    }

    fun maxZone(width: Int): Int = if (width == 3) 120 else 60

    /** Projects [p] into its own zone, or into [zone] when given (positions near a zone edge). */
    fun project(p: LngLat, width: Int, ellipsoid: Ellipsoid = Ellipsoid.CGCS2000, zone: Int? = null): PlanePosition {
        val z = zone ?: zone(p.lng, width)
        val cm = centralMeridian(z, width)
        val (e, n) = TransverseMercator(ellipsoid, cm).forward(p)
        return PlanePosition(x = n, y = e, zone = z, width = width, centralMeridian = cm, ellipsoid = ellipsoid)
    }

    /**
     * Back to longitude and latitude. [y] may carry the zone number in front
     * (anything of 1 000 km or more), which then wins over [zone].
     */
    fun unproject(x: Double, y: Double, width: Int, ellipsoid: Ellipsoid = Ellipsoid.CGCS2000, zone: Int? = null): LngLat {
        val prefixed = y >= 1_000_000
        val z = if (prefixed) floor(y / 1_000_000).toInt() else zone
            ?: throw IllegalArgumentException("Y 不带带号时需要指定带号")
        require(z in 1..maxZone(width)) { "带号 $z 超出 ${width}° 带范围（1–${maxZone(width)}）" }
        val easting = if (prefixed) y - z * 1_000_000.0 else y
        return TransverseMercator(ellipsoid, centralMeridian(z, width)).inverse(easting, x)
    }
}
