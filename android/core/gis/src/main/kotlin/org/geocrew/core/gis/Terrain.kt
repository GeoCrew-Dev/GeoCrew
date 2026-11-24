package org.geocrew.core.gis

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.positions
import java.io.IOException
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Terrain of an area from the public DEM: contour lines (every fifth one an
 * index contour), or slope or aspect classes as coloured cells. Heights are
 * sampled on a regular grid over the chosen polygon; cells outside it are
 * left out, so the result follows the outline.
 */
class TerrainTool : GisTool {
    override val id = "terrain"
    override val name = "地形分析"
    override val description = "取公开 DEM 高程（约 30 m，需联网），在选定的面里画等高线，或按坡度、坡向分级着色，并给出最高、最低和平均坡度。适合踏勘前看地形、选路线，或给填图底图补等高线。"
    override val accepts = setOf(GeometryType.POLYGON)

    private val area = ToolParam.Feature("area", "范围", setOf(GeometryType.POLYGON), help = "画一个面圈出要看的区域")
    private val output = ToolParam.Choice(
        "output", "输出",
        listOf(
            ToolParam.Choice.Option(OUT_CONTOURS, "等高线"),
            ToolParam.Choice.Option(OUT_SLOPE, "坡度分级"),
            ToolParam.Choice.Option(OUT_ASPECT, "坡向"),
        ),
    )
    private val interval = ToolParam.Number("interval", "等高距", default = null, min = 1.0, max = 1000.0, unit = "m", help = "留空按高差自动选；每五条加粗一条计曲线")
    private val cell = ToolParam.Number("cell", "格距", default = null, min = 10.0, max = 2000.0, unit = "m", help = "留空自动选择，不小于 DEM 的约 30 m")
    override val params = listOf(area, output, interval, cell)

    /** Heights on grid nodes, NaN outside the area or where the DEM has none. */
    internal class Grid(val plane: LocalPlane, val minX: Double, val minY: Double, val size: Double, val z: Array<DoubleArray>) {
        val rows get() = z.size
        val cols get() = z[0].size

        /** Slope in degrees and downhill azimuth of the cell whose lower-left node is (r, c), or null without four heights. */
        fun gradient(r: Int, c: Int): Pair<Double, Double>? {
            val z00 = z[r][c]; val z01 = z[r][c + 1]; val z10 = z[r + 1][c]; val z11 = z[r + 1][c + 1]
            if (listOf(z00, z01, z10, z11).any { it.isNaN() }) return null
            val gx = ((z01 + z11) - (z00 + z10)) / (2 * size) // dz/dx, east
            val gy = ((z10 + z11) - (z00 + z01)) / (2 * size) // dz/dy, north
            val slope = atan(sqrt(gx * gx + gy * gy)) * 180 / PI
            val aspect = (atan2(-gx, -gy) * 180 / PI + 360) % 360
            return slope to aspect
        }
    }

    override fun check(input: ToolInput): String? {
        val a = input.feature(area) ?: return "请选择一个面作为范围"
        if (a.geometry!!.areaSquareMeters() <= 0) return "范围面积为 0"
        if (input.elevation == null) return "这里取不到 DEM 高程"
        return null
    }

