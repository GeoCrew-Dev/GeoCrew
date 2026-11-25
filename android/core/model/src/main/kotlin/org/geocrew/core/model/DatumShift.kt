package org.geocrew.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Earth-centred, earth-fixed coordinates in metres. */
data class Ecef(val x: Double, val y: Double, val z: Double)

/** Geodetic position with ellipsoidal height in metres. */
data class Geodetic(val position: LngLat, val height: Double = 0.0)

fun Ellipsoid.toEcef(g: Geodetic): Ecef {
    val e2 = f * (2 - f)
    val phi = g.position.lat * PI / 180
    val lambda = g.position.lng * PI / 180
    val n = a / sqrt(1 - e2 * sin(phi) * sin(phi))
    return Ecef((n + g.height) * cos(phi) * cos(lambda), (n + g.height) * cos(phi) * sin(lambda), (n * (1 - e2) + g.height) * sin(phi))
}

fun Ellipsoid.toGeodetic(c: Ecef): Geodetic {
    val e2 = f * (2 - f)
    val p = hypot(c.x, c.y)
    val lambda = atan2(c.y, c.x)
    var phi = atan2(c.z, p * (1 - e2))
    var h = 0.0
    repeat(8) {
        val n = a / sqrt(1 - e2 * sin(phi) * sin(phi))
        h = p / cos(phi) - n
        phi = atan2(c.z, p * (1 - e2 * n / (n + h)))
    }
    return Geodetic(LngLat(lambda * 180 / PI, phi * 180 / PI), h)
}

/**
 * Moving coordinates between geodetic datums, e.g. from GPS (WGS 84,
 * within a metre of CGCS2000) to an old Beijing 54 or Xi'an 80 map grid.
 * The parameters are local: survey offices publish them for an area, or
 * they are fitted here from a few points known in both systems.
 */
@Serializable
sealed interface DatumShift {
    /**
     * Seven-parameter Bursa-Wolf shift between earth-centred frames, with
     * the coordinate-frame rotation convention common in Chinese survey
     * practice: rotations in arc seconds, scale in parts per million.
     * Turns a source-datum position into the target datum.
     */
    @Serializable
    @SerialName("bursa")
    data class Bursa(
        val dx: Double,
        val dy: Double,
        val dz: Double,
        val rx: Double = 0.0,
        val ry: Double = 0.0,
        val rz: Double = 0.0,
        val ppm: Double = 0.0,
    ) : DatumShift {
        fun forward(c: Ecef): Ecef {
            val k = 1 + ppm * 1e-6
            val (ex, ey, ez) = Triple(rx * ARCSEC, ry * ARCSEC, rz * ARCSEC)
            return Ecef(
                dx + k * (c.x + ez * c.y - ey * c.z),
                dy + k * (-ez * c.x + c.y + ex * c.z),
                dz + k * (ey * c.x - ex * c.y + c.z),
            )
        }

        /** The way back; exact to well under a millimetre for the small rotations datums have. */
        fun inverse(c: Ecef): Ecef {
            val k = 1 + ppm * 1e-6
            val (ex, ey, ez) = Triple(rx * ARCSEC, ry * ARCSEC, rz * ARCSEC)
            val (u, v, w) = Triple((c.x - dx) / k, (c.y - dy) / k, (c.z - dz) / k)
            // The rotation's transpose undoes it to first order; one refinement step takes the rest.
            var x = Ecef(u - ez * v + ey * w, ez * u + v - ex * w, -ey * u + ex * v + w)
            val back = forward(x)
            x = Ecef(x.x + (c.x - back.x), x.y + (c.y - back.y), x.z + (c.z - back.z))
            return x
        }
    }

    /**
     * Four-parameter similarity on the map plane (two shifts, a rotation
     * and a scale), the usual choice for an area of a few tens of
     * kilometres. Takes Gauss-Krüger X (north) and Y (east) on the source
     * datum to the target grid, both without the zone number.
     */
    @Serializable
    @SerialName("plane")
    data class Plane(val dx: Double, val dy: Double, val rotationSeconds: Double, val ppm: Double) : DatumShift {
        private val c: Double get() = (1 + ppm * 1e-6) * cos(rotationSeconds * ARCSEC)
        private val s: Double get() = (1 + ppm * 1e-6) * sin(rotationSeconds * ARCSEC)

