package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.positions
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Shapes of semivariogram model, all reaching (about) the sill at [Variogram.range]. */
enum class VariogramModel(val label: String) {
    SPHERICAL("球状"), EXPONENTIAL("指数"), GAUSSIAN("高斯");

    /** Normalised structure, 0 at h = 0 rising to (about) 1 at h = a. */
    fun shape(h: Double, a: Double): Double {
        if (h <= 0) return 0.0
        val r = h / a
        return when (this) {
            SPHERICAL -> if (r >= 1) 1.0 else 1.5 * r - 0.5 * r * r * r
            // Practical range: 95 % of the sill at h = a.
            EXPONENTIAL -> 1 - exp(-3 * r)
            GAUSSIAN -> 1 - exp(-3 * r * r)
        }
    }
}

/** γ(h) = nugget + partialSill · shape(h / range); γ(0) = 0. */
data class Variogram(val model: VariogramModel, val nugget: Double, val partialSill: Double, val range: Double) {
    val sill get() = nugget + partialSill

    fun gamma(h: Double): Double = if (h <= 1e-12) 0.0 else nugget + partialSill * model.shape(h, range)

    /** One bin of the experimental semivariogram: mean lag, γ and the number of pairs. */
    data class Lag(val h: Double, val gamma: Double, val pairs: Int)

    companion object {
        /**
         * Experimental semivariogram from (x, y, v) samples in metres: pairs
         * grouped into [lags] equal bins up to half the largest distance.
         */
        fun experimental(x: DoubleArray, y: DoubleArray, v: DoubleArray, lags: Int = 12): List<Lag> {
            val n = v.size
            var maxD = 0.0
            for (i in 0 until n) for (j in i + 1 until n) maxD = max(maxD, hypot(x[i] - x[j], y[i] - y[j]))
            val cutoff = maxD / 2
            if (cutoff <= 0) return emptyList()
            val width = cutoff / lags
            val sumH = DoubleArray(lags)
            val sumG = DoubleArray(lags)
            val count = IntArray(lags)
            for (i in 0 until n) for (j in i + 1 until n) {
                val d = hypot(x[i] - x[j], y[i] - y[j])
                if (d <= 0 || d > cutoff) continue
                val b = min((d / width).toInt(), lags - 1)
                sumH[b] += d
                val dv = v[i] - v[j]
                sumG[b] += 0.5 * dv * dv
                count[b]++
            }
            return (0 until lags).filter { count[it] > 0 }.map { Lag(sumH[it] / count[it], sumG[it] / count[it], count[it]) }
        }

        /**
         * Fits [model] by weighted least squares (weights = pair counts): for
         * each trial range the nugget and partial sill follow in closed form,
         * kept non-negative. Falls back to a pure nugget when nothing fits.
         */
        fun fit(lags: List<Lag>, model: VariogramModel, fixedRange: Double? = null): Variogram? {
            if (lags.isEmpty()) return null
            val maxH = lags.maxOf { it.h }
            val ranges = fixedRange?.let { listOf(it) } ?: (1..60).map { maxH * 0.05 + (maxH * 1.5 - maxH * 0.05) * it / 60.0 }
            var best: Variogram? = null
            var bestErr = Double.MAX_VALUE
            for (a in ranges) {
                val v = solve(lags, model, a) ?: continue
                val err = lags.sumOf { l -> l.pairs * (v.gamma(l.h) - l.gamma).let { it * it } }
                if (err < bestErr) { bestErr = err; best = v }
            }
            return best
        }

        private fun solve(lags: List<Lag>, model: VariogramModel, a: Double): Variogram? {
            // Minimise Σ w (c0 + c·f − γ)²  over c0, c ≥ 0.
            var sw = 0.0; var sf = 0.0; var sff = 0.0; var sg = 0.0; var sfg = 0.0
            for (l in lags) {
                val w = l.pairs.toDouble()
                val f = model.shape(l.h, a)
                sw += w; sf += w * f; sff += w * f * f; sg += w * l.gamma; sfg += w * f * l.gamma
            }
            val det = sw * sff - sf * sf
            var c0: Double
            var c: Double
            if (abs(det) > 1e-12) {
                c0 = (sff * sg - sf * sfg) / det
                c = (sw * sfg - sf * sg) / det
            } else {
                c0 = sg / sw; c = 0.0
            }
            if (c0 < 0) { c0 = 0.0; c = if (sff > 0) max(0.0, sfg / sff) else 0.0 }
            if (c < 0) { c = 0.0; c0 = max(0.0, sg / sw) }
            if (c0 + c <= 0) return null
            return Variogram(model, c0, c, a)
        }
    }
}