    internal fun grid(input: ToolInput, progress: (Float) -> Unit = {}): Grid {
        check(input)?.let { throw IllegalArgumentException(it) }
        val a = input.feature(area)!!
        val g = a.geometry!!
        val pos = g.positions()
        val plane = LocalPlane(pos.first())
        val xs = pos.map(plane::x); val ys = pos.map(plane::y)
        val minX = xs.min(); val minY = ys.min()
        val w = xs.max() - minX; val h = ys.max() - minY
        var size = input.number(cell) ?: max(MIN_CELL, sqrt(w * h / AUTO_CELLS))
        if ((ceil(w / size) + 1) * (ceil(h / size) + 1) > MAX_NODES) size = sqrt(w * h / MAX_NODES) * 1.05
        val cols = ceil(w / size).toInt().coerceAtLeast(1) + 1
        val rows = ceil(h / size).toInt().coerceAtLeast(1) + 1
        // A node one cell outside the outline still counts, so cells along the edge get all four corners.
        val reach = size * 1.5
        val nodes = ArrayList<Pair<Int, LngLat>>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val p = plane.toLngLat(minX + c * size, minY + r * size)
            if (ZonalTool.contains(g, p) || near(g, plane, minX + c * size, minY + r * size, reach)) nodes += (r * cols + c) to p
        }
        if (input.cancelled()) throw ToolCancelled()
        val z = Array(rows) { DoubleArray(cols) { Double.NaN } }
        nodes.chunked(CHUNK).forEachIndexed { i, chunk ->
            if (input.cancelled()) throw ToolCancelled()
            val hs = try {
                input.elevation!!.at(chunk.map { it.second })
            } catch (e: IOException) {
                throw IllegalArgumentException("取 DEM 高程失败：${e.message ?: "网络不通"}")
            }
            chunk.forEachIndexed { k, (id, _) -> hs.getOrNull(k)?.let { z[id / cols][id % cols] = it } }
            progress(((i + 1f) * CHUNK / nodes.size.coerceAtLeast(1)).coerceAtMost(1f))
        }
        if (z.all { row -> row.all { it.isNaN() } }) throw IllegalArgumentException("这一带没有地形数据")
        return Grid(plane, minX, minY, size, z)
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        val grid = grid(input) { progress(it * 0.8f) }
        val a = input.feature(area)!!
        val name = a.title ?: "范围"
        val heights = grid.z.flatMap { r -> r.filter { !it.isNaN() } }
        val slopes = ArrayList<Pair<Int, Int>>().also { cells ->
            for (r in 0 until grid.rows - 1) for (c in 0 until grid.cols - 1) {
                val centre = grid.plane.toLngLat(grid.minX + (c + 0.5) * grid.size, grid.minY + (r + 0.5) * grid.size)
                if (ZonalTool.contains(a.geometry!!, centre) && grid.gradient(r, c) != null) cells += r to c
            }
        }
        val meanSlope = slopes.map { (r, c) -> grid.gradient(r, c)!!.first }.average().takeIf { !it.isNaN() }
        val facts = "「$name」高程 ${fmt(heights.min())}–${fmt(heights.max())} m，平均 ${fmt(heights.average())} m" +
            (meanSlope?.let { "，平均坡度 ${fmt(it)}°" } ?: "") + "；格距 ${fmt(grid.size)} m。"
        return when (input.choice(output)) {
            OUT_SLOPE -> classes(grid, slopes, SLOPE_CLASSES, facts) { it.first }
            OUT_ASPECT -> classes(grid, slopes, ASPECT_CLASSES, facts) { (s, az) -> if (s < FLAT) -1.0 else az }
            else -> contours(grid, input.number(interval), facts)
        }.also { progress(1f) }
    }

    private fun contours(grid: Grid, interval: Double?, facts: String): ToolOutput {
        // Contours.features puts value i at cell centre i + 0.5; nodes sit on the grid corners.
        val lines = Contours.features(grid.z, grid.plane, grid.minX - grid.size / 2, grid.minY - grid.size / 2, grid.size, id, "高程", interval)
            ?: return ToolOutput(emptyList(), facts + "高差太小，画不出等高线。")
        val (features, levels) = lines
        val step = levels.zipWithNext { x, y -> y - x }.minOrNull() ?: 0.0
        val styled = features.map { f ->
            val level = (f.properties[OutputProps.VALUE] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val index = step > 0 && abs(level / (step * 5) - (level / (step * 5)).roundToInt()) < 1e-6
            OutputFeature(
                f.kind, f.geometry,
                JsonObject(
                    f.properties + mapOf(
                        Props.TITLE to JsonPrimitive("等高线 ${Contours.label(level)} m"),
                        OutputProps.STROKE to JsonPrimitive("#8D6E63"),
                        OutputProps.STROKE_WIDTH to JsonPrimitive(if (index) 2.2 else 1.0),
                        INDEX to JsonPrimitive(index),
                    ),
                ),
            )
        }
        return ToolOutput(styled, facts + "等高线 ${styled.size} 段，等高距 ${Contours.label(step)} m，${Contours.label(levels.first())}–${Contours.label(levels.last())} m，粗线为计曲线。")
    }

    private fun classes(grid: Grid, cells: List<Pair<Int, Int>>, classes: List<Class>, facts: String, value: (Pair<Double, Double>) -> Double): ToolOutput {
        val counts = LinkedHashMap<String, Int>()
        val cellArea = grid.size * grid.size
        val out = cells.mapNotNull { (r, c) ->
            val v = value(grid.gradient(r, c)!!)
            val k = classes.indexOfFirst { v >= it.from && v < it.to }.takeIf { it >= 0 } ?: return@mapNotNull null
            counts.merge(classes[k].label, 1, Int::plus)
            val x0 = grid.minX + c * grid.size; val y0 = grid.minY + r * grid.size
            val ring = listOf(
                grid.plane.toLngLat(x0, y0), grid.plane.toLngLat(x0 + grid.size, y0),
                grid.plane.toLngLat(x0 + grid.size, y0 + grid.size), grid.plane.toLngLat(x0, y0 + grid.size),
            )
            OutputFeature(FeatureKind.POLYGON, Geometry.Polygon.of(ring), buildProperties {
                this[OutputProps.TOOL] = id
                this[OutputProps.VALUE] = round1(v)
                this[CLASS] = classes[k].label
                this[Props.TITLE] = classes[k].label
                style(fill = classes[k].color, fillOpacity = 0.5, stroke = classes[k].color, strokeWidth = 0.0)
            })
        }
        val total = counts.values.sum().coerceAtLeast(1)
        val share = counts.entries.sortedByDescending { it.value }.take(3)
            .joinToString("，") { (label, n) -> "$label ${fmt(n * 100.0 / total)}%（${fmt1(n * cellArea / 1e6)} km²）" }
        return ToolOutput(out, facts + "面积最多的是：$share。", classes.distinctBy { it.label }.map { LegendEntry(it.color, it.label) })
    }

    internal data class Class(val from: Double, val to: Double, val label: String, val color: String)

    companion object {
        const val OUT_CONTOURS = "contours"
        const val OUT_SLOPE = "slope"
        const val OUT_ASPECT = "aspect"
        /** On contours: true for index (every fifth) contours. */
        const val INDEX = "index_contour"
        /** On slope or aspect cells: the class label. */
        const val CLASS = "class"

        private const val MIN_CELL = 30.0
        private const val AUTO_CELLS = 6_000.0
        private const val MAX_NODES = 12_000.0
        private const val CHUNK = 500
        private const val FLAT = 2.0

        internal val SLOPE_CLASSES = listOf(
            Class(0.0, 5.0, "0–5° 平缓", "#1A9850"),
            Class(5.0, 15.0, "5–15° 缓坡", "#91CF60"),
            Class(15.0, 25.0, "15–25° 斜坡", "#D9EF8B"),
            Class(25.0, 35.0, "25–35° 陡坡", "#FEE08B"),
            Class(35.0, 45.0, "35–45° 急坡", "#FC8D59"),
            Class(45.0, 90.01, "45° 以上 险坡", "#D73027"),
        )

        internal val ASPECT_CLASSES = listOf(Class(-1.0, 0.0, "平地", "#BDBDBD")) +
            listOf("北" to "#E53935", "东北" to "#FB8C00", "东" to "#FDD835", "东南" to "#7CB342", "南" to "#00ACC1", "西南" to "#1E88E5", "西" to "#5E35B1", "西北" to "#D81B60")
                .flatMapIndexed { i, (n, color) ->
                    val from = i * 45.0 - 22.5
                    if (i == 0) listOf(Class(337.5, 360.01, "朝$n", color), Class(0.0, 22.5, "朝$n", color))
                    else listOf(Class(from, from + 45, "朝$n", color))
                }

        private fun near(g: Geometry, plane: LocalPlane, x: Double, y: Double, reach: Double): Boolean =
            listOf(reach to 0.0, -reach to 0.0, 0.0 to reach, 0.0 to -reach).any { (dx, dy) -> ZonalTool.contains(g, plane.toLngLat(x + dx, y + dy)) }

        private fun fmt(v: Double) = String.format(Locale.ROOT, "%.0f", v)
        private fun fmt1(v: Double) = String.format(Locale.ROOT, "%.2f", v)
        private fun round1(v: Double) = Math.round(v * 10) / 10.0
    }
}
