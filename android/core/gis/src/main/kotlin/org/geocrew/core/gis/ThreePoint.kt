package org.geocrew.core.gis

import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.buildProperties
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The three-point problem (三点法求产状): the attitude of a plane, such as a
 * contact or a coal seam, from three points on it with known elevations,
 * e.g. where the contact crosses three gullies. Elevations come from the
 * points' own altitude or from a numeric property.
 */
class ThreePointTool : GisTool {
    override val id = "threepoint"
    override val name = "三点法求产状"
    override val description = "已知同一层面（接触面、煤层、断层面）上三个点的位置和高程，求这个面的倾向和倾角。高程取点的定位海拔，或指定一个数值属性。"
    override val accepts = setOf(GeometryType.POINT)

    private val a = ToolParam.Feature("a", "点 A", setOf(GeometryType.POINT))
    private val b = ToolParam.Feature("b", "点 B", setOf(GeometryType.POINT))
    private val c = ToolParam.Feature("c", "点 C", setOf(GeometryType.POINT))
    private val elevation = ToolParam.Field("elevation", "高程属性（可选）", numeric = true, help = "留空则用定位时记下的海拔")
    override val params = listOf(a, b, c, elevation)

    /** A plane through three points; [spread] is the smallest angle of their triangle, degrees. */
    data class Fit(val attitude: Attitude, val spread: Double, val drop: Double)

    override fun check(input: ToolInput): String? {
        val pts = listOf(a, b, c).map { input.feature(it) }
        if (pts.any { it == null }) return "请选择三个点"
        if (pts.map { it!!.id }.toSet().size < 3) return "三个点不能重复"
        val key = input.text(elevation.key)
        pts.forEach { f -> if (height(f!!, key) == null) return "「${f.title ?: "未命名"}」没有${if (key == null) "海拔" else "「$key」数值"}" }
        val fit = solve(pts.map { position(it!!, key)!! }) ?: return "三个点几乎在一条直线上，求不出层面"
        return if (fit.spread < MIN_SPREAD) "三个点几乎在一条直线上（三角形最小角 %.0f°），结果不可靠".format(fit.spread) else null
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        check(input)?.let { throw IllegalArgumentException(it) }
        val key = input.text(elevation.key)
        val fs = listOf(a, b, c).map { input.feature(it)!! }
        val ps = fs.map { position(it, key)!! }
        val fit = solve(ps)!!
        val at = LngLat(ps.sumOf { it.lng } / 3, ps.sumOf { it.lat } / 3, ps.mapNotNull { it.alt }.average())
        val names = fs.joinToString("、") { it.title ?: "未命名" }
        val out = OutputFeature(FeatureKind.POINT, Geometry.Point(at), buildProperties {
            this[OutputProps.TOOL] = id
            this[Props.TITLE] = "三点法 ${fit.attitude.format()}"
            fit.attitude.writeTo(this)
            this[SOURCE] = names
        })
        progress(1f)
        val summary = buildList {
            add("由 $names 求得层面产状 ${fit.attitude.format()}（走向 %.0f°）".format(fit.attitude.strike))
            add("三点高差 %.1f m，三角形最小角 %.0f°".format(fit.drop, fit.spread))
            if (fit.attitude.dip < 2) add("倾角很小，接近水平，倾向不可靠")
        }
        return ToolOutput(listOf(out), summary.joinToString("\n"))
    }

    companion object {
        const val SOURCE = "source_points"
        /** Below this smallest triangle angle the three points are treated as on a line. */
        const val MIN_SPREAD = 5.0
        private const val R = 6371008.8

        private fun height(f: GeoFeature, key: String?): Double? =
            if (key != null) f.number(key) else (f.geometry as? Geometry.Point)?.position?.alt

        private fun position(f: GeoFeature, key: String?): LngLat? {
            val p = (f.geometry as? Geometry.Point)?.position ?: return null
            return LngLat(p.lng, p.lat, height(f, key) ?: return null)
        }

        /** The plane through three points with altitudes, in a local east-north-up frame; null if they are on a line. */
        fun solve(ps: List<LngLat>): Fit? {
            require(ps.size == 3)
            val lat0 = ps.sumOf { it.lat } / 3 * PI / 180
            val lng0 = ps.sumOf { it.lng } / 3
            val v = ps.map { p ->
                doubleArrayOf((p.lng - lng0) * PI / 180 * R * cos(lat0), (p.lat - ps[0].lat) * PI / 180 * R, p.alt ?: 0.0)
            }
            val u = sub(v[1], v[0]); val w = sub(v[2], v[0])
            val n = doubleArrayOf(u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
            val len = norm(n)
            if (len < 1e-9 || abs(n[2]) < 1e-12) return null
            val att = Attitude.fromDeviceNormal(n[0], n[1], n[2])
            val drop = ps.maxOf { it.alt ?: 0.0 } - ps.minOf { it.alt ?: 0.0 }
            return Fit(att, smallestAngle(v), drop)
        }

        private fun smallestAngle(v: List<DoubleArray>): Double = (0..2).minOf { i ->
            val p = v[i]; val q = v[(i + 1) % 3]; val r = v[(i + 2) % 3]
            val e1 = sub(q, p); val e2 = sub(r, p)
            val cross = norm(doubleArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]))
            val l = norm(e1) * norm(e2)
            if (l == 0.0) 0.0 else asin((cross / l).coerceIn(0.0, 1.0)) * 180 / PI
        }

        private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        private fun norm(a: DoubleArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    }
}