/**
 * Ordinary kriging with a local neighbourhood. Besides the estimate it gives
 * the kriging variance, which shows where the samples say least and so where
 * the next sample is worth most.
 */
class OrdinaryKriging(
    private val x: DoubleArray,
    private val y: DoubleArray,
    private val v: DoubleArray,
    private val variogram: Variogram,
    private val neighbours: Int = 16,
) {
    data class Estimate(val value: Double, val variance: Double)

    fun estimate(px: Double, py: Double): Estimate {
        val n = v.size
        val idx = if (n <= neighbours) (0 until n).toList()
        else (0 until n).sortedBy { (x[it] - px) * (x[it] - px) + (y[it] - py) * (y[it] - py) }.take(neighbours)
        val k = idx.size
        // [ Γ 1 ] [λ]   [γ0]
        // [ 1ᵀ 0 ] [μ] = [ 1]
        val a = Array(k + 1) { DoubleArray(k + 2) }
        for (i in 0 until k) {
            for (j in 0 until k) a[i][j] = variogram.gamma(hypot(x[idx[i]] - x[idx[j]], y[idx[i]] - y[idx[j]]))
            a[i][k] = 1.0
            a[i][k + 1] = variogram.gamma(hypot(x[idx[i]] - px, y[idx[i]] - py))
        }
        for (j in 0 until k) a[k][j] = 1.0
        a[k][k] = 0.0
        a[k][k + 1] = 1.0
        // gauss() works in place, so keep the right-hand side for the variance.
        val g0 = DoubleArray(k) { a[it][k + 1] }
        val sol = gauss(a) ?: return Estimate(idx.sumOf { v[it] } / k, Double.NaN)
        var value = 0.0
        var variance = sol[k]
        for (i in 0 until k) {
            value += sol[i] * v[idx[i]]
            variance += sol[i] * g0[i]
        }
        return Estimate(value, max(0.0, variance))
    }

    /** Solves an augmented matrix in place with partial pivoting; null if singular. */
    private fun gauss(m: Array<DoubleArray>): DoubleArray? {
        val n = m.size
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < 1e-12) return null
            val t = m[p]; m[p] = m[c]; m[c] = t
            for (r in 0 until n) {
                if (r == c) continue
                val f = m[r][c] / m[c][c]
                if (f == 0.0) continue
                for (k in c until n + 1) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}

/**
 * Ordinary kriging on a grid, with the semivariogram fitted automatically,
 * and optional suggestions for where to sample next: the cells with the
 * largest kriging standard deviation, kept apart from each other.
 */
class KrigingTool : GisTool {
    override val id = "kriging"
    override val name = "克里金插值"
    override val description = "普通克里金：自动拟合半变异函数，输出估计值网格和估计标准差，并可给出下一步补充采样的建议位置。"
    override val accepts = setOf(GeometryType.POINT)

    private val field = ToolParam.Field("field", "插值属性", numeric = true)
    private val model = ToolParam.Choice(
        "model", "变异函数模型",
        VariogramModel.entries.map { ToolParam.Choice.Option(it.name, it.label) },
    )
    private val range = ToolParam.Number("range", "变程", default = null, min = 1.0, unit = "m", help = "留空自动拟合")
    private val cell = ToolParam.Number("cell", "网格大小", default = null, min = 1.0, max = 10_000.0, unit = "m", help = "留空自动选择")
    private val show = ToolParam.Choice(
        "show", "网格显示",
        listOf(ToolParam.Choice.Option("value", "估计值"), ToolParam.Choice.Option("sd", "估计标准差（不确定性）")),
    )
    private val suggest = ToolParam.Number("suggest", "建议补点数", default = 3.0, min = 0.0, max = 20.0, help = "在最不确定的位置给出补充采样点，0 表示不需要")
    private val plan = ToolParam.Choice(
        "plan", "补点方式",
        listOf(
            ToolParam.Choice.Option("uncertainty", "只看不确定性"),
            ToolParam.Choice.Option("travel", "兼顾路程"),
        ),
        help = "每选一个点都把它当作已采样重新计算，后面的点不会扎堆；兼顾路程时离上一个点近的位置优先",
    )
    private val start = ToolParam.Feature(
        "start", "补点路线起点", setOf(GeometryType.POINT),
        help = "可选，如驻地或现在所在的记录点；能取到 DEM 时按地形徒步用时排顺序，否则按最短距离",
    )
    private val contour = ToolParam.Number("contour", "等值线间距", default = null, min = 0.0, help = "按所显示的量（估计值或标准差）画，留空自动分档，填 0 不画")
    override val params = listOf(field, model, range, cell, show, contour, suggest, plan, start)

    override fun check(input: ToolInput): String? {
        val key = input.text(field.key) ?: return "请选择插值属性"
        val n = input.of(accepts).count { it.number(key) != null }
        return if (n < 5) "克里金至少需要 5 个带「$key」数值的点，现在有 $n 个" else null
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        check(input)?.let { throw IllegalArgumentException(it) }
        val key = input.text(field.key)!!
        val raw = input.of(accepts).flatMap { f -> f.number(key)?.let { v -> f.geometry!!.positions().map { it to v } }.orEmpty() }
        val plane = LocalPlane(raw.first().first)
        // Samples at the same spot would make the system singular: average them.
        val merged = raw.groupBy { (p, _) -> Math.round(plane.x(p) * 10) to Math.round(plane.y(p) * 10) }
            .values.map { g -> Triple(plane.x(g.first().first), plane.y(g.first().first), g.map { it.second }.average()) }
        val xs = DoubleArray(merged.size) { merged[it].first }
        val ys = DoubleArray(merged.size) { merged[it].second }
        val vs = DoubleArray(merged.size) { merged[it].third }
        if (merged.size < 5) throw IllegalArgumentException("去掉重复位置后只剩 ${merged.size} 个点，至少需要 5 个")

        val m = VariogramModel.valueOf(input.choice(model))
        val lags = Variogram.experimental(xs, ys, vs)
        val vg = Variogram.fit(lags, m, input.number(range))
            ?: throw IllegalArgumentException("「$key」在各点几乎没有变化，无法拟合变异函数")
        val ok = OrdinaryKriging(xs, ys, vs, vg)

        var minX = xs.min(); var maxX = xs.max(); var minY = ys.min(); var maxY = ys.max()
        val pad = max(maxX - minX, maxY - minY) * 0.05 + 1.0
        minX -= pad; maxX += pad; minY -= pad; maxY += pad
        val w = maxX - minX
        val h = maxY - minY
        var size = input.number(cell) ?: (max(w, h) / AUTO_CELLS)
        if (ceil(w / size) * ceil(h / size) > MAX_CELLS) size = sqrt(w * h / MAX_CELLS)
        val cols = ceil(w / size).toInt().coerceAtLeast(1)
        val rows = ceil(h / size).toInt().coerceAtLeast(1)

        val est = Array(rows) { arrayOfNulls<OrdinaryKriging.Estimate>(cols) }
        for (row in 0 until rows) {
            if (input.cancelled()) throw ToolCancelled()
            for (col in 0 until cols) est[row][col] = ok.estimate(minX + (col + 0.5) * size, minY + (row + 0.5) * size)
            progress((row + 1f) / rows)
        }

        val showSd = input.choice(show) == "sd"
        val shown = est.flatMap { r -> r.map { e -> if (showSd) sqrt(e!!.variance) else e!!.value } }.filter { it.isFinite() }
        val lo = shown.min(); val hi = shown.max()
        val ramp = ColorRamp(lo, hi)
        val out = ArrayList<OutputFeature>(rows * cols + 20)
        for (row in 0 until rows) for (col in 0 until cols) {
            val e = est[row][col]!!
            val sd = sqrt(e.variance)
            val c = if (showSd) sd else e.value
            if (!c.isFinite()) continue
            val x0 = minX + col * size; val y0 = minY + row * size
            out += OutputFeature(
                FeatureKind.POLYGON,
                Geometry.Polygon.of(listOf(plane.toLngLat(x0, y0), plane.toLngLat(x0 + size, y0), plane.toLngLat(x0 + size, y0 + size), plane.toLngLat(x0, y0 + size))),
                buildProperties {
                    this[OutputProps.TOOL] = id
                    this[OutputProps.VALUE] = e.value
                    this[SD] = sd
                    this[Props.TITLE] = "$key ≈ ${fmt(e.value)} ± ${fmt(sd)}"
                    style(fill = ramp.color(c), fillOpacity = 0.55, stroke = ramp.color(c), strokeWidth = 0.0)
                },
            )
        }

        val grid = Array(rows) { row -> DoubleArray(cols) { col -> est[row][col]!!.let { e -> if (showSd) sqrt(e.variance) else e.value } } }
        val lines = Contours.features(grid, plane, minX, minY, size, id, if (showSd) "$key 标准差" else key, input.number(contour))
        lines?.let { out += it.first }

        val wanted = input.number(suggest)?.toInt() ?: 0
        val from = input.feature(start)?.let { f -> (f.geometry as Geometry.Point).position.let { plane.x(it) to plane.y(it) } }
        val centres = ArrayList<Pair<Double, Double>>(rows * cols)
        val variances = ArrayList<Double>(rows * cols)
        for (row in 0 until rows) for (col in 0 until cols) {
            centres += (minX + (col + 0.5) * size) to (minY + (row + 0.5) * size)
            variances += est[row][col]!!.variance
        }
        val picks = SamplingPlan.pick(
            xs, ys, vg, centres, wanted, variances.toDoubleArray(),
            start = from, travel = if (input.choice(plan) == "travel") 2.0 else 0.0, scale = max(w, h),
            cancelled = input.cancelled,
        )
        // With a DEM, order the stops by walking time (uphill costs more), not by distance.
        val offset = if (from != null) 1 else 0
        val byDistance = SamplingPlan.route(picks.map { it.x to it.y }, from)
        val nodesLL = (listOfNotNull(from) + picks.map { it.x to it.y }).map { (x, y) -> plane.toLngLat(x, y) }
        val hours = input.elevation?.takeIf { picks.size >= 2 }?.let { dem ->
            try {
                SamplingPlan.walkHours(nodesLL, dem, org.geocrew.core.model.Hiking.OFF_PATH, cancelled = input.cancelled)
            } catch (e: java.io.IOException) {
                null
            }
        }
        var saved = 0.0
        val order = if (hours == null) byDistance else {
            val byTime = SamplingPlan.routeBy(nodesLL.size) { a, b -> hours[a][b] }
            val distanceNodes = (if (from != null) listOf(0) else emptyList()) + byDistance.map { it + offset }
            saved = SamplingPlan.cost(distanceNodes) { a, b -> hours[a][b] } - SamplingPlan.cost(byTime) { a, b -> hours[a][b] }
            byTime.drop(offset).map { it - offset }
        }
        val visits = order.map { picks[it] }
        visits.forEachIndexed { i, p ->
            out += OutputFeature(
                FeatureKind.POINT,
                Geometry.Point(plane.toLngLat(p.x, p.y)),
                buildProperties {
                    this[OutputProps.TOOL] = id
                    this[SD] = p.sd
                    this[Props.TITLE] = "建议补点 ${i + 1}"
                    this[Props.NOTE] = "采到这里之前的克里金估计标准差 ${fmt(p.sd)}，在此采样能明显减少「$key」的不确定性"
                },
            )
        }
        val stops = listOfNotNull(from) + visits.map { it.x to it.y }
        val walk = SamplingPlan.length(stops)
        if (stops.size >= 2) {
            out += OutputFeature(
                FeatureKind.LINE,
                Geometry.LineString(stops.map { (x, y) -> plane.toLngLat(x, y) }),
                buildProperties {
                    this[OutputProps.TOOL] = id
                    this[Props.TITLE] = "补点路线"
                    this[Props.NOTE] = "依次经过 ${visits.size} 个建议补点，直线距离约 ${fmt(walk, 0)} m" +
                        (walkTime(input, stops.map { (x, y) -> plane.toLngLat(x, y) })?.let { "，按地形徒步$it" } ?: "")
                    style(stroke = "#d81b60", strokeWidth = 3.0)
                },
            )
        }

        val fitText = "${m.label}模型：块金 ${fmt(vg.nugget)}，基台 ${fmt(vg.sill)}，变程 ${fmt(vg.range, 0)} m"
        val nuggetShare = if (vg.sill > 0) vg.nugget / vg.sill else 0.0
        val note = if (nuggetShare > 0.75) "块金占基台 ${(nuggetShare * 100).toInt()}%，空间相关性弱，估计接近平均值，可考虑加密采样。" else ""
        return ToolOutput(
            out,
            buildString {
                append("由 ${merged.size} 个点得到 ${cols}×${rows} 网格（格距 ${fmt(size, 1)} m）。")
                append(fitText).append("。")
                append(if (showSd) "颜色为估计标准差 ${fmt(lo)} – ${fmt(hi)}。" else "「$key」估计范围 ${fmt(lo)} – ${fmt(hi)}。")
                lines?.let { (_, lv) -> append("等值线 ${lv.size} 条，${Contours.label(lv.first())} – ${Contours.label(lv.last())}。") }
                if (visits.isNotEmpty()) append("已标出 ${visits.size} 个建议补点" + if (stops.size >= 2) "和补点路线（直线约 ${fmt(walk, 0)} m）。" else "。")
                if (hours != null) {
                    append("补点顺序按地形徒步用时排")
                    append(if (saved >= 1.0 / 60) "，比按直线距离排少走约${org.geocrew.core.model.Hiking.format(saved)}。" else "。")
                }
                append(note)
            },
            ramp.legend { fmt(it) },
        )
    }

    companion object {
        /** Kriging standard deviation of a cell or suggestion. */
        const val SD = "kriging_sd"
        private const val AUTO_CELLS = 40.0
        private const val MAX_CELLS = 6_400.0
    }
}

/** Walking time along [path] over the DEM (Tobler, off paths), or null without heights or signal. */
internal fun walkTime(input: ToolInput, path: List<org.geocrew.core.model.LngLat>): String? {
    val dem = input.elevation ?: return null
    val pts = org.geocrew.core.model.Hiking.densify(path).takeIf { it.size in 2..4000 } ?: return null
    val hs = try { pts.chunked(500).flatMap { dem.at(it) } } catch (e: java.io.IOException) { return null }
    if (hs.all { it == null }) return null
    return org.geocrew.core.model.Hiking.format(org.geocrew.core.model.Hiking.estimate(pts, hs, org.geocrew.core.model.Hiking.OFF_PATH).hours) + "（无路，未计采样时间）"
}
