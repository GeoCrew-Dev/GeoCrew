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

    /** Density contours of directions on the net; see [density]. */
    data class Density(
        /** Contour lines by level, in percent per 1 % of the net's area; they may run past the primitive circle, so clip when drawing. */
        val lines: List<Pair<Double, List<NetPoint>>>,
        val levels: List<Double>,
        /** Highest density and where it is. */
        val max: Double,
        val maxAt: NetPoint,
    )

    /** Fisher-kernel concentration whose kernel covers the same area as a 1 % counting circle. */
    private const val KAPPA = 100.0

    /** How far past the primitive circle the grid reaches, so contours run up to the rim. */
    private const val MARGIN = 1.1

    /**
     * Pole density as usually contoured on a Schmidt net (等密图): the share
     * of [lines] falling into a counting area of 1 % of the net, in percent,
     * so a uniform spread reads 1 % everywhere. Counting uses a smooth Fisher
     * kernel exp(κ(|cos θ| − 1)) with κ = 100, which has the same area as the
     * classic 1 % counting circle but gives smooth contours even for a few
     * dozen readings. Lines are axes: directions near the primitive circle
     * count on both sides of it. Levels double from 1 % (0.5 % when the data
     * are very spread). Null for fewer than three lines.
     */
    fun density(lines: List<Lineation>, cells: Int = 64): Density? {
        if (lines.size < 3) return null
        val vs = lines.map { it.vector() }
        val n = cells + 1
        val step = 2 * MARGIN / cells
        val grid = Array(n) { row ->
            val y = MARGIN - row * step
            DoubleArray(n) { col ->
                val x = -MARGIN + col * step
                val d = direction(x, y) ?: return@DoubleArray Double.NaN
                var sum = 0.0
                for (v in vs) sum += kotlin.math.exp(KAPPA * (kotlin.math.abs(v[0] * d[0] + v[1] * d[1] + v[2] * d[2]) - 1))
                // The kernel integrates to 2π/κ (one cap), the counting area to 1 % of the hemisphere (2π · 0.01).
                sum / vs.size * 100 * (KAPPA * 0.01)
            }
        }
        var max = -1.0; var maxRow = 0; var maxCol = 0
        for (r in 0 until n) for (c in 0 until n) {
            val x = -MARGIN + c * step; val y = MARGIN - r * step
            if (x * x + y * y <= 1 && grid[r][c] > max) { max = grid[r][c]; maxRow = r; maxCol = c }
        }
        val levels = generateSequence(if (max < 2) 0.5 else 1.0) { it * 2 }.takeWhile { it < max }.toList()
        val traced = levels.flatMap { level ->
            Contours.trace(grid, level).map { l -> level to l.points.map { (c, r) -> NetPoint(-MARGIN + c * step, MARGIN - r * step) } }
        }
        return Density(traced, levels, max, NetPoint(-MARGIN + maxCol * step, MARGIN - maxRow * step))
    }

    /** Unit vector (east, north, down) of net point (x, y); beyond the primitive circle it is the upper hemisphere. */
    private fun direction(x: Double, y: Double): DoubleArray? {
        val r = hypot(x, y)
        if (r > sqrt(2.0)) return null
        val theta = 2 * asin(r / sqrt(2.0)) // angle from straight down
        val h = sin(theta)
        return if (r == 0.0) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(h * x / r, h * y / r, cos(theta))
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
