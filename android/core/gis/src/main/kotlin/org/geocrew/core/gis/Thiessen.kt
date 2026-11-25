package org.geocrew.core.gis

import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.buildProperties
import kotlin.math.hypot
import kotlin.math.max

/**
 * Thiessen (Voronoi) polygons: the area closer to each point than to any
 * other, clipped to a boundary. With a value such as a grade this is the
 * polygon method of resource estimation (多边形法): each sample stands for
 * its polygon, and the area-weighted mean is the block's average.
 */
class ThiessenTool : GisTool {
    override val id = "thiessen"
    override val name = "泰森多边形"
    override val description = "把范围分给离它最近的点，每个点得到一块面（泰森多边形）。选了数值属性（如品位）时按值着色，并给出按面积加权的平均值，即多边形法。"
    override val accepts = setOf(GeometryType.POINT, GeometryType.POLYGON)

    private val value = ToolParam.Field("value", "数值属性（可选）", numeric = true, help = "例如品位、厚度；每块面取它所属点的值")
    private val boundary = ToolParam.Feature(
        "boundary", "范围（可选）", setOf(GeometryType.POLYGON),
        help = "选一个面作为范围，只用它的外圈；不选时取点的外接矩形向外扩 10%",
    )
    private val thickness = ToolParam.Field("thickness", "厚度属性（可选）", numeric = true, help = "例如矿层真厚度（米）；给了就按面积×厚度算体积，品位按体积加权")
    private val density = ToolParam.Number("density", "体重（可选）", default = null, min = 0.1, max = 25.0, unit = "t/m³", help = "矿石体重；和厚度一起给出矿石量")
    override val params = listOf(value, boundary, thickness, density)

    /** One point's polygon. */
    data class Cell(val point: GeoFeature, val ring: List<LngLat>, val areaM2: Double, val value: Double?, val thicknessM: Double? = null) {
        val volumeM3: Double? get() = thicknessM?.let { it * areaM2 }
    }

    data class Result(val cells: List<Cell>, val duplicates: Int, val outside: Int)

    override fun check(input: ToolInput): String? = when {
        points(input).size < 2 -> "至少需要 2 个点"
        else -> null
    }

    private fun points(input: ToolInput): List<GeoFeature> =
        input.of(setOf(GeometryType.POINT)).filter { it.geometry is Geometry.Point }

