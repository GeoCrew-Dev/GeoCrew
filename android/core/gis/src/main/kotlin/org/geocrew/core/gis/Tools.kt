package org.geocrew.core.gis

import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.lengthMeters
import org.geocrew.core.model.positions
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Inverse distance weighting: estimates a numeric property (a grade, a
 * thickness, a reading) on a regular grid from sample points.
 */
class IdwTool : GisTool {
    override val id = "idw"
    override val name = "反距离加权插值"
    override val description = "用采样点的数值属性估算整片区域，输出带颜色的网格面。适合品位、厚度、测量读数等。"
    override val accepts = setOf(GeometryType.POINT)

    private val field = ToolParam.Field("field", "插值属性", numeric = true)
    private val cell = ToolParam.Number("cell", "网格大小", default = null, min = 1.0, max = 10_000.0, unit = "m", help = "留空自动选择")
    private val power = ToolParam.Number("power", "距离幂次", default = 2.0, min = 0.5, max = 5.0, help = "越大越偏向最近的点")
    private val radius = ToolParam.Number("radius", "搜索半径", default = null, min = 1.0, unit = "m", help = "留空使用全部点")
    override val params = listOf(field, cell, power, radius)

    private data class Sample(val x: Double, val y: Double, val v: Double)

    override fun check(input: ToolInput): String? {
        val key = input.text(field.key) ?: return "请选择插值属性"
        val n = input.of(accepts).count { it.number(key) != null }
        return if (n < 3) "至少需要 3 个带「$key」数值的点，现在有 $n 个" else null
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        check(input)?.let { throw IllegalArgumentException(it) }
        val key = input.text(field.key)!!
        val points = input.of(accepts).flatMap { f -> f.number(key)?.let { v -> f.geometry!!.positions().map { it to v } }.orEmpty() }
        val plane = LocalPlane(points.first().first)
        val samples = points.map { (p, v) -> Sample(plane.x(p), plane.y(p), v) }

        var minX = samples.minOf { it.x }; var maxX = samples.maxOf { it.x }
        var minY = samples.minOf { it.y }; var maxY = samples.maxOf { it.y }
        val pad = max(maxX - minX, maxY - minY) * 0.05 + 1.0
        minX -= pad; maxX += pad; minY -= pad; maxY += pad
        val w = maxX - minX
        val h = maxY - minY

        var size = input.number(cell) ?: (max(w, h) / AUTO_CELLS)
        // Keep the grid small enough for a phone and a map layer.
        if (ceil(w / size) * ceil(h / size) > MAX_CELLS) size = sqrt(w * h / MAX_CELLS)
        val cols = ceil(w / size).toInt().coerceAtLeast(1)
        val rows = ceil(h / size).toInt().coerceAtLeast(1)
        val p = input.number(power) ?: 2.0
        val r = input.number(radius)

        val values = Array(rows) { DoubleArray(cols) { Double.NaN } }
        for (row in 0 until rows) {
            if (input.cancelled()) throw ToolCancelled()
            for (col in 0 until cols) {
                val cx = minX + (col + 0.5) * size
                val cy = minY + (row + 0.5) * size
                values[row][col] = estimate(samples, cx, cy, p, r)
            }
            progress((row + 1f) / rows)
        }

        val all = values.flatMap { it.filter(Double::isFinite) }
        if (all.isEmpty()) return ToolOutput(emptyList(), "搜索半径内没有采样点，没有生成网格。")
        val lo = all.min(); val hi = all.max()
        val ramp = ColorRamp(lo, hi)
        val out = ArrayList<OutputFeature>(all.size)
        for (row in 0 until rows) for (col in 0 until cols) {
            val v = values[row][col]
            if (!v.isFinite()) continue
            val x0 = minX + col * size; val y0 = minY + row * size
            val ring = listOf(
                plane.toLngLat(x0, y0), plane.toLngLat(x0 + size, y0),
                plane.toLngLat(x0 + size, y0 + size), plane.toLngLat(x0, y0 + size),
            )
            out += OutputFeature(
                FeatureKind.POLYGON,
                Geometry.Polygon.of(ring),
                buildProperties {
                    this[OutputProps.TOOL] = id
                    this[OutputProps.VALUE] = v
                    this[Props.TITLE] = "$key ≈ ${fmt(v)}"
                    style(fill = ramp.color(v), fillOpacity = 0.55, stroke = ramp.color(v), strokeWidth = 0.0)
                },
            )
        }
        return ToolOutput(
            out,
            "由 ${samples.size} 个点插值得到 ${cols}×${rows} 网格（格距 ${fmt(size, 1)} m），「$key」范围 ${fmt(lo)} – ${fmt(hi)}。",
            ramp.legend { fmt(it) },
        )
    }

    private fun estimate(samples: List<Sample>, x: Double, y: Double, p: Double, radius: Double?): Double {
        var num = 0.0
        var den = 0.0
        for (s in samples) {
            val d = hypot(s.x - x, s.y - y)
            if (radius != null && d > radius) continue
            if (d < 1e-9) return s.v
            val wgt = 1.0 / d.pow(p)
            num += wgt * s.v
            den += wgt
        }
        return if (den == 0.0) Double.NaN else num / den
    }

    private companion object {
        const val AUTO_CELLS = 40.0
        const val MAX_CELLS = 6_400.0
    }
}

/** Counts, lengths, areas and the distribution of a numeric property. */
class StatisticsTool : GisTool {
    override val id = "stats"
    override val name = "统计"
    override val description = "统计记录数量、线总长、面总面积，以及某个数值属性的最小值、最大值、平均值和标准差。"
    override val accepts = GeometryType.entries.toSet()
    private val field = ToolParam.Field("field", "数值属性（可选）", numeric = true)
    override val params = listOf(field)

