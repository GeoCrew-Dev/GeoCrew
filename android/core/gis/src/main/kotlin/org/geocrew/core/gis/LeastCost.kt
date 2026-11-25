package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Hiking
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.buildProperties
import java.io.IOException
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The quickest way on foot between two points across open country: heights
 * from the public DEM on a grid over a corridor around the two points, then
 * the path of least walking time by Tobler's hiking function (direction
 * matters: uphill and downhill cost differently), with ground steeper than
 * a chosen slope treated as impassable. Roads and paths are not known, so
 * this answers "which side of the ridge", not "which track".
 */
class LeastCostTool : GisTool {
    override val id = "least_cost"
    override val name = "最省时路线"
    override val description = "在两个点之间取公开 DEM 高程（需联网），按 Tobler 徒步公式找走起来最省时间的路线，绕开太陡的坡，并和直线走过去比较用时、爬升。适合在没有路的山里选上山、翻梁的方向。"
    override val accepts = setOf(GeometryType.POINT)

    private val from = ToolParam.Feature("from", "起点", setOf(GeometryType.POINT), help = "如驻地、停车点或现在所在的记录点")
    private val to = ToolParam.Feature("to", "终点", setOf(GeometryType.POINT), help = "要去的露头、采样点或山头")
    private val steep = ToolParam.Number("steep", "最大坡度", default = 35.0, min = 10.0, max = 75.0, unit = "°", help = "比这更陡的地方当作走不了")
    private val ground = ToolParam.Choice(
        "ground", "路况",
        listOf(
            ToolParam.Choice.Option(OFF, "无路（灌丛、乱石，按 0.6 倍速度）"),
            ToolParam.Choice.Option(PATH, "好走（草坡、林间空地）"),
        ),
    )
    private val pace = ToolParam.Number("pace", "速度系数", default = 1.0, min = 0.2, max = 2.0, help = "负重或队伍慢时调小，例如 0.8")
    override val params = listOf(from, to, steep, ground, pace)

