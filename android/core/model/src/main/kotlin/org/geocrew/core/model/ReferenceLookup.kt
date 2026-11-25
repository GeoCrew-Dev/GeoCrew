package org.geocrew.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * What the reference layers say about a spot: the areas it falls in (a map
 * unit, a licence) and the nearest line (a fault, a boundary) within reach.
 * Lets a geologist ask "这里是什么地层" against an old map in the field.
 */
object ReferenceLookup {
    /** An area that contains the spot. */
    data class Area(val layer: String, val value: String?, val attributes: Map<String, String>)

    /** The nearest line of a layer and how far it is. */
    data class Near(val layer: String, val value: String?, val meters: Double)

    data class Result(val areas: List<Area>, val lines: List<Near>) {
        val empty get() = areas.isEmpty() && lines.isEmpty()

        /** One or two Chinese sentences for the assistant to say. */
        fun text(): String {
            if (empty) return "这里不在任何参考图层的图形里。"
            val parts = mutableListOf<String>()
            if (areas.isNotEmpty()) {
                parts += "这里在" + areas.joinToString("、") { a -> "「${a.layer}」的" + (a.value?.let { "「$it」" } ?: "一个图斑") + "里" }
            }
            lines.forEach { n -> parts += "离「${n.layer}」的" + (n.value?.let { "「$it」" } ?: "一条线") + "约 ${n.meters.toInt()} 米" }
            return parts.joinToString("；") + "。"
        }
    }

    /**
     * Looks [at] up in [layers], each with its shapes, top layer last as
     * drawn. Areas are listed top layer first; for each layer only the
     * nearest line within [lineReachM] metres is given.
     */
    fun at(at: LngLat, layers: List<Pair<ReferenceLayer, List<GeoFeature>>>, lineReachM: Double = 200.0): Result {
        val areas = mutableListOf<Area>()
        val lines = mutableListOf<Near>()
        layers.asReversed().forEach { (layer, shapes) ->
            fun value(f: GeoFeature) = layer.colorBy?.let { ReferenceLayer.text(f, it) }
            shapes.filter { f -> f.geometry?.let { contains(it, at) } == true }.forEach { f ->
                val attrs = f.properties.keys.filter { it != Props.KIND }.mapNotNull { k -> ReferenceLayer.text(f, k)?.let { k to it } }.toMap()
                areas += Area(layer.name, value(f), attrs)
            }
            shapes.mapNotNull { f -> lineDistance(f.geometry, at)?.let { f to it } }
                .filter { it.second <= lineReachM }
                .minByOrNull { it.second }
                ?.let { (f, d) -> lines += Near(layer.name, value(f), d) }
        }
        return Result(areas, lines)
    }

    fun contains(g: Geometry, p: LngLat): Boolean = when (g) {
        is Geometry.Polygon -> inPolygon(g.rings, p)
        is Geometry.MultiPolygon -> g.polygons.any { inPolygon(it.rings, p) }
        is Geometry.GeometryCollection -> g.geometries.any { contains(it, p) }
        else -> false
    }

    private fun inPolygon(rings: List<List<LngLat>>, p: LngLat): Boolean {
        val outer = rings.firstOrNull() ?: return false
        return inRing(outer, p) && rings.drop(1).none { inRing(it, p) }
    }

    /** Even-odd ray casting. */
    private fun inRing(ring: List<LngLat>, p: LngLat): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng) inside = !inside
            j = i
        }
        return inside
    }

    private fun lineDistance(g: Geometry?, p: LngLat): Double? {
        val parts = when (g) {
            is Geometry.LineString -> listOf(g.positions)
            is Geometry.MultiLineString -> g.lines
            else -> return null
        }
        return parts.flatMap { it.zipWithNext() }.minOfOrNull { (a, b) -> segment(p, a, b) }
    }

    /** Metres from [p] to segment [a]–[b], on a local flat projection (fine over a few kilometres). */
    private fun segment(p: LngLat, a: LngLat, b: LngLat): Double {
        val my = 6_371_000.0 * PI / 180
        val mx = my * cos(p.lat * PI / 180)
        val ax = (a.lng - p.lng) * mx; val ay = (a.lat - p.lat) * my
        val bx = (b.lng - p.lng) * mx; val by = (b.lat - p.lat) * my
        val dx = bx - ax; val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }
}
