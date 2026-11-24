package org.geocrew.core.gis

import org.geocrew.core.model.Attitude
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.positions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.tan

/** Where a position falls on a polyline, measured on a local plane. */
internal data class Station(
    /** Distance along the line from its first vertex, in metres. */
    val chainage: Double,
    /** Distance from the line; positive to the right when walking along it. */
    val offset: Double,
    /** Azimuth of the segment the position projects onto, degrees from north. */
    val azimuth: Double,
    val foot: LngLat,
)

/** Projects positions onto [line]; the closest segment wins. */
internal class Polyline(line: List<LngLat>) {
    private val plane = LocalPlane(line.first())
    private val xs = line.map { plane.x(it) }
    private val ys = line.map { plane.y(it) }
    private val starts = DoubleArray(line.size).also { c ->
        for (i in 1 until line.size) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    val length get() = starts.last()

    fun project(p: LngLat): Station? {
        val px = plane.x(p)
        val py = plane.y(p)
        var best: Station? = null
        var bestDist = Double.MAX_VALUE
        for (i in 0 until xs.size - 1) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) continue
            val t = (((px - xs[i]) * dx + (py - ys[i]) * dy) / len2).coerceIn(0.0, 1.0)
            val fx = xs[i] + t * dx
            val fy = ys[i] + t * dy
            val d = hypot(px - fx, py - fy)
            if (d < bestDist) {
                bestDist = d
                // Cross product sign: negative means the point lies to the right of the direction of travel.
                val side = if (dx * (py - ys[i]) - dy * (px - xs[i]) < 0) 1 else -1
                val az = Geodesy.normalizeDegrees(atan2(dx, dy) * 180 / PI)
                best = Station(starts[i] + t * kotlin.math.sqrt(len2), side * d, az, plane.toLngLat(fx, fy))
            }
        }
        return best
    }
}

/**
 * Apparent dip of a plane seen in a vertical section with azimuth [sectionAzimuth]:
 * tan β = tan δ · cos(θ − dip direction). Positive when the plane dips toward the
 * section's forward direction, negative when it dips back toward its start.
 */
fun apparentDip(a: Attitude, sectionAzimuth: Double): Double {
    if (a.dip >= 90.0) return 90.0
    val c = cos((sectionAzimuth - a.dipDirection) * PI / 180)
    val beta = atan(tan(a.dip * PI / 180) * abs(c)) * 180 / PI
    return if (c >= 0) beta else -beta
}

/**
 * Projects measured attitudes onto a section line and works out the dip each
 * plane shows in that section, the usual first step when drawing a geological
 * cross-section from field data.
 */
class SectionTool : GisTool {
    override val id = "section"
    override val name = "剖面投影与视倾角"
    override val description = "把剖面线两侧一定距离内的产状点投影到剖面线上，算出每个面在剖面上的视倾角和到起点的距离，可作为绘制地质剖面图的底稿。"
    override val accepts = setOf(GeometryType.POINT, GeometryType.LINE)

    private val line = ToolParam.Feature("line", "剖面线", setOf(GeometryType.LINE), help = "方向从线的起点指向终点")
    private val width = ToolParam.Number("width", "投影带宽（单侧）", default = 200.0, min = 1.0, max = 50_000.0, unit = "m", help = "离剖面线更远的点不参与")
    override val params = listOf(line, width)

    override fun check(input: ToolInput): String? {
        val l = input.feature(line) ?: return "请选择一条剖面线"
        if ((l.geometry?.positions()?.size ?: 0) < 2) return "剖面线至少要有两个节点"
        val n = input.of(setOf(GeometryType.POINT)).count { it.attitude != null }
        return if (n == 0) "工程里还没有带产状的点" else null
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        check(input)?.let { throw IllegalArgumentException(it) }
        val l = input.feature(line)!!
        val path = when (val g = l.geometry) {
            is Geometry.LineString -> g.positions
            is Geometry.MultiLineString -> g.lines.maxBy { it.size }
            else -> throw IllegalArgumentException("剖面线不是线")
        }
        val poly = Polyline(path)
        val band = input.number(width) ?: 200.0
        val points = input.of(setOf(GeometryType.POINT)).filter { it.attitude != null }

        data class Hit(val name: String, val st: Station, val a: Attitude, val beta: Double)
        val hits = ArrayList<Hit>()
        points.forEachIndexed { i, f ->
            if (input.cancelled()) throw ToolCancelled()
            val p = (f.geometry as? Geometry.Point)?.position ?: return@forEachIndexed
            val st = poly.project(p) ?: return@forEachIndexed
            // Points beyond either end are not on the section, however close.
            if (abs(st.offset) <= band && st.chainage > 0.0 && st.chainage < poly.length) {
                val a = f.attitude!!
                hits += Hit(f.title ?: "未命名", st, a, apparentDip(a, st.azimuth))
            }
            progress((i + 1).toFloat() / points.size)
        }
        hits.sortBy { it.st.chainage }

        val features = hits.map { h ->
            OutputFeature(FeatureKind.POINT, Geometry.Point(h.st.foot), buildProperties {
                this[OutputProps.TOOL] = id
                this[Props.TITLE] = "${h.name} 视倾角 ${"%.0f".format(abs(h.beta))}°"
                this[SECTION_LINE] = l.title ?: l.id
                this[CHAINAGE_M] = round1(h.st.chainage)
                this[SECTION_LENGTH_M] = round1(poly.length)
                this[OFFSET_M] = round1(h.st.offset)
                this[APPARENT_DIP] = round1(h.beta)
                this[SOURCE] = h.name
                this[TRUE_ATTITUDE] = h.a.format()
                this["marker-color"] = if (h.beta >= 0) "#C62828" else "#1565C0"
            })
        }
        val title = l.title ?: "剖面线"
        val summary = if (hits.isEmpty()) {
            "「$title」两侧 ${CoordinateFormatter.distance(band)} 内没有带产状的点。"
        } else buildString {
            append("「$title」长 ${CoordinateFormatter.distance(poly.length)}，投影了 ${hits.size} 个产状点（红色向剖面前进方向倾，蓝色向起点方向倾）：")
            hits.take(SUMMARY_ROWS).forEach { h ->
                append("\n${h.name}：距起点 ${CoordinateFormatter.distance(h.st.chainage)}，${h.a.format()} → 视倾角 ${"%.0f".format(abs(h.beta))}°" + if (h.beta < 0) "（向起点）" else "")
            }
            if (hits.size > SUMMARY_ROWS) append("\n……其余 ${hits.size - SUMMARY_ROWS} 个见输出记录的属性。")
        }
        return ToolOutput(features, summary)
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    companion object {
        const val SECTION_LINE = "section_line"
        const val CHAINAGE_M = "chainage_m"
        /** The whole section line's length, on every output, so a strip can be drawn to scale. */
        const val SECTION_LENGTH_M = "section_length_m"
        const val OFFSET_M = "offset_m"
        const val APPARENT_DIP = "apparent_dip"
        const val SOURCE = "source"
        const val TRUE_ATTITUDE = "true_attitude"
        private const val SUMMARY_ROWS = 8
    }
}
