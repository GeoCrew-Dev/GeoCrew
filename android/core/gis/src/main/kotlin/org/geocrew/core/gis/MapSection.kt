package org.geocrew.core.gis

import org.geocrew.core.model.Attitude
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Legend
import org.geocrew.core.model.LineStyle
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.lineStyle
import org.geocrew.core.model.positions
import org.geocrew.core.model.string
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Map-derived cross-section (图切剖面): walks the section line from its start
 * and lists the mapped units (polygons) it passes through and the contacts
 * and faults (other lines) it cuts, with each cut's apparent dip where the
 * line has an attitude. That is the plan half of drawing a section from a
 * geological map; heights come from the profile or a DEM.
 *
 * Where polygons overlap, the smaller one wins, so an intrusion drawn on top
 * of its host reads as the intrusion. Neighbouring pieces of the same unit
 * merge into one interval.
 */
class MapSectionTool : GisTool {
    override val id = "map-section"
    override val name = "图切剖面"
    override val description = "沿剖面线从起点走到终点，列出依次穿过的填图单元（面）和切过的地质界线、断层（线），给出每段的起止距离和长度，有产状的界线还算出剖面上的视倾角。结果是按单元着色的剖面分段和交点，可作为图切剖面的底稿。"
    override val accepts = setOf(GeometryType.LINE, GeometryType.POLYGON)

    private val line = ToolParam.Feature("line", "剖面线", setOf(GeometryType.LINE), help = "方向从线的起点指向终点")
    private val unit = ToolParam.Field("unit", "填图单元属性（可选）", numeric = false, help = "例如岩性或地层代号；不选就用面的名称")
    private val terrain = ToolParam.Choice(
        "terrain", "地形线",
        listOf(ToolParam.Choice.Option(TERRAIN_DEM, "取公开 DEM 高程（约 30 m，需联网）"), ToolParam.Choice.Option(TERRAIN_NONE, "不画地形")),
        help = "有地形线时剖面示意按高程画，界线按视倾角画进地下",
    )
    override val params = listOf(line, unit, terrain)

    /** Ground height along the section: chainage (m) and height (m), at even steps. */
    data class Profile(val chainage: DoubleArray, val height: DoubleArray) {
        fun at(c: Double): Double {
            if (c <= chainage.first()) return height.first()
            if (c >= chainage.last()) return height.last()
            val i = chainage.indexOfFirst { it >= c }.coerceAtLeast(1)
            val t = (c - chainage[i - 1]) / (chainage[i] - chainage[i - 1])
            return height[i - 1] + t * (height[i] - height[i - 1])
        }
        override fun equals(other: Any?) = other is Profile && chainage.contentEquals(other.chainage) && height.contentEquals(other.height)
        override fun hashCode() = chainage.contentHashCode() * 31 + height.contentHashCode()
    }

    /**
     * Samples the ground along [s] every [step] metres or so (at most
     * [MAX_SAMPLES]); null with the reason when there's no terrain to be had.
     */
    private fun profile(input: ToolInput, s: Walk): Pair<Profile?, String?> {
        if (input.choice(terrain) != TERRAIN_DEM) return null to null
        val dem = input.elevation ?: return null to "这台设备上没有地形数据来源"
        val n = kotlin.math.ceil(s.length / STEP_M).toInt().coerceIn(2, MAX_SAMPLES)
        val cs = DoubleArray(n + 1) { s.length * it / n }
        val hs = try {
            dem.at(cs.map(s::at))
        } catch (e: IllegalArgumentException) {
            return null to (e.message ?: "剖面太长")
        } catch (e: java.io.IOException) {
            return null to "地形没下载成功（${e.message ?: "网络不通"}）"
        }
        // Fill short gaps from the neighbours; give up when most of the line has no data.
        val known = hs.indices.filter { hs[it] != null }
        if (known.size < (n + 1) / 2) return null to "这一带没有地形数据"
        val filled = DoubleArray(n + 1) { i ->
            hs[i] ?: run {
                val a = known.lastOrNull { it < i }
                val b = known.firstOrNull { it > i }
                when {
                    a == null -> hs[b!!]!!
                    b == null -> hs[a]!!
                    else -> hs[a]!! + (hs[b]!! - hs[a]!!) * (i - a) / (b - a)
                }
            }
        }
        return Profile(cs, filled) to null
    }

    /** One stretch of the section inside one mapped unit. */
    data class Interval(val from: Double, val to: Double, val unit: String, val polygon: GeoFeature) {
        val length get() = to - from
    }

