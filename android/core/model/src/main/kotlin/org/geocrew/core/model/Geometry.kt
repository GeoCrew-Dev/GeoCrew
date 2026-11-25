package org.geocrew.core.model

/**
 * A WGS84 / CGCS2000 position. Longitude first, as in GeoJSON.
 * [alt] is metres above the ellipsoid when known.
 */
data class LngLat(val lng: Double, val lat: Double, val alt: Double? = null) {
    init {
        require(lng in -180.0..180.0) { "longitude out of range: $lng" }
        require(lat in -90.0..90.0) { "latitude out of range: $lat" }
    }
}

/** GeoJSON geometry types (RFC 7946). */
sealed interface Geometry {
    val type: String

    data class Point(val position: LngLat) : Geometry {
        override val type get() = "Point"
    }

    data class MultiPoint(val positions: List<LngLat>) : Geometry {
        override val type get() = "MultiPoint"
    }

    data class LineString(val positions: List<LngLat>) : Geometry {
        override val type get() = "LineString"
    }

    data class MultiLineString(val lines: List<List<LngLat>>) : Geometry {
        override val type get() = "MultiLineString"
    }

    /** First ring is the exterior; the rest are holes. Rings are closed (first == last). */
    data class Polygon(val rings: List<List<LngLat>>) : Geometry {
        override val type get() = "Polygon"
        val exterior: List<LngLat> get() = rings.firstOrNull().orEmpty()

        companion object {
            /** Builds a polygon from an open or closed list of vertices. */
            fun of(vertices: List<LngLat>): Polygon {
                require(vertices.size >= 3) { "a polygon needs at least 3 vertices" }
                val closed = if (vertices.first().sameXY(vertices.last())) vertices else vertices + vertices.first()
                return Polygon(listOf(closed))
            }
        }
    }

    data class MultiPolygon(val polygons: List<Polygon>) : Geometry {
        override val type get() = "MultiPolygon"
    }

    data class GeometryCollection(val geometries: List<Geometry>) : Geometry {
        override val type get() = "GeometryCollection"
    }
}

internal fun LngLat.sameXY(o: LngLat) = lng == o.lng && lat == o.lat

/** Every position in the geometry, in order. */
fun Geometry.positions(): List<LngLat> = when (this) {
    is Geometry.Point -> listOf(position)
    is Geometry.MultiPoint -> positions
    is Geometry.LineString -> positions
    is Geometry.MultiLineString -> lines.flatten()
    is Geometry.Polygon -> rings.flatten()
    is Geometry.MultiPolygon -> polygons.flatMap { it.rings.flatten() }
    is Geometry.GeometryCollection -> geometries.flatMap { it.positions() }
}

/** Axis-aligned bounds, or null for an empty geometry. */
data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    val center get() = LngLat((west + east) / 2, (south + north) / 2)

    fun union(o: Bounds) = Bounds(minOf(west, o.west), minOf(south, o.south), maxOf(east, o.east), maxOf(north, o.north))

    companion object {
        fun of(points: Iterable<LngLat>): Bounds? {
            val list = points.toList()
            if (list.isEmpty()) return null
            return Bounds(list.minOf { it.lng }, list.minOf { it.lat }, list.maxOf { it.lng }, list.maxOf { it.lat })
        }
    }
}

fun Geometry.bounds(): Bounds? = Bounds.of(positions())