        fun forward(x: Double, y: Double): Pair<Double, Double> = Pair(dx + c * x - s * y, dy + s * x + c * y)

        fun inverse(x: Double, y: Double): Pair<Double, Double> {
            val (u, v) = Pair(x - dx, y - dy)
            val d = c * c + s * s
            return Pair((c * u + s * v) / d, (-s * u + c * v) / d)
        }
    }

    companion object {
        const val ARCSEC = PI / 180 / 3600
    }
}

/** A point known in both systems, for fitting a [DatumShift]. */
data class CommonPoint(val name: String, val gps: Geodetic, val x: Double, val y: Double, val h: Double? = null) {
    companion object {
        /**
         * Reads one point per line, "名称, 经度, 纬度, X, Y[, 高程]", separated
         * by commas, tabs or spaces; X is north, Y east, the zone number may
         * be in front of Y. Returns the points and a note for each line that
         * could not be read. Blank lines and lines starting with # are skipped.
         */
        fun parse(text: String): Pair<List<CommonPoint>, List<String>> {
            val points = ArrayList<CommonPoint>()
            val problems = ArrayList<String>()
            for ((i, raw) in text.lines().withIndex()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("""[,，\t ]+""")).filter { it.isNotEmpty() }
                val nums = parts.drop(1).map { it.toDoubleOrNull() }
                if (parts.size < 5 || nums.take(4).any { it == null }) {
                    problems += "第 ${i + 1} 行要有名称、经度、纬度、X、Y"
                    continue
                }
                val (lng, lat, x, y) = nums.take(4).map { it!! }
                if (lng !in -180.0..180.0 || lat !in -90.0..90.0) {
                    problems += "第 ${i + 1} 行的经纬度不对（先经度后纬度）"
                    continue
                }
                if (x < 1_000_000 && y > 1_000_000 && y < 10_000_000) {
                    problems += "第 ${i + 1} 行的 X、Y 像是写反了（X 是北向坐标，Y 是东向）"
                    continue
                }
                val h = nums.getOrNull(4)
                points += CommonPoint(parts[0], Geodetic(LngLat(lng, lat), h ?: 0.0), x, y, h)
            }
            return points to problems
        }
    }
}

/** A fitted shift with how far each common point misses after it, in metres. */
data class ShiftFit<T : DatumShift>(val shift: T, val residuals: List<Double>) {
    val rms: Double get() = if (residuals.isEmpty()) 0.0 else sqrt(residuals.sumOf { it * it } / residuals.size)
    val worst: Double get() = residuals.maxOrNull() ?: 0.0
}

/**
 * An old-style map grid: Gauss-Krüger coordinates on [ellipsoid] in a
 * fixed zone, reached from GPS positions through [shift]. Without a shift
 * it only changes the ellipsoid, which for Beijing 54 and Xi'an 80 is off
 * by up to a hundred metres or more.
 */