    /** Where the section cuts another line. */
    data class Cut(val chainage: Double, val line: GeoFeature, val at: LngLat, val azimuth: Double) {
        val attitude: Attitude? get() = line.attitude
        val apparentDip: Double? get() = attitude?.let { apparentDip(it, azimuth) }
    }

    data class Result(val length: Double, val intervals: List<Interval>, val cuts: List<Cut>)

    override fun check(input: ToolInput): String? {
        val l = input.feature(line) ?: return "请选择一条剖面线"
        if (path(l).size < 2) return "剖面线至少要有两个节点"
        val others = input.of(accepts).count { it.id != l.id }
        return if (others == 0) "剖面线以外没有面或线可切，请先画填图单元或地质界线" else null
    }

    fun section(input: ToolInput, progress: (Float) -> Unit = {}): Result {
        check(input)?.let { throw IllegalArgumentException(it) }
        val l = input.feature(line)!!
        val s = Walk(path(l))
        val unitKey = input.text(unit.key)
        val polygons = input.of(setOf(GeometryType.POLYGON))
        val lines = input.of(setOf(GeometryType.LINE)).filter { it.id != l.id }
        val total = (polygons.size + lines.size).coerceAtLeast(1)
        var done = 0

        // Every chainage where the section crosses a polygon's boundary.
        val breaks = sortedSetOf(0.0, s.length)
        for (p in polygons) {
            if (input.cancelled()) throw ToolCancelled()
            edges(p.geometry).forEach { (a, b) -> s.crossings(a, b).forEach { breaks += it.first } }
            progress((++done).toFloat() / total)
        }
        val cuts = ArrayList<Cut>()
        for (f in lines) {
            if (input.cancelled()) throw ToolCancelled()
            val seen = ArrayList<Double>()
            edges(f.geometry).forEach { (a, b) ->
                s.crossings(a, b).forEach { (c, az) ->
                    // A crossing through a vertex shows up on both edges.
                    if (seen.none { abs(it - c) < TOLERANCE_M }) {
                        seen += c
                        cuts += Cut(c, f, s.at(c), az)
                    }
                }
            }
            progress((++done).toFloat() / total)
        }
        cuts.sortBy { it.chainage }

        val areas = HashMap<String, Double>()
        fun areaOf(p: GeoFeature) = areas.getOrPut(p.id) { p.geometry!!.areaSquareMeters() }
        val intervals = ArrayList<Interval>()
        breaks.toList().zipWithNext().forEach { (a, b) ->
            if (b - a < TOLERANCE_M) return@forEach
            val mid = s.at((a + b) / 2)
            val p = polygons.filter { ZonalTool.contains(it.geometry!!, mid) }.minByOrNull(::areaOf) ?: return@forEach
            val name = unitKey?.let { p.properties.string(it)?.trim()?.ifEmpty { null } } ?: p.title ?: "未命名面"
            val last = intervals.lastOrNull()
            if (last != null && last.unit == name && abs(last.to - a) < TOLERANCE_M) {
                intervals[intervals.lastIndex] = last.copy(to = b)
            } else {
                intervals += Interval(a, b, name, p)
            }
        }
        return Result(s.length, intervals, cuts)
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        val r = section(input, progress)
        val l = input.feature(line)!!
        val s = Walk(path(l))
        if (input.cancelled()) throw ToolCancelled()
        val (ground, groundNote) = profile(input, s)
        val title = l.title ?: "剖面线"
        val palette = HashMap<String, String>()
        fun colorOf(i: Interval): String = palette.getOrPut(i.unit) {
            listOf(OutputProps.FILL, OutputProps.STROKE).firstNotNullOfOrNull { k -> i.polygon.properties.string(k)?.takeIf(Legend::isColor) }
                ?: PALETTE[palette.size % PALETTE.size]
        }
        val features = r.intervals.map { i ->
            OutputFeature(FeatureKind.LINE, Geometry.LineString(s.between(i.from, i.to)), buildProperties {
                this[OutputProps.TOOL] = id
                this[Props.TITLE] = "$title ${i.unit}"
                this[SECTION_LINE] = title
                this[UNIT] = i.unit
                this[FROM_M] = round1(i.from)
                this[TO_M] = round1(i.to)
                this[LENGTH_M] = round1(i.length)
                this[SectionTool.SECTION_LENGTH_M] = round1(r.length)
                this[SOURCE] = i.polygon.id
                style(stroke = colorOf(i), strokeWidth = 6.0)
            })
        } + r.cuts.map { c ->
            OutputFeature(FeatureKind.POINT, Geometry.Point(c.at), buildProperties {
                this[OutputProps.TOOL] = id
                this[Props.TITLE] = "${c.line.title ?: "未命名线"} 距起点 ${CoordinateFormatter.distance(c.chainage)}"
                this[SECTION_LINE] = title
                this[CHAINAGE_M] = round1(c.chainage)
                this[SectionTool.SECTION_LENGTH_M] = round1(r.length)
                this[SOURCE] = c.line.id
                c.line.lineStyle?.let { this[Props.LINE_STYLE] = it.id }
                c.apparentDip?.let { this[SectionTool.APPARENT_DIP] = round1(it) }
                c.attitude?.let { this[SectionTool.TRUE_ATTITUDE] = it.format() }
                ground?.let { this[ELEVATION_M] = round1(it.at(c.chainage)) }
                this["marker-color"] = if (c.line.lineStyle?.fault == true) "#D32F2F" else "#212121"
            })
        }
        val terrainLine = ground?.let { g ->
            OutputFeature(FeatureKind.LINE, Geometry.LineString(g.chainage.indices.map { i -> s.at(g.chainage[i]).copy(alt = round1(g.height[i])) }), buildProperties {
                this[OutputProps.TOOL] = id
                this[Props.TITLE] = "$title 地形线"
                this[SECTION_LINE] = title
                this[ROLE] = ROLE_TERRAIN
                this[SectionTool.SECTION_LENGTH_M] = round1(r.length)
                put(PROFILE, kotlinx.serialization.json.JsonArray(g.chainage.indices.map { i ->
                    kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(round1(g.chainage[i])), kotlinx.serialization.json.JsonPrimitive(round1(g.height[i]))))
                }))
                style(stroke = "#6D4C41", strokeWidth = 2.0)
            })
        }
        val legend = palette.entries.map { (unit, color) -> LegendEntry(color, unit) }
        return ToolOutput(features + listOfNotNull(terrainLine), summary(title, r) + terrainSummary(ground, groundNote), legend)
    }

    private fun terrainSummary(g: Profile?, note: String?): String = when {
        g != null -> {
            val hi = g.height.indices.maxBy { g.height[it] }
            val lo = g.height.indices.minBy { g.height[it] }
            "\n地形：起点 ${fmt(g.height.first(), 0)} m，终点 ${fmt(g.height.last(), 0)} m，最高 ${fmt(g.height[hi], 0)} m（距起点 ${CoordinateFormatter.distance(g.chainage[hi])}），最低 ${fmt(g.height[lo], 0)} m"
        }
        note != null -> "\n没画地形线：$note"
        else -> ""
    }

    private fun summary(title: String, r: Result): String = buildString {
        append("「$title」长 ${CoordinateFormatter.distance(r.length)}")
        if (r.intervals.isEmpty()) append("，没有穿过任何面") else {
            val covered = r.intervals.sumOf { it.length }
            append("，依次穿过 ${r.intervals.size} 段填图单元")
            if (r.length - covered > TOLERANCE_M) append("（${CoordinateFormatter.distance(r.length - covered)} 在面外）")
            append("：")
            r.intervals.take(SUMMARY_ROWS).forEach { i ->
                append("\n${fmt(i.from, 0)}–${fmt(i.to, 0)} m ${i.unit}（${CoordinateFormatter.distance(i.length)}）")
            }
            if (r.intervals.size > SUMMARY_ROWS) append("\n……其余 ${r.intervals.size - SUMMARY_ROWS} 段见结果的属性")
        }
        if (r.cuts.isNotEmpty()) {
            append("\n切过 ${r.cuts.size} 处线：")
            r.cuts.take(SUMMARY_ROWS).forEach { c ->
                append("\n${fmt(c.chainage, 0)} m ${c.line.title ?: "未命名线"}")
                c.line.lineStyle?.let { append("（${it.label}）") }
                c.apparentDip?.let { b ->
                    append("，${c.attitude!!.format()} → 视倾角 ${fmt(abs(b), 0)}°" + if (b < 0) "向起点" else if (b < 90.0) "向终点" else "")
                }
            }
            if (r.cuts.size > SUMMARY_ROWS) append("\n……其余 ${r.cuts.size - SUMMARY_ROWS} 处见结果的属性")
        }
    }

    /** The section line on a local plane: chainage both ways, and crossings with other segments. */
    private class Walk(private val line: List<LngLat>) {
        private val plane = LocalPlane(line.first())
        private val xs = line.map { plane.x(it) }
        private val ys = line.map { plane.y(it) }
        private val starts = DoubleArray(line.size).also { c ->
            for (i in 1 until line.size) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        val length get() = starts.last()

        /** Chainage and segment azimuth of every point where segment [a]–[b] crosses the section. */
        fun crossings(a: LngLat, b: LngLat): List<Pair<Double, Double>> {
            val qx = plane.x(a)
            val qy = plane.y(a)
            val sx = plane.x(b) - qx
            val sy = plane.y(b) - qy
            val out = ArrayList<Pair<Double, Double>>(1)
            for (i in 0 until xs.size - 1) {
                val rx = xs[i + 1] - xs[i]
                val ry = ys[i + 1] - ys[i]
                val d = rx * sy - ry * sx
                // Parallel or overlapping segments don't cross; the ends of an overlap are found on the neighbouring edges.
                if (abs(d) < 1e-9 * hypot(rx, ry) * hypot(sx, sy) || d == 0.0) continue
                val wx = qx - xs[i]
                val wy = qy - ys[i]
                val t = (wx * sy - wy * sx) / d
                val u = (wx * ry - wy * rx) / d
                if (t < -EPS || t > 1 + EPS || u < -EPS || u > 1 + EPS) continue
                val seg = starts[i + 1] - starts[i]
                val c = (starts[i] + t.coerceIn(0.0, 1.0) * seg)
                out += c to Geodesy.normalizeDegrees(atan2(rx, ry) * 180 / PI)
            }
            return out
        }

        fun at(chainage: Double): LngLat {
            val c = chainage.coerceIn(0.0, length)
            val i = (1 until starts.size).firstOrNull { starts[it] >= c }?.minus(1) ?: (starts.size - 2)
            val seg = starts[i + 1] - starts[i]
            val t = if (seg > 0) (c - starts[i]) / seg else 0.0
            return plane.toLngLat(xs[i] + t * (xs[i + 1] - xs[i]), ys[i] + t * (ys[i + 1] - ys[i]))
        }

        /** The part of the line from chainage [from] to [to], with the vertices in between. */
        fun between(from: Double, to: Double): List<LngLat> =
            listOf(at(from)) + line.indices.filter { starts[it] > from && starts[it] < to }.map { line[it] } + at(to)
    }

    companion object {
        const val SECTION_LINE = "section_line"
        const val UNIT = "unit"
        const val FROM_M = "from_m"
        const val TO_M = "to_m"
        const val LENGTH_M = "length_m"
        const val CHAINAGE_M = "chainage_m"
        const val SOURCE = "source"
        /** Ground height at a cut, when the section has terrain. */
        const val ELEVATION_M = "elevation_m"
        /** What an output is for; the terrain line has [ROLE_TERRAIN]. */
        const val ROLE = "role"
        const val ROLE_TERRAIN = "terrain"
        /** The terrain line's profile, [[chainage, height], ...] in metres. */
        const val PROFILE = "profile"
        const val TERRAIN_DEM = "dem"
        const val TERRAIN_NONE = "none"
        private const val STEP_M = 30.0
        private const val MAX_SAMPLES = 400
        private const val TOLERANCE_M = 0.05
        private const val EPS = 1e-9
        private const val SUMMARY_ROWS = 10

        /** Distinct, print-friendly colours for units without a colour of their own. */
        private val PALETTE = listOf("#E6550D", "#31A354", "#3182BD", "#FDAE6B", "#756BB1", "#636363", "#DE2D26", "#A1D99B", "#9ECAE1", "#BCBDDC")

        private fun round1(v: Double) = Math.round(v * 10) / 10.0

        private fun path(f: GeoFeature): List<LngLat> = when (val g = f.geometry) {
            is Geometry.LineString -> g.positions
            is Geometry.MultiLineString -> g.lines.maxByOrNull { it.size }.orEmpty()
            else -> emptyList()
        }

        /** Boundary segments of a polygon, or the segments of a line. */
        private fun edges(g: Geometry?): Sequence<Pair<LngLat, LngLat>> = when (g) {
            is Geometry.LineString -> g.positions.zipWithNext().asSequence()
            is Geometry.MultiLineString -> g.lines.asSequence().flatMap { it.zipWithNext() }
            is Geometry.Polygon -> g.rings.asSequence().flatMap { closed(it).zipWithNext() }
            is Geometry.MultiPolygon -> g.polygons.asSequence().flatMap { p -> p.rings.asSequence().flatMap { closed(it).zipWithNext() } }
            else -> emptySequence()
        }

        private fun closed(ring: List<LngLat>) = if (ring.size > 1 && ring.first() != ring.last()) ring + ring.first() else ring
    }
}
