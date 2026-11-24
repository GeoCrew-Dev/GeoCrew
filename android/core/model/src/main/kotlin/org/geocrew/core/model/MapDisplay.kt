package org.geocrew.core.model

import kotlinx.serialization.Serializable

/**
 * What a project's map shows on top of the base map: which record kinds,
 * whether record names are written next to them, teammates' positions, and
 * an "only my open tasks" view for team work. Kept per project and device;
 * it only changes the display, never the data.
 */
@Serializable
data class MapDisplay(
    /** Kind ids (see [FeatureKind]) not drawn on the map. */
    val hiddenKinds: List<String> = emptyList(),
    /** Write each record's name next to it. */
    val names: Boolean = false,
    /** Draw teammates' live positions. */
    val team: Boolean = true,
    /** Only records assigned to me and not done (team projects). */
    val onlyMyTasks: Boolean = false,
) {
    fun shows(kind: FeatureKind) = kind.id !in hiddenKinds

    fun toggle(kind: FeatureKind) = copy(
        hiddenKinds = if (shows(kind)) hiddenKinds + kind.id else hiddenKinds - kind.id,
    )

    /** True when anything is filtered out, so the map can say so. */
    val filtering: Boolean get() = hiddenKinds.isNotEmpty() || onlyMyTasks

    /**
     * The records to draw. [keep] (the selected record, say) is always
     * kept so the user never loses what they are looking at.
     */
    fun filter(features: List<GeoFeature>, memberId: String?, keep: String? = null): List<GeoFeature> {
        if (!filtering) return features
        return features.filter { f ->
            f.id == keep || (shows(f.kind) && (!onlyMyTasks || f.isOpenTaskOf(memberId)))
        }
    }

    private fun GeoFeature.isOpenTaskOf(memberId: String?): Boolean {
        val t = task ?: return false
        return memberId != null && t.assignee == memberId && t.status != TaskStatus.DONE
    }

    companion object {
        /**
         * Where a record's name goes: the point itself, the middle vertex
         * of a line, the average of a polygon's outer ring. Null for empty
         * geometry.
         */
        fun anchor(g: Geometry?): LngLat? = when (g) {
            null -> null
            is Geometry.Point -> g.position
            is Geometry.MultiPoint -> g.positions.firstOrNull()
            is Geometry.LineString -> g.positions.getOrNull(g.positions.size / 2)
            is Geometry.MultiLineString -> g.lines.maxByOrNull { it.size }?.let { it.getOrNull(it.size / 2) }
            is Geometry.Polygon -> ringCenter(g.rings.firstOrNull())
            is Geometry.MultiPolygon -> g.polygons.maxByOrNull { it.rings.firstOrNull()?.size ?: 0 }?.let { ringCenter(it.rings.firstOrNull()) }
            is Geometry.GeometryCollection -> g.geometries.firstNotNullOfOrNull(::anchor)
        }

        private fun ringCenter(ring: List<LngLat>?): LngLat? {
            if (ring.isNullOrEmpty()) return null
            // A closed ring repeats its first vertex; count it once.
            val pts = if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
            return LngLat(pts.sumOf { it.lng } / pts.size, pts.sumOf { it.lat } / pts.size)
        }
    }
}
