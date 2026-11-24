package org.geocrew.core.gis

import org.geocrew.core.model.Attitude
import org.geocrew.core.model.Geodesy
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the net, in units of the primitive circle's radius; +y is north, +x is east. */
data class NetPoint(val x: Double, val y: Double)

/** A line (direction) in space as trend (azimuth, degrees) and plunge (degrees below horizontal). */
data class Lineation(val trend: Double, val plunge: Double) {
    /** Unit vector (east, north, down). */
    fun vector(): DoubleArray {
        val t = trend.rad()
        val p = plunge.rad()
        return doubleArrayOf(cos(p) * sin(t), cos(p) * cos(t), sin(p))
    }

    companion object {
        /** From a vector (east, north, down); flipped into the lower hemisphere. */
        fun of(e: Double, n: Double, d: Double): Lineation {
            val s = if (d < 0) -1.0 else 1.0
            val len = sqrt(e * e + n * n + d * d)
            require(len > 0) { "zero vector" }
            val plunge = asin((d * s / len).coerceIn(-1.0, 1.0)).deg()
            val trend = if (hypot(e, n) < 1e-12) 0.0 else Geodesy.normalizeDegrees(atan2(e * s, n * s).deg())
            return Lineation(trend, plunge)
        }
    }
}

/** Fisher statistics of a set of directions. */
data class FisherStats(
    val count: Int,
    val mean: Lineation,
    /** Mean resultant length, 0..1; near 1 means tightly clustered. */
    val resultant: Double,
    /** Fisher precision parameter k; null when it cannot be estimated (n < 2 or all identical). */
    val kappa: Double?,
    /** Radius of the 95 % confidence cone around the mean, degrees; null when n < 2. */
    val alpha95: Double?,
)

/**
 * Lower-hemisphere equal-area (Schmidt) projection, the usual stereonet for
 * structural data: equal areas on the sphere stay equal on the net, so the
 * density of poles can be read off directly.
 */
object Stereonet {
    /** Projects a line. Horizontal lines fall on the primitive circle, vertical ones at the centre. */
    fun project(l: Lineation): NetPoint {
        val r = sqrt(2.0) * sin((90 - l.plunge).rad() / 2)
        val t = l.trend.rad()
        return NetPoint(r * sin(t), r * cos(t))
    }

    /** The pole (normal) of a plane, pointing down. */
    fun pole(a: Attitude) = Lineation(Geodesy.normalizeDegrees(a.dipDirection + 180), 90 - a.dip)

    /** The plane's trace on the net, from one end of its strike line to the other. */
    fun greatCircle(a: Attitude, steps: Int = 90): List<NetPoint> {
        val strike = Lineation(a.strike, 0.0).vector()
        val dip = Lineation(a.dipDirection, a.dip).vector()
        return (0..steps).map { i ->
            val rake = PI * i / steps
            val c = cos(rake)
            val s = sin(rake)
            project(Lineation.of(c * strike[0] + s * dip[0], c * strike[1] + s * dip[1], c * strike[2] + s * dip[2]))
        }
    }

    /**
     * The small circle of directions at [angle] degrees from [axis], cut to
     * the lower hemisphere; may come back as several pieces.
     */
    fun smallCircle(axis: Lineation, angle: Double, steps: Int = 120): List<List<NetPoint>> {
        val a = axis.vector()
        // Two unit vectors perpendicular to the axis.
        val helper = if (kotlin.math.abs(a[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val u = cross(a, helper).normalized()
        val v = cross(a, u)
        val ca = cos(angle.rad())
        val sa = sin(angle.rad())
        val pieces = mutableListOf<MutableList<NetPoint>>()
        var current: MutableList<NetPoint>? = null
        for (i in 0..steps) {
            val t = 2 * PI * i / steps
            val d = DoubleArray(3) { k -> ca * a[k] + sa * (cos(t) * u[k] + sin(t) * v[k]) }
            if (d[2] < -1e-9) {
                current = null
            } else {
                if (current == null) current = mutableListOf<NetPoint>().also(pieces::add)
                current.add(project(Lineation.of(d[0], d[1], d[2])))
            }
        }
        return pieces.filter { it.size > 1 }
    }

    /** Vector mean and Fisher statistics of lines (e.g. poles), treated as axes in the lower hemisphere. */
    fun fisher(lines: List<Lineation>): FisherStats? {
        if (lines.isEmpty()) return null
        var e = 0.0
        var n = 0.0
        var d = 0.0
        lines.forEach { l -> l.vector().let { e += it[0]; n += it[1]; d += it[2] } }
        val count = lines.size
        val r = sqrt(e * e + n * n + d * d)
        val mean = if (r < 1e-12) lines.first() else Lineation.of(e, n, d)
        val kappa = if (count < 2 || count - r < 1e-9) null else (count - 1) / (count - r)
        val alpha95 = if (count < 2 || r < 1e-9) null else {
            val x = 1 - (count - r) / r * ((1 / 0.05).pow(1.0 / (count - 1)) - 1)
            acos(x.coerceIn(-1.0, 1.0)).deg()
        }
        return FisherStats(count, mean, r / count, kappa, alpha95)
    }

    /** The plane whose pole is [pole]. */
    fun planeOfPole(pole: Lineation): Attitude = Attitude.of(pole.trend + 180, 90 - pole.plunge)

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun DoubleArray.normalized(): DoubleArray {
        val l = sqrt(this[0] * this[0] + this[1] * this[1] + this[2] * this[2])
        return DoubleArray(3) { this[it] / l }
    }
}

private fun Double.rad() = this * PI / 180
private fun Double.deg() = this * 180 / PI