    override fun check(input: ToolInput): String? = if (input.of(accepts).isEmpty()) "没有可统计的记录" else null

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        val fs = input.of(accepts)
        val byType = fs.groupBy { GeometryType.of(it.geometry) }
        val lines = buildList {
            add("共 ${fs.size} 条记录：点 ${byType[GeometryType.POINT]?.size ?: 0}，线 ${byType[GeometryType.LINE]?.size ?: 0}，面 ${byType[GeometryType.POLYGON]?.size ?: 0}")
            byType[GeometryType.LINE]?.let { l -> add("线总长 ${CoordinateFormatter.distance(l.sumOf { it.geometry!!.lengthMeters() })}") }
            byType[GeometryType.POLYGON]?.let { p -> add("面总面积 ${CoordinateFormatter.area(p.sumOf { it.geometry!!.areaSquareMeters() })}") }
            input.text(field.key)?.let { key ->
                val v = fs.mapNotNull { it.number(key) }
                if (v.isEmpty()) add("没有记录带「$key」数值") else {
                    val mean = v.average()
                    val sd = if (v.size > 1) sqrt(v.sumOf { (it - mean) * (it - mean) } / (v.size - 1)) else 0.0
                    add("「$key」${v.size} 个值：最小 ${fmt(v.min())}，最大 ${fmt(v.max())}，平均 ${fmt(mean)}，标准差 ${fmt(sd)}")
                }
            }
        }
        progress(1f)
        return ToolOutput(emptyList(), lines.joinToString("\n"))
    }
}

/** The smallest convex polygon around the input, e.g. the extent of a sampling campaign. */
class ConvexHullTool : GisTool {
    override val id = "hull"
    override val name = "凸包范围"
    override val description = "生成包住所有选中记录的最小凸多边形，可用来圈定采样或调查范围。"
    override val accepts = GeometryType.entries.toSet()
    override val params = emptyList<ToolParam>()

    override fun check(input: ToolInput): String? =
        if (hull(input.of(accepts)).size < 3) "至少需要 3 个不在一条直线上的位置" else null

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        val ring = hull(input.of(accepts))
        require(ring.size >= 3) { "至少需要 3 个不在一条直线上的位置" }
        val polygon = Geometry.Polygon.of(ring)
        val area = polygon.areaSquareMeters()
        progress(1f)
        return ToolOutput(
            listOf(
                OutputFeature(FeatureKind.POLYGON, polygon, buildProperties {
                    this[OutputProps.TOOL] = id
                    this[Props.TITLE] = "范围（凸包）"
                    this[Props.AREA_M2] = area
                    style(fill = "#FF9800", fillOpacity = 0.15, stroke = "#E65100", strokeWidth = 2.0)
                }),
            ),
            "范围面积 ${CoordinateFormatter.area(area)}，周长 ${CoordinateFormatter.distance(Geodesy.length(polygon.exterior))}。",
        )
    }

    /** Andrew's monotone chain on a local plane; returns the hull counter-clockwise, open. */
    internal fun hull(features: List<GeoFeature>): List<LngLat> {
        val pts = features.flatMap { it.geometry?.positions().orEmpty() }.distinctBy { it.lng to it.lat }
        if (pts.size < 3) return pts
        val plane = LocalPlane(pts.first())
        val sorted = pts.map { Triple(plane.x(it), plane.y(it), it) }.sortedWith(compareBy({ it.first }, { it.second }))
        fun cross(o: Triple<Double, Double, LngLat>, a: Triple<Double, Double, LngLat>, b: Triple<Double, Double, LngLat>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = ArrayList<Triple<Double, Double, LngLat>>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<Triple<Double, Double, LngLat>>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        val ring = (lower.dropLast(1) + upper.dropLast(1)).map { it.third }
        return if (ring.size >= 3) ring else emptyList()
    }
}

/** Circles of a given radius around points, e.g. a safety distance or a sampling neighbourhood. */
class BufferTool : GisTool {
    override val id = "buffer"
    override val name = "点缓冲区"
    override val description = "在每个点周围生成指定半径的圆形区域。"
    override val accepts = setOf(GeometryType.POINT)
    private val radius = ToolParam.Number("radius", "半径", default = 50.0, min = 1.0, max = 100_000.0, unit = "m")
    override val params = listOf(radius)

    override fun check(input: ToolInput): String? = if (input.of(accepts).isEmpty()) "没有选中点" else null

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        val r = input.number(radius) ?: 50.0
        val pts = input.of(accepts)
        val out = pts.flatMapIndexed { i, f ->
            if (input.cancelled()) throw ToolCancelled()
            progress((i + 1f) / pts.size)
            f.geometry!!.positions().map { c ->
                OutputFeature(FeatureKind.POLYGON, Geometry.Polygon.of(circle(c, r)), buildProperties {
                    this[OutputProps.TOOL] = id
                    this[Props.TITLE] = "${f.title ?: "点"} 周围 ${fmt(r, 0)} m"
                    style(fill = "#2196F3", fillOpacity = 0.15, stroke = "#1565C0", strokeWidth = 1.5)
                })
            }
        }
        return ToolOutput(out, "生成 ${out.size} 个半径 ${fmt(r, 0)} m 的缓冲区。")
    }

    private fun circle(c: LngLat, r: Double, steps: Int = 48) =
        (0 until steps).map { Geodesy.destination(c, it * 360.0 / steps, r) }
}