    override fun check(input: ToolInput): String? {
        val a = input.feature(from) ?: return "请选择起点"
        val b = input.feature(to) ?: return "请选择终点"
        if (a.id == b.id) return "起点和终点是同一个点"
        if (input.elevation == null) return "这里取不到 DEM 高程"
        val d = Geodesy.distance(point(a.geometry), point(b.geometry))
        return when {
            d < MIN_DISTANCE -> "两点只相距 ${d.roundToInt()} m，直接走过去就好"
            d > MAX_DISTANCE -> "两点相距 ${fmt(d / 1000, 1)} km，超过 ${(MAX_DISTANCE / 1000).toInt()} km；请分段计算"
            else -> null
        }
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        check(input)?.let { throw IllegalArgumentException(it) }
        val a = point(input.feature(from)!!.geometry)
        val b = point(input.feature(to)!!.geometry)
        val factor = (input.number(pace) ?: 1.0) * if (input.choice(ground) == OFF) Hiking.OFF_PATH else 1.0
        val maxSlope = input.number(steep) ?: 35.0

        val plane = LocalPlane(a)
        val ax = plane.x(a); val ay = plane.y(a); val bx = plane.x(b); val by = plane.y(b)
        val straight = hypot(bx - ax, by - ay)
        val pad = max(MIN_PAD, straight * PAD_SHARE)
        val minX = minOf(ax, bx) - pad; val minY = minOf(ay, by) - pad
        val w = abs(bx - ax) + 2 * pad; val h = abs(by - ay) + 2 * pad
        val size = max(Hiking.STEP_M, sqrt(w * h / MAX_NODES))
        val cols = ceil(w / size).toInt() + 1
        val rows = ceil(h / size).toInt() + 1

        val nodes = (0 until rows * cols).map { i -> plane.toLngLat(minX + (i % cols) * size, minY + (i / cols) * size) }
        val z = DoubleArray(rows * cols) { Double.NaN }
        val chunks = nodes.chunked(CHUNK)
        chunks.forEachIndexed { k, chunk ->
            if (input.cancelled()) throw ToolCancelled()
            val hs = try {
                input.elevation!!.at(chunk)
            } catch (e: IOException) {
                throw IllegalArgumentException("取 DEM 高程失败：${e.message ?: "网络不通"}")
            }
            hs.forEachIndexed { j, v -> if (v != null) z[k * CHUNK + j] = v }
            progress(0.7f * (k + 1) / chunks.size)
        }
        if (z.all { it.isNaN() }) throw IllegalArgumentException("这一带没有 DEM 高程")

        fun nodeOf(x: Double, y: Double): Int {
            val c = ((x - minX) / size).roundToInt().coerceIn(0, cols - 1)
            val r = ((y - minY) / size).roundToInt().coerceIn(0, rows - 1)
            return r * cols + c
        }
        val start = nodeOf(ax, ay)
        val goal = nodeOf(bx, by)
        val path = LeastCost.search(z, cols, rows, size, start, goal, maxSlope, factor, input.cancelled)
            ?: throw IllegalArgumentException("在两点周围找不到坡度都不超过 ${fmt(maxSlope, 0)}° 的路线，可以把最大坡度调大一些再试")
        progress(0.9f)

        val turns = LeastCost.corners(path, cols)
        val line = listOf(a) + turns.map { nodes[it] } + listOf(b)
        val pts = Hiking.densify(line)
        val hs = try { pts.chunked(CHUNK).flatMap { input.elevation!!.at(it) } } catch (e: IOException) { null }
        val best = hs?.let { Hiking.estimate(pts, it, factor) } ?: Hiking.estimate(turns.map { nodes[it] }, turns.map { z[it].takeIf(Double::isFinite) }, factor)
        val direct = Hiking.densify(listOf(a, b)).let { d ->
            try { Hiking.estimate(d, d.chunked(CHUNK).flatMap { input.elevation!!.at(it) }, factor) } catch (e: IOException) { null }
        }
        val directSteep = direct?.let { steepest(Hiking.densify(listOf(a, b)), input) }

        val summary = buildString {
            append("最省时路线 ${fmt(best.distance / 1000, 2)} km，爬升 ${fmt(best.ascent, 0)} m、下降 ${fmt(best.descent, 0)} m，约${Hiking.format(best.hours)}。")
            if (direct != null) {
                append("\n直线走过去 ${fmt(direct.distance / 1000, 2)} km，爬升 ${fmt(direct.ascent, 0)} m，约${Hiking.format(direct.hours)}")
                if (directSteep != null && directSteep > maxSlope) append("，而且要过 ${fmt(directSteep, 0)}° 的陡坡")
                append("。")
            }
            append("\n按约 ${fmt(size, 0)} m 格距的 DEM 和 Tobler 公式估算，避开了陡于 ${fmt(maxSlope, 0)}° 的地方；不知道哪里有路、河、崖和植被，以实地为准。")
        }
        return ToolOutput(
            listOf(
                OutputFeature(
                    FeatureKind.LINE,
                    Geometry.LineString(line),
                    buildProperties {
                        this[OutputProps.TOOL] = id
                        this[Props.TITLE] = "最省时路线 · ${Hiking.format(best.hours)}"
                        this[Props.NOTE] = summary.lineSequence().first()
                        this[HikeTool.HOURS] = best.hours
                        this[HikeTool.ASCENT] = best.ascent
                        this[HikeTool.DESCENT] = best.descent
                        style(stroke = "#ef6c00", strokeWidth = 3.5)
                    },
                ),
            ),
            summary,
        )
    }