@Serializable
data class LocalGrid(
    val name: String,
    val ellipsoid: Ellipsoid,
    val width: Int,
    val zone: Int,
    val shift: DatumShift? = null,
) {
    private val cm: Double get() = GaussKruger.centralMeridian(zone, width)

    /** A GPS (WGS 84 / CGCS2000) position on this grid: X north, Y east without the zone number. */
    fun toGrid(p: Geodetic): Pair<Double, Double> = when (val s = shift) {
        is DatumShift.Plane -> TransverseMercator(Ellipsoid.CGCS2000, cm).forward(p.position).let { (e, n) -> s.forward(n, e) }
        is DatumShift.Bursa -> {
            val g = ellipsoid.toGeodetic(s.forward(Ellipsoid.CGCS2000.toEcef(p)))
            TransverseMercator(ellipsoid, cm).forward(g.position).let { (e, n) -> Pair(n, e) }
        }
        null -> TransverseMercator(ellipsoid, cm).forward(p.position).let { (e, n) -> Pair(n, e) }
    }

    /** Back from grid X (north) and Y (east, the zone number may be in front) to a GPS position. */
    fun fromGrid(x: Double, y: Double, height: Double = 0.0): LngLat {
        val east = if (y >= 1_000_000) y - zone * 1_000_000.0 else y
        return when (val s = shift) {
            is DatumShift.Plane -> s.inverse(x, east).let { (n, e) -> TransverseMercator(Ellipsoid.CGCS2000, cm).inverse(e, n) }
            is DatumShift.Bursa -> {
                val local = TransverseMercator(ellipsoid, cm).inverse(east, x)
                Ellipsoid.CGCS2000.toGeodetic(s.inverse(ellipsoid.toEcef(Geodetic(local, height)))).position
            }
            null -> TransverseMercator(ellipsoid, cm).inverse(east, x)
        }
    }

    companion object {
        /** Why [g] cannot be used, or null; for grids read from elsewhere, such as shared settings. */
        fun problem(g: LocalGrid): String? {
            if (g.name.isBlank()) return "没有名称"
            if (g.width != 3 && g.width != 6) return "带宽只能是 3° 或 6°"
            if (g.zone < 1 || g.zone > 360 / g.width) return "带号不对"
            val nums = when (val s = g.shift) {
                is DatumShift.Bursa -> listOf(s.dx, s.dy, s.dz, s.rx, s.ry, s.rz, s.ppm)
                is DatumShift.Plane -> listOf(s.dx, s.dy, s.rotationSeconds, s.ppm)
                null -> emptyList()
            }
            if (nums.any { !it.isFinite() }) return "参数不是有效数字"
            val ppm = when (val s = g.shift) {
                is DatumShift.Bursa -> s.ppm
                is DatumShift.Plane -> s.ppm
                null -> 0.0
            }
            if (kotlin.math.abs(ppm) > 1000) return "尺度参数过大"
            return null
        }

        /**
         * Fits a four-parameter plane shift to two or more common points,
         * by least squares. Null with fewer than two distinct points.
         */
        fun fitPlane(points: List<CommonPoint>, width: Int, zone: Int): ShiftFit<DatumShift.Plane>? {
            if (points.size < 2) return null
            val tm = TransverseMercator(Ellipsoid.CGCS2000, GaussKruger.centralMeridian(zone, width))
            val src = points.map { tm.forward(it.gps.position).let { (e, n) -> Pair(n, e) } }
            val dst = points.map { Pair(it.x, if (it.y >= 1_000_000) it.y - zone * 1_000_000.0 else it.y) }
            val (sx, sy) = Pair(src.sumOf { it.first } / src.size, src.sumOf { it.second } / src.size)
            val (tx, ty) = Pair(dst.sumOf { it.first } / dst.size, dst.sumOf { it.second } / dst.size)
            var num1 = 0.0; var num2 = 0.0; var den = 0.0
            for (i in src.indices) {
                val (u, v) = Pair(src[i].first - sx, src[i].second - sy)
                val (p, q) = Pair(dst[i].first - tx, dst[i].second - ty)
                num1 += u * p + v * q
                num2 += u * q - v * p
                den += u * u + v * v
            }
            if (den < 1.0) return null // all points in one place
            val a = num1 / den
            val b = num2 / den
            val shift = DatumShift.Plane(
                dx = tx - (a * sx - b * sy),
                dy = ty - (b * sx + a * sy),
                rotationSeconds = atan2(b, a) / DatumShift.ARCSEC,
                ppm = (hypot(a, b) - 1) * 1e6,
            )
            val res = src.indices.map { i -> shift.forward(src[i].first, src[i].second).let { (x, y) -> hypot(x - dst[i].first, y - dst[i].second) } }
            return ShiftFit(shift, res)
        }

        /**
         * Fits a seven-parameter shift to three or more common points with
         * heights, by linearised least squares. Null when the points are too
         * few or too close together to tell rotations from shifts; a plane
         * shift then does better.
         */
        fun fitBursa(points: List<CommonPoint>, ellipsoid: Ellipsoid, width: Int, zone: Int): ShiftFit<DatumShift.Bursa>? {
            if (points.size < 3) return null
            val tm = TransverseMercator(ellipsoid, GaussKruger.centralMeridian(zone, width))
            val src = points.map { Ellipsoid.CGCS2000.toEcef(it.gps) }
            val dst = points.map {
                val east = if (it.y >= 1_000_000) it.y - zone * 1_000_000.0 else it.y
                ellipsoid.toEcef(Geodetic(tm.inverse(east, it.x), it.h ?: it.gps.height))
            }
            val c = Ecef(src.sumOf { it.x } / src.size, src.sumOf { it.y } / src.size, src.sumOf { it.z } / src.size)
            // Unknowns: shift at the centroid (3), rotations in radians (3), scale (1); columns scaled to unit size.
            val rows = ArrayList<DoubleArray>()
            val rhs = ArrayList<Double>()
            for (i in src.indices) {
                val (u, v, w) = Triple(src[i].x - c.x, src[i].y - c.y, src[i].z - c.z)
                val s = src[i]
                val d = dst[i]
                rows += doubleArrayOf(1.0, 0.0, 0.0, 0.0, -w, v, u); rhs += d.x - s.x
                rows += doubleArrayOf(0.0, 1.0, 0.0, w, 0.0, -u, v); rhs += d.y - s.y
                rows += doubleArrayOf(0.0, 0.0, 1.0, -v, u, 0.0, w); rhs += d.z - s.z
            }
            val sol = leastSquares(rows, rhs) ?: return null
            val (ex, ey, ez, m) = listOf(sol[3], sol[4], sol[5], sol[6])
            // Shift at the earth's centre: d = d' - M·c.
            val shift = DatumShift.Bursa(
                dx = sol[0] - (m * c.x + ez * c.y - ey * c.z),
                dy = sol[1] - (-ez * c.x + m * c.y + ex * c.z),
                dz = sol[2] - (ey * c.x - ex * c.y + m * c.z),
                rx = ex / DatumShift.ARCSEC, ry = ey / DatumShift.ARCSEC, rz = ez / DatumShift.ARCSEC,
                ppm = m * 1e6,
            )
            val res = src.indices.map { i ->
                val p = shift.forward(src[i])
                sqrt((p.x - dst[i].x) * (p.x - dst[i].x) + (p.y - dst[i].y) * (p.y - dst[i].y) + (p.z - dst[i].z) * (p.z - dst[i].z))
            }
            return ShiftFit(shift, res)
        }

        /** Normal equations with column scaling and partial pivoting; null when singular. */
        private fun leastSquares(rows: List<DoubleArray>, rhs: List<Double>): DoubleArray? {
            val n = rows.first().size
            val scale = DoubleArray(n)
            for (j in 0 until n) {
                scale[j] = sqrt(rows.sumOf { it[j] * it[j] })
                if (scale[j] == 0.0) return null
            }
            val a = Array(n) { DoubleArray(n + 1) }
            for ((r, row) in rows.withIndex()) {
                for (i in 0 until n) {
                    val ri = row[i] / scale[i]
                    for (j in 0 until n) a[i][j] += ri * row[j] / scale[j]
                    a[i][n] += ri * rhs[r]
                }
            }
            for (col in 0 until n) {
                val piv = (col until n).maxBy { abs(a[it][col]) }
                if (abs(a[piv][col]) < 1e-9) return null
                val t = a[piv]; a[piv] = a[col]; a[col] = t
                for (r in 0 until n) {
                    if (r == col) continue
                    val k = a[r][col] / a[col][col]
                    for (j in col..n) a[r][j] -= k * a[col][j]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] / scale[it] }
        }
    }
}
