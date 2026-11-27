package org.geocrew.android

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.hypot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/** A point on the screen, in pixels. */
internal data class Px(val x: Double, val y: Double)

/** What a point on the screen snaps to: a vertex, by its place in the list, or a point on an edge. */
internal sealed interface SnapHit {
    data class Vertex(val index: Int) : SnapHit

    data class Edge(val at: Px) : SnapHit
}

/**
 * Where [at] snaps to among [vertices] and the [edges] between them (pairs of
 * places in [vertices]), all on the screen: the nearest vertex within [reach],
 * else the nearest point on an edge within it; null when nothing is that near.
 */
internal fun snapOnScreen(at: Px, vertices: List<Px>, edges: List<Pair<Int, Int>>, reach: Double): SnapHit? {
    fun away(p: Px) = hypot(p.x - at.x, p.y - at.y)
    val vertex = vertices.indices.minByOrNull { away(vertices[it]) }
    if (vertex != null && away(vertices[vertex]) <= reach) return SnapHit.Vertex(vertex)
    var best: Px? = null
    var nearest = reach
    for ((a, b) in edges) {
        val p = nearestOn(at, vertices[a], vertices[b])
        val d = away(p)
        if (d <= nearest) {
            nearest = d
            best = p
        }
    }
    return best?.let { SnapHit.Edge(it) }
}

/** The point of the segment from [a] to [b] nearest to [p]. */
internal fun nearestOn(p: Px, a: Px, b: Px): Px {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length2 = dx * dx + dy * dy
    val t = if (length2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length2).coerceIn(0.0, 1.0)
    return Px(a.x + t * dx, a.y + t * dy)
}

/**
 * The vertices of a GeoJSON [geometry] part by part, as (longitude, latitude):
 * a point alone, a line, each ring of an area (closed, as GeoJSON keeps it).
 */
internal fun partsOf(geometry: JsonObject?): List<List<Pair<Double, Double>>> {
    val type = (geometry?.get("type") as? JsonPrimitive)?.contentOrNull ?: return emptyList()
    val coordinates = geometry["coordinates"] as? JsonArray ?: return emptyList()
    fun position(e: JsonElement?): Pair<Double, Double>? {
        val xy = e as? JsonArray ?: return null
        val x = (xy.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val y = (xy.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        return x to y
    }
    fun line(e: JsonElement?) = (e as? JsonArray).orEmpty().mapNotNull(::position)
    return when (type) {
        "Point" -> listOfNotNull(position(coordinates)?.let { listOf(it) })
        "MultiPoint" -> coordinates.mapNotNull { e -> position(e)?.let { listOf(it) } }
        "LineString" -> listOf(line(coordinates))
        "MultiLineString", "Polygon" -> coordinates.map(::line)
        "MultiPolygon" -> coordinates.flatMap { polygon -> (polygon as? JsonArray).orEmpty().map(::line) }
        else -> emptyList()
    }.filter { it.isNotEmpty() }
}

/**
 * Where a point at [at] on the screen snaps to while [drawing]: a vertex or an
 * edge of a feature drawn near it in [layerIds] (found in [features] by its
 * map id, for its exact coordinates: the map's own are cut to tiles and
 * rounded), or of the drawing itself, but not the vertex being moved, nor the
 * feature being reshaped ([reshaped], its map id). Null when nothing is
 * within [reach] pixels.
 */
internal fun snap(
    map: MapLibreMap,
    at: PointF,
    drawing: Drawing,
    features: Map<String, JsonObject>,
    reshaped: String?,
    reach: Float,
    layerIds: Array<String>,
): LatLng? {
    val box = RectF(at.x - reach, at.y - reach, at.x + reach, at.y + reach)
    val ids = map.queryRenderedFeatures(box, *layerIds).mapNotNull { it.id() }.distinct().filter { it != reshaped }
    val places = ArrayList<LatLng>()
    val edges = ArrayList<Pair<Int, Int>>()
    for (id in ids) {
        for (part in partsOf(features[id]?.get("geometry") as? JsonObject)) {
            val start = places.size
            part.mapTo(places) { (x, y) -> LatLng(y, x) }
            for (i in 1 until part.size) edges += (start + i - 1) to (start + i)
        }
    }
    // The drawing's own vertices and sides, without the vertex being moved and the sides that end at it.
    val own = drawing.vertices
    val moved = if (drawing.moving) drawing.picked else null
    val placeOf = HashMap<Int, Int>()
    for (i in own.indices) {
        if (i == moved) continue
        placeOf[i] = places.size
        places += own[i]
    }
    val sides = own.indices.zipWithNext() + if (drawing.area && own.size >= 3) listOf(own.size - 1 to 0) else emptyList()
    for ((a, b) in sides) edges += (placeOf[a] ?: continue) to (placeOf[b] ?: continue)
    if (places.isEmpty()) return null
    val screen = places.map { map.projection.toScreenLocation(it).let { p -> Px(p.x.toDouble(), p.y.toDouble()) } }
    return when (val hit = snapOnScreen(Px(at.x.toDouble(), at.y.toDouble()), screen, edges, reach.toDouble())) {
        null -> null
        is SnapHit.Vertex -> places[hit.index]
        is SnapHit.Edge -> map.projection.fromScreenLocation(PointF(hit.at.x.toFloat(), hit.at.y.toFloat())).wrap()
    }
}