    fun cells(input: ToolInput): Result {
        val all = points(input)
        require(all.size >= 2) { "至少需要 2 个点" }
        val valueKey = input.text(value.key)
        val thickKey = input.text(thickness.key)
        // One cell per location: a second point on the same spot has no area of its own.
        val unique = all.distinctBy { (it.geometry as Geometry.Point).position.let { p -> Math.round(p.lng * 1e7) to Math.round(p.lat * 1e7) } }
        val area = input.feature(boundary)?.geometry
        val outer: List<LngLat>? = when (area) {
            is Geometry.Polygon -> area.exterior
            is Geometry.MultiPolygon -> area.polygons.maxByOrNull { it.areaSquareMeters() }?.exterior
            else -> null
        }
        val inside = if (area == null) unique else unique.filter { ZonalTool.contains(area, (it.geometry as Geometry.Point).position) }
        require(inside.size >= 2) { "范围里至少需要 2 个点" }

        val plane = LocalPlane((inside.first().geometry as Geometry.Point).position)
        val xy = inside.map { (it.geometry as Geometry.Point).position.let { p -> doubleArrayOf(plane.x(p), plane.y(p)) } }
        val clip: List<DoubleArray> = outer?.dropLast(1)?.map { doubleArrayOf(plane.x(it), plane.y(it)) } ?: run {
            val minX = xy.minOf { it[0] }; val maxX = xy.maxOf { it[0] }
            val minY = xy.minOf { it[1] }; val maxY = xy.maxOf { it[1] }
            val pad = max(50.0, 0.1 * max(maxX - minX, maxY - minY))
            listOf(doubleArrayOf(minX - pad, minY - pad), doubleArrayOf(maxX + pad, minY - pad), doubleArrayOf(maxX + pad, maxY + pad), doubleArrayOf(minX - pad, maxY + pad))
        }

        val cells = ArrayList<Cell>()
        for ((i, p) in xy.withIndex()) {
            if (input.cancelled()) throw ToolCancelled()
            var cell = clip
            // Nearest neighbours first; once a neighbour is over twice the cell's
            // reach away, its bisector cannot cut the cell any more.
            val others = xy.indices.filter { it != i }.sortedBy { hypot(xy[it][0] - p[0], xy[it][1] - p[1]) }
            for (j in others) {
                val q = xy[j]
                val d = hypot(q[0] - p[0], q[1] - p[1])
                val reach = cell.maxOfOrNull { hypot(it[0] - p[0], it[1] - p[1]) } ?: 0.0
                if (d > 2 * reach) break
                cell = halfPlane(cell, p, q)
                if (cell.size < 3) break
            }
            if (cell.size < 3) continue
            val ring = cell.map { plane.toLngLat(it[0], it[1]) }
            val polygon = Geometry.Polygon.of(ring)
            cells += Cell(
                inside[i], polygon.exterior, polygon.areaSquareMeters(), valueKey?.let { inside[i].number(it) },
                thickKey?.let { inside[i].number(it) }?.takeIf { it >= 0 },
            )
        }
        return Result(cells, all.size - unique.size, unique.size - inside.size)
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        val r = cells(input)
        progress(1f)
        val valueKey = input.text(value.key)
        val valued = r.cells.filter { it.value != null }
        val ramp = valued.takeIf { it.isNotEmpty() }?.let { v -> ColorRamp(v.minOf { it.value!! }, v.maxOf { it.value!! }) }
        val out = r.cells.mapIndexed { n, c ->
            OutputFeature(FeatureKind.POLYGON, Geometry.Polygon(listOf(c.ring)), buildProperties {
                this[OutputProps.TOOL] = id
                this[Props.TITLE] = "${c.point.title ?: "点"} 的泰森多边形"
                this[Props.AREA_M2] = Math.round(c.areaM2 * 10) / 10.0
                this[SOURCE] = c.point.id
                if (valueKey != null && c.value != null) this[valueKey] = c.value
                c.volumeM3?.let { this[VOLUME_M3] = Math.round(it).toDouble() }
                val color = c.value?.let { ramp?.color(it) } ?: PALETTE[n % PALETTE.size]
                style(fill = color, fillOpacity = 0.35, stroke = "#37474F", strokeWidth = 1.0)
            })
        }
        val total = r.cells.sumOf { it.areaM2 }
        val lines = mutableListOf("生成 ${r.cells.size} 个泰森多边形，总面积 ${CoordinateFormatter.area(total)}。")
        if (valueKey != null) {
            if (valued.isEmpty()) lines += "没有点带「$valueKey」的数值。"
            else {
                val a = valued.sumOf { it.areaM2 }
                val weighted = valued.sumOf { it.value!! * it.areaM2 } / a
                val plain = valued.sumOf { it.value!! } / valued.size
                lines += "「$valueKey」按面积加权平均 ${fmt(weighted)}（${valued.size} 个点，算术平均 ${fmt(plain)}）。"
                if (valued.size < r.cells.size) lines += "${r.cells.size - valued.size} 块面的点没有这个值，不计入平均。"
            }
        }
        val thickKey = input.text(thickness.key)
        if (thickKey != null) {
            val withH = r.cells.filter { it.volumeM3 != null }
            if (withH.isEmpty()) lines += "没有点带「$thickKey」的数值，算不了体积。"
            else {
                val vol = withH.sumOf { it.volumeM3!! }
                val rho = input.number(density)
                lines += "体积 ${fmt(vol / 1e4, 2)} 万 m³（${withH.size} 块面有厚度，平均厚 ${fmt(vol / withH.sumOf { it.areaM2 })} m）" +
                    (rho?.let { "，矿石量 ${fmt(vol * it / 1e4, 2)} 万 t（体重 ${fmt(it)} t/m³）" } ?: "") + "。"
                val both = withH.filter { it.value != null }
                if (valueKey != null && both.isNotEmpty() && both.sumOf { it.volumeM3!! } > 0) {
                    lines += "「$valueKey」按体积（面积×厚度）加权平均 ${fmt(both.sumOf { it.value!! * it.volumeM3!! } / both.sumOf { it.volumeM3!! })}。"
                }
            }
        }
        if (r.duplicates > 0) lines += "${r.duplicates} 个点与别的点位置重合，已合并。"
        if (r.outside > 0) lines += "${r.outside} 个点在范围外，没有参与。"
        return ToolOutput(out, lines.joinToString("\n"), ramp?.legend { fmt(it) }.orEmpty())
    }

    companion object {
        /** The point a polygon belongs to, by record id. */
        const val SOURCE = "source_point"
        /** A cell's area times its point's thickness. */
        const val VOLUME_M3 = "volume_m3"
        private val PALETTE = listOf("#8DD3C7", "#FFFFB3", "#BEBADA", "#FB8072", "#80B1D3", "#FDB462", "#B3DE69", "#FCCDE5")

        /** Sutherland–Hodgman against the half-plane of points nearer [p] than [q]. */
        internal fun halfPlane(poly: List<DoubleArray>, p: DoubleArray, q: DoubleArray): List<DoubleArray> {
            val nx = q[0] - p[0]
            val ny = q[1] - p[1]
            val c = (nx * (p[0] + q[0]) + ny * (p[1] + q[1])) / 2
            fun side(v: DoubleArray) = nx * v[0] + ny * v[1] - c // <= 0 is p's side
            val out = ArrayList<DoubleArray>(poly.size + 1)
            for (k in poly.indices) {
                val a = poly[k]
                val b = poly[(k + 1) % poly.size]
                val sa = side(a)
                val sb = side(b)
                if (sa <= 0) out += a
                if ((sa < 0 && sb > 0) || (sa > 0 && sb < 0)) {
                    val t = sa / (sa - sb)
                    out += doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                }
            }
            return out
        }
    }
}