    /** Steepest slope in degrees between consecutive points of a straight line, from the DEM. */
    private fun steepest(pts: List<LngLat>, input: ToolInput): Double? {
        val hs = try { pts.chunked(CHUNK).flatMap { input.elevation!!.at(it) } } catch (e: IOException) { return null }
        var worst: Double? = null
        for (i in 1 until pts.size) {
            val z0 = hs[i - 1] ?: continue
            val z1 = hs[i] ?: continue
            val d = Geodesy.distance(pts[i - 1], pts[i]).takeIf { it > 0 } ?: continue
            val s = atan(abs(z1 - z0) / d) * 180 / PI
            if (worst == null || s > worst) worst = s
        }
        return worst
    }

    private fun point(g: Geometry?) = (g as Geometry.Point).position

    companion object {
        private const val PATH = "path"
        private const val OFF = "off"
        private const val CHUNK = 500
        private const val MAX_NODES = 40_000.0
        private const val MIN_DISTANCE = 60.0
        private const val MAX_DISTANCE = 20_000.0
        private const val MIN_PAD = 300.0
        private const val PAD_SHARE = 0.35
    }
}

/** Least walking time over a height grid; nodes are numbered row by row from the south-west. */
internal object LeastCost {
    private val STEPS = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)

    /**
     * Dijkstra over the 8-connected grid: a step costs Tobler's walking time
     * for its rise over its run, so uphill and downhill differ. Steps steeper
     * than [maxSlope] degrees, or onto nodes without a height, are not taken.
     * Returns the node indices from [start] to [goal], or null if [goal] can't be reached.
     */
    fun search(
        z: DoubleArray, cols: Int, rows: Int, size: Double, start: Int, goal: Int,
        maxSlope: Double, factor: Double, cancelled: () -> Boolean = { false },
    ): List<Int>? {
        val n = rows * cols
        val time = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val prev = IntArray(n) { -1 }
        val done = BooleanArray(n)
        val limit = kotlin.math.tan(maxSlope * PI / 180)
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        val z0 = z.copyOf().also { g -> if (g[start].isNaN()) g[start] = nearest(z, start) }
        if (z0[goal].isNaN()) z0[goal] = nearest(z, goal)
        time[start] = 0.0
        queue += 0.0 to start
        var visited = 0
        while (queue.isNotEmpty()) {
            val (t, u) = queue.poll()
            if (done[u]) continue
            done[u] = true
            if (u == goal) break
            if (++visited % 2000 == 0 && cancelled()) throw ToolCancelled()
            val r = u / cols; val c = u % cols
            for ((dr, dc) in STEPS) {
                val rr = r + dr; val cc = c + dc
                if (rr !in 0 until rows || cc !in 0 until cols) continue
                val v = rr * cols + cc
                if (done[v] || z0[v].isNaN()) continue
                val run = if (dr != 0 && dc != 0) size * SQRT2 else size
                val slope = (z0[v] - z0[u]) / run
                if (abs(slope) > limit) continue
                val nt = t + run / 1000 / (Hiking.speedKmh(slope) * factor)
                if (nt < time[v]) {
                    time[v] = nt
                    prev[v] = u
                    queue += nt to v
                }
            }
        }
        if (!done[goal]) return null
        val path = ArrayList<Int>()
        var at = goal
        while (at != -1) { path += at; at = prev[at] }
        return path.reversed()
    }

    /** The path's first and last nodes and every node where it changes direction. */
    fun corners(path: List<Int>, cols: Int): List<Int> {
        if (path.size < 3) return path
        val out = arrayListOf(path.first())
        fun step(a: Int, b: Int) = (b / cols - a / cols) to (b % cols - a % cols)
        for (i in 1 until path.size - 1) if (step(path[i - 1], path[i]) != step(path[i], path[i + 1])) out += path[i]
        out += path.last()
        return out
    }

    private fun nearest(z: DoubleArray, i: Int): Double = z.withIndex().filter { !it.value.isNaN() }.minByOrNull { abs(it.index - i) }?.value ?: Double.NaN

    private val SQRT2 = sqrt(2.0)
}
