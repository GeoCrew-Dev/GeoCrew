package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Hiking
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.buildProperties
import java.io.IOException

/**
 * Walking time along planned lines over the public DEM (Tobler's hiking
 * function), one way and back, with climb and descent: for planning a
 * traverse, or knowing when to turn round to be back before dark.
 */
class HikeTool : GisTool {
    override val id = "hike"
    override val name = "徒步用时"
    override val description = "沿画好的线取公开 DEM 高程（需联网），按 Tobler 徒步公式估算走完要多久、累计爬升和下降，并给出原路返回的用时。适合排路线、定外出的返回时间。"
    override val accepts = setOf(GeometryType.LINE)

    private val ground = ToolParam.Choice(
        "ground", "路况",
        listOf(
            ToolParam.Choice.Option(PATH, "有路（山路、小道）"),
            ToolParam.Choice.Option(OFF, "无路（灌丛、乱石，按 0.6 倍速度）"),
        ),
    )
    private val pace = ToolParam.Number("pace", "速度系数", default = 1.0, min = 0.2, max = 2.0, help = "负重或队伍慢时调小，例如 0.8")
    override val params = listOf(ground, pace)

    override fun check(input: ToolInput): String? = when {
        input.of(accepts).isEmpty() -> "请选择要估算的线"
        input.elevation == null -> "这里取不到 DEM 高程"
        else -> null
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        check(input)?.let { throw IllegalArgumentException(it) }
        val factor = (input.number(pace) ?: 1.0) * if (input.choice(ground) == OFF) Hiking.OFF_PATH else 1.0
        val lines = input.of(accepts)
        val out = ArrayList<OutputFeature>()
        val rows = ArrayList<String>()
        var total = 0.0
        var missing = false
        lines.forEachIndexed { i, f ->
            if (input.cancelled()) throw ToolCancelled()
            for (path in paths(f.geometry!!)) {
                val pts = Hiking.densify(path).let { if (it.size > MAX_SAMPLES) Hiking.densify(path, Hiking.STEP_M * it.size / MAX_SAMPLES) else it }
                val hs = try {
                    pts.chunked(CHUNK).flatMap { input.elevation!!.at(it) }
                } catch (e: IOException) {
                    throw IllegalArgumentException("取 DEM 高程失败：${e.message ?: "网络不通"}")
                }
                val go = Hiking.estimate(pts, hs, factor)
                val back = Hiking.estimate(pts.reversed(), hs.reversed(), factor)
                missing = missing || go.missingHeights
                total += go.hours
                val name = f.title?.takeIf { it.isNotBlank() } ?: "路线 ${i + 1}"
                val line = "$name：${fmt(go.distance / 1000, 1)} km，爬升 ${fmt(go.ascent, 0)} m、下降 ${fmt(go.descent, 0)} m，" +
                    "去程${Hiking.format(go.hours)}，原路返回${Hiking.format(back.hours)}"
                rows += line
                out += OutputFeature(
                    FeatureKind.LINE,
                    Geometry.LineString(path),
                    buildProperties {
                        this[OutputProps.TOOL] = id
                        this[Props.TITLE] = "$name · ${Hiking.format(go.hours)}"
                        this[Props.NOTE] = line
                        this[HOURS] = go.hours
                        this[BACK_HOURS] = back.hours
                        this[ASCENT] = go.ascent
                        this[DESCENT] = go.descent
                        style(stroke = "#6a1b9a", strokeWidth = 3.0)
                    },
                )
            }
            progress((i + 1f) / lines.size)
        }
        val summary = buildString {
            append(rows.joinToString("\n"))
            if (rows.size > 1) append("\n合计去程${Hiking.format(total)}。")
            append("\n按 Tobler 公式和约 30 m 的 DEM 估算，未计休息、观察和采样时间；陡崖、河流等障碍看不出来，以实地为准。")
            if (missing) append("部分位置没有 DEM 高程，按前后高程插补。")
        }
        return ToolOutput(out, summary)
    }

    private fun paths(g: Geometry): List<List<LngLat>> = when (g) {
        is Geometry.LineString -> listOf(g.positions)
        is Geometry.MultiLineString -> g.lines
        else -> emptyList()
    }.filter { it.size >= 2 }

    companion object {
        const val HOURS = "hike_hours"
        const val BACK_HOURS = "hike_back_hours"
        const val ASCENT = "ascent_m"
        const val DESCENT = "descent_m"
        private const val PATH = "path"
        private const val OFF = "off"
        private const val CHUNK = 500
        private const val MAX_SAMPLES = 4000
    }
}
