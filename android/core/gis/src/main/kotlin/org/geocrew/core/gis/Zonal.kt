package org.geocrew.core.gis

import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.string

/**
 * What lies inside each polygon (面内统计): how many points, how dense, the
 * mix of a category such as lithology, and the range of a value such as a
 * grade, e.g. for each mapped intrusion or each claim block. A point on a
 * shared edge counts in every polygon it touches; points in holes don't.
 */
class ZonalTool : GisTool {
    override val id = "zonal"
    override val name = "面内统计"
    override val description = "统计每个面里有多少个点、点的密度、某个分类属性（如岩性）各有几个，以及某个数值属性的范围和平均值。结果是一份带统计属性的面，按点数着色。"
    override val accepts = setOf(GeometryType.POINT, GeometryType.POLYGON)

    private val category = ToolParam.Field("category", "分类属性（可选）", numeric = false, help = "例如岩性、地层，按取值分别计数")
    private val value = ToolParam.Field("value", "数值属性（可选）", numeric = true, help = "例如品位，给出最小、最大和平均")
    override val params = listOf(category, value)

    /** The tally for one polygon. */
    data class Zone(
        val polygon: GeoFeature,
        val points: List<GeoFeature>,
        val areaM2: Double,
        val categories: List<Pair<String, Int>>,
        val values: List<Double>,
    ) {
        val perKm2: Double get() = if (areaM2 > 0) points.size / (areaM2 / 1e6) else 0.0
    }

    override fun check(input: ToolInput): String? = when {
        input.of(setOf(GeometryType.POLYGON)).isEmpty() -> "没有面可统计，请先画面或导入面"
        input.of(setOf(GeometryType.POINT)).isEmpty() -> "没有点可统计"
        else -> null
    }

    fun zones(input: ToolInput): List<Zone> {
        val points = input.of(setOf(GeometryType.POINT))
        val catKey = input.text(category.key)
        val valKey = input.text(value.key)
        return input.of(setOf(GeometryType.POLYGON)).map { poly ->
            if (input.cancelled()) throw ToolCancelled()
            val g = poly.geometry!!
            val inside = points.filter { p -> positions(p.geometry).any { contains(g, it) } }
            val cats = catKey?.let { k ->
                inside.mapNotNull { it.properties.string(k)?.trim()?.ifEmpty { null } }
                    .groupingBy { it }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .map { it.key to it.value }
            }.orEmpty()
            Zone(poly, inside, g.areaSquareMeters(), cats, valKey?.let { k -> inside.mapNotNull { it.number(k) } }.orEmpty())
        }
    }

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        check(input)?.let { throw IllegalArgumentException(it) }
        val zs = zones(input)
        val valKey = input.text(value.key)
        val catKey = input.text(category.key)
        val ramp = ColorRamp(0.0, (zs.maxOfOrNull { it.points.size } ?: 0).toDouble())
        val out = zs.map { z ->
            OutputFeature(FeatureKind.POLYGON, z.polygon.geometry!!, buildProperties {
                this[OutputProps.TOOL] = id
                this[Props.TITLE] = "面内统计 ${z.polygon.title ?: "未命名"}"
                this[OutputProps.VALUE] = z.points.size.toDouble()
                this[COUNT] = z.points.size
                this[AREA_KM2] = round(z.areaM2 / 1e6, 4)
                this[PER_KM2] = round(z.perKm2, 2)
                this[SOURCE] = z.polygon.id
                if (catKey != null && z.categories.isNotEmpty()) this[CATEGORIES] = z.categories.joinToString("、") { (k, n) -> "$k $n" }
                if (valKey != null && z.values.isNotEmpty()) {
                    this[MIN] = z.values.min()
                    this[MAX] = z.values.max()
                    this[MEAN] = round(z.values.average(), 4)
                }
                style(fill = ramp.color(z.points.size.toDouble()), fillOpacity = 0.35, stroke = "#333333", strokeWidth = 1.5)
            })
        }
        progress(1f)
        val lines = buildList {
            val counted = zs.flatMap { it.points }.map { it.id }.toSet().size
            add("${zs.size} 个面，共落入 $counted 个点（全部 ${input.of(setOf(GeometryType.POINT)).size} 个）")
            zs.sortedByDescending { it.points.size }.take(SUMMARY_ROWS).forEach { z ->
                val parts = buildList {
                    add("${z.points.size} 个点")
                    add(CoordinateFormatter.area(z.areaM2))
                    if (z.areaM2 > 0) add("${fmt(z.perKm2, 1)} 个/km²")
                    if (z.categories.isNotEmpty()) add(z.categories.take(3).joinToString("、") { (k, n) -> "$k $n" })
                    if (z.values.isNotEmpty()) add("「$valKey」平均 ${fmt(z.values.average())}（${fmt(z.values.min())}–${fmt(z.values.max())}）")
                }
                add("${z.polygon.title ?: "未命名"}：" + parts.joinToString("，"))
            }
            if (zs.size > SUMMARY_ROWS) add("其余 ${zs.size - SUMMARY_ROWS} 个面见结果的属性")
        }
        return ToolOutput(out, lines.joinToString("\n"), ramp.legend { fmt(it, 0) + " 个点" })
    }

    companion object {
        const val COUNT = "point_count"
        const val AREA_KM2 = "area_km2"
        const val PER_KM2 = "points_per_km2"
        const val CATEGORIES = "categories"
        const val MIN = "value_min"
        const val MAX = "value_max"
        const val MEAN = "value_mean"
        const val SOURCE = "source_polygon"
        private const val SUMMARY_ROWS = 6

        private fun round(v: Double, digits: Int) = fmt(v, digits).toDouble()

        private fun positions(g: Geometry?): List<LngLat> = when (g) {
            is Geometry.Point -> listOf(g.position)
            is Geometry.MultiPoint -> g.positions
            else -> emptyList()
        }

        /** Inside the outer ring (or on its edge) and not inside a hole. */
        fun contains(g: Geometry, p: LngLat): Boolean = when (g) {
            is Geometry.Polygon -> inPolygon(g.rings, p)
            is Geometry.MultiPolygon -> g.polygons.any { inPolygon(it.rings, p) }
            is Geometry.GeometryCollection -> g.geometries.any { contains(it, p) }
            else -> false
        }

        private fun inPolygon(rings: List<List<LngLat>>, p: LngLat): Boolean {
            val outer = rings.firstOrNull() ?: return false
            if (onEdge(outer, p)) return true
            if (!inRing(outer, p)) return false
            return rings.drop(1).none { inRing(it, p) && !onEdge(it, p) }
        }

        /** Even-odd ray casting. */
        private fun inRing(ring: List<LngLat>, p: LngLat): Boolean {
            var inside = false
            var j = ring.lastIndex
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[j]
                if ((a.lat > p.lat) != (b.lat > p.lat) &&
                    p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        private fun onEdge(ring: List<LngLat>, p: LngLat): Boolean = ring.zipWithNext().any { (a, b) ->
            val cross = (b.lng - a.lng) * (p.lat - a.lat) - (b.lat - a.lat) * (p.lng - a.lng)
            kotlin.math.abs(cross) < 1e-12 &&
                p.lng in minOf(a.lng, b.lng) - 1e-12..maxOf(a.lng, b.lng) + 1e-12 &&
                p.lat in minOf(a.lat, b.lat) - 1e-12..maxOf(a.lat, b.lat) + 1e-12
        }
    }
}
