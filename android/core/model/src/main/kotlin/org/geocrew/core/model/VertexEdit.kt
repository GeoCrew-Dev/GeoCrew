package org.geocrew.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Reshaping a record on the map: which records can be reshaped, which vertex
 * a tap lands on, and where a tapped new vertex belongs. Distances use a local
 * equirectangular plane around the tap, which at field scales is good to far
 * better than a finger's width.
 */
object VertexEdit {
    /** Point-like kinds that are moved as a whole. */
    private val points = setOf(FeatureKind.POINT, FeatureKind.GEO_POINT, FeatureKind.PHOTO)

    /** Kinds drawn by hand; tracks, sections and routes get their shape from other data. */
    private val shapes = setOf(FeatureKind.LINE, FeatureKind.POLYGON)

    /** What the user can do to [f]'s shape on the map. */
    sealed interface Edit {
        data class Move(val position: LngLat) : Edit
        data class Reshape(val vertices: List<LngLat>, val closed: Boolean) : Edit
        /** Not editable, with the reason to show. */
        data class No(val reason: String) : Edit
    }

    fun of(f: GeoFeature): Edit {
        val g = f.geometry
        return when {
            f.kind == FeatureKind.TRACK -> Edit.No("轨迹按记录的定位点生成，不能改形状")
            f.kind == FeatureKind.SECTION -> Edit.No("实测剖面的线由导线推算，请在剖面里改导线")
            f.kind == FeatureKind.ROUTE -> Edit.No("路线按各点位置连成，改点的位置即可")
            g == null -> Edit.No("这条记录没有位置")
            f.kind in points && g is Geometry.Point -> Edit.Move(g.position)
            f.kind in shapes || g is Geometry.LineString || g is Geometry.Polygon -> when (g) {
                is Geometry.LineString -> if (g.positions.size >= 2) Edit.Reshape(g.positions, false) else Edit.No("线的节点太少")
                is Geometry.Polygon -> {
                    val ring = open(g.exterior)
                    when {
                        g.rings.size > 1 -> Edit.No("带洞的面暂不支持在地图上改")
                        ring.size < 3 -> Edit.No("面的节点太少")
                        else -> Edit.Reshape(ring, true)
                    }
                }
                is Geometry.Point -> Edit.Move(g.position)
                else -> Edit.No("多部分的图形暂不支持在地图上改")
            }
            g is Geometry.Point -> Edit.Move(g.position)
            else -> Edit.No("这种图形暂不支持在地图上改")
        }
    }

    private fun open(ring: List<LngLat>) = if (ring.size > 1 && ring.first().sameXY(ring.last())) ring.dropLast(1) else ring

    /** The vertex nearest [p] if it is within [toleranceM] metres. */
    fun near(vertices: List<LngLat>, p: LngLat, toleranceM: Double): Int? {
        var best: Int? = null
        var bestD = Double.POSITIVE_INFINITY
        vertices.forEachIndexed { i, v ->
            val (x, y) = local(p, v)
            val d = hypot(x, y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best?.takeIf { bestD <= toleranceM }
    }

    /**
     * [vertices] with [p] put into the segment nearest to it. On an open line a
     * tap beyond either end extends the line there instead; a polygon's closing
     * segment counts like any other.
     */
    fun insert(vertices: List<LngLat>, p: LngLat, closed: Boolean): List<LngLat> {
        if (vertices.size < 2) return vertices + p
        val segments = if (closed && vertices.size >= 3) vertices.size else vertices.size - 1
        var best = 0
        var bestD = Double.POSITIVE_INFINITY
        var bestT = 0.0
        for (i in 0 until segments) {
            val (ax, ay) = local(p, vertices[i])
            val (bx, by) = local(p, vertices[(i + 1) % vertices.size])
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            // Tap is the origin, so t is where the perpendicular from (0, 0) meets the segment.
            val t = if (len2 == 0.0) 0.0 else -(ax * dx + ay * dy) / len2
            val c = t.coerceIn(0.0, 1.0)
            val d = hypot(ax + c * dx, ay + c * dy)
            if (d < bestD - 1e-9) { bestD = d; best = i; bestT = t }
        }
        if (!closed) {
            if (best == 0 && bestT < 0) return listOf(p) + vertices
            if (best == segments - 1 && bestT > 1) return vertices + p
        }
        return vertices.toMutableList().apply { add(best + 1, p) }
    }

    /** Fewest vertices a shape may keep when one is removed. */
    fun minimum(closed: Boolean) = if (closed) 3 else 2

    // Metres east and north of [origin].
    private fun local(origin: LngLat, q: LngLat): Pair<Double, Double> {
        val k = PI / 180 * 6_371_008.8
        var dLng = q.lng - origin.lng
        if (dLng > 180) dLng -= 360
        if (dLng < -180) dLng += 360
        return dLng * k * cos(origin.lat * PI / 180) to (q.lat - origin.lat) * k
    }
}
