package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.positions
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Kernel density (核密度): how many points, or how much of a weight such as
 * an ore count, there are per square kilometre around each place, e.g. to
 * see where mineral occurrences or springs cluster. Each point spreads over
 * a quartic (biweight) kernel of radius h, the kernel desktop GIS uses, so
 * the grid integrates to the total weight. Without a radius, h comes from
 * the spatial form of Silverman's rule:
 * h = 0.9 · min(SD, √(1/ln 2) · Dm) · n^−0.2, with SD the standard distance
 * and Dm the median distance to the mean centre.
 */
class DensityTool : GisTool {
    override val id = "density"
    override val name = "核密度"
    override val description = "看点在哪里扎堆：估算每平方公里有多少个点（或多少权重），输出带颜色的密度网格和等值线。适合矿化点、泉点、样品分布。"
    override val accepts = setOf(GeometryType.POINT)

    private val weight = ToolParam.Field("weight", "权重属性（可选）", numeric = true, help = "留空时每个点算 1 个")
    private val radius = ToolParam.Number("radius", "搜索半径", default = null, min = 1.0, max = 100_000.0, unit = "m", help = "留空按点的分布自动选择")
    private val cell = ToolParam.Number("cell", "网格大小", default = null, min = 1.0, max = 10_000.0, unit = "m", help = "留空自动选择")
    private val contour = ToolParam.Number("contour", "等值线间距", default = null, min = 0.0, unit = "个/km²", help = "留空自动分档，填 0 不画等值线")
    override val params = listOf(weight, radius, cell, contour)

    private data class Pt(val x: Double, val y: Double, val w: Double)

    override fun check(input: ToolInput): String? {
        val key = input.text(weight.key)
        val pts = input.of(accepts)
        val n = if (key == null) pts.size else pts.count { (it.number(key) ?: 0.0) > 0 }
        if (n < 2) return if (key == null) "至少需要 2 个点，现在有 $n 个" else "至少需要 2 个「$key」大于 0 的点，现在有 $n 个"
        return null
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        check(input)?.let { throw IllegalArgumentException(it) }
        val key = input.text(weight.key)
        val raw = input.of(accepts).flatMap { f ->
            val w = if (key == null) 1.0 else f.number(key)?.takeIf { it > 0 } ?: 0.0
            if (w <= 0) emptyList() else f.geometry!!.positions().map { it to w }
        }
        val plane = LocalPlane(raw.first().first)
        val pts = raw.map { (p, w) -> Pt(plane.x(p), plane.y(p), w) }
        val total = pts.sumOf { it.w }

        val h = input.number(radius) ?: autoRadius(pts)
        require(h > 0) { "所有点都在同一位置，无法自动选择搜索半径，请手动填写" }

        val minX = pts.minOf { it.x } - h; val maxX = pts.maxOf { it.x } + h
        val minY = pts.minOf { it.y } - h; val maxY = pts.maxOf { it.y } + h
        val w = maxX - minX
        val ht = maxY - minY
        var size = input.number(cell) ?: min(max(w, ht) / AUTO_CELLS, h / 3)
        if (ceil(w / size) * ceil(ht / size) > MAX_CELLS) size = sqrt(w * ht / MAX_CELLS)
        val cols = ceil(w / size).toInt().coerceAtLeast(1)
        val rows = ceil(ht / size).toInt().coerceAtLeast(1)

        // Quartic kernel scaled so it integrates to 1, then per km².
        val norm = 3.0 / (PI * h * h) * 1e6
        val values = Array(rows) { DoubleArray(cols) }
        for (row in 0 until rows) {
            if (input.cancelled()) throw ToolCancelled()
            val cy = minY + (row + 0.5) * size
            for (col in 0 until cols) {
                val cx = minX + (col + 0.5) * size
                var s = 0.0
                for (p in pts) {
                    val dx = p.x - cx; val dy = p.y - cy
                    val u = (dx * dx + dy * dy) / (h * h)
                    if (u < 1) s += p.w * (1 - u).pow(2)
                }
                values[row][col] = s * norm
            }
            progress((row + 1f) / rows)
        }

        val hi = values.maxOf { r -> r.max() }
        // Leave out the near-empty fringe so the layer shows where points are, not a square.
        val floor = hi * FRINGE
        val ramp = ColorRamp(floor, hi)
        val unit = if (key == null) "个/km²" else "/km²"
        val label = if (key == null) "点密度" else "「$key」密度"
        val out = ArrayList<OutputFeature>()
        for (row in 0 until rows) for (col in 0 until cols) {
            val v = values[row][col]
            if (v <= floor) continue
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
                    this[Props.TITLE] = "$label ≈ ${fmt(v)} $unit"
                    style(fill = ramp.color(v), fillOpacity = 0.55, stroke = ramp.color(v), strokeWidth = 0.0)
                },
            )
        }
        val lines = Contours.features(values, plane, minX, minY, size, id, label, input.number(contour))
        lines?.let { out += it.first }
        val what = if (key == null) "${pts.size} 个点" else "${pts.size} 个点（「$key」合计 ${fmt(total)}）"
        return ToolOutput(
            out,
            "由 $what 估算，搜索半径 ${fmt(h, 0)} m，网格 ${cols}×${rows}（格距 ${fmt(size, 1)} m），最高 ${fmt(hi)} $unit。" +
                lines?.let { (_, lv) -> "等值线 ${lv.size} 条。" }.orEmpty(),
            ramp.legend { fmt(it) },
        )
    }

    private fun autoRadius(pts: List<Pt>): Double {
        val sw = pts.sumOf { it.w }
        val mx = pts.sumOf { it.x * it.w } / sw
        val my = pts.sumOf { it.y * it.w } / sw
        val sd = sqrt(pts.sumOf { it.w * ((it.x - mx).pow(2) + (it.y - my).pow(2)) } / sw)
        val d = pts.map { sqrt((it.x - mx).pow(2) + (it.y - my).pow(2)) }.sorted()
        val dm = if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
        val spread = min(sd, sqrt(1 / ln(2.0)) * dm).takeIf { it > 0 } ?: sd
        return 0.9 * spread * pts.size.toDouble().pow(-0.2)
    }

    private companion object {
        const val AUTO_CELLS = 50.0
        const val MAX_CELLS = 6_400.0
        const val FRINGE = 0.02
    }
}
