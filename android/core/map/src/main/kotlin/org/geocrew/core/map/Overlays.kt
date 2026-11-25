package org.geocrew.core.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.formats.GeoJson
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Legend
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.TaskStatus
import org.geocrew.core.model.lineStyle
import org.geocrew.core.model.meetAt
import org.geocrew.core.model.task

/** The user's own position as drawn on the map. */
data class MyLocation(val position: LngLat, val accuracy: Float?, val bearing: Float?)

/** Another team member's live position. */
data class TeamMarker(
    val memberId: String,
    val nickname: String,
    val position: LngLat,
    val accuracy: Float?,
    val stale: Boolean,
    /** Where they walked recently, oldest first; drawn as a thin line behind the dot. */
    val trail: List<LngLat> = emptyList(),
)

/** Everything drawn above the base map. */
data class MapOverlays(
    val features: List<GeoFeature> = emptyList(),
    val selectedId: String? = null,
    val me: MyLocation? = null,
    val team: List<TeamMarker> = emptyList(),
    /** Vertices of a line or polygon being drawn. */
    val sketch: List<LngLat> = emptyList(),
    val sketchClosed: Boolean = false,
    /** Index of the sketch vertex picked up for moving, drawn larger. */
    val sketchPicked: Int? = null,
    /** This device's member id in the project's workspace; its open tasks are highlighted. */
    val memberId: String? = null,
    /** Colours records by an attribute where they have no colour of their own. */
    val legend: Legend = Legend(),
    /** Kilometre grid lines over the current view; see [org.geocrew.core.model.PlaneGrid]. */
    val grid: List<org.geocrew.core.model.PlaneGrid.Line> = emptyList(),
    /** Map sheet frames over the current view (see [org.geocrew.core.model.MapSheet]). */
    val sheets: List<org.geocrew.core.model.Bounds> = emptyList(),
    /** Pictures pinned to the map under everything else, bottom first. */
    val images: List<MapImage> = emptyList(),
)

/** A decoded picture to draw over the base map, stretched to its four corners (top left first, clockwise). */
data class MapImage(
    val id: String,
    val bitmap: android.graphics.Bitmap,
    val quad: List<LngLat>,
    val opacity: Float,
)

/** Converts overlays into GeoJSON strings for MapLibre sources. */
internal object OverlayGeoJson {
    private fun collection(features: List<JsonObject>) = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }.toString()

    private fun feature(g: Geometry, props: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", "Feature")
        put("geometry", GeoJson.encodeGeometry(g))
        put("properties", JsonObjectBuilder().apply(props).build())
    }

    fun features(list: List<GeoFeature>, selectedId: String?, memberId: String? = null, legend: Legend = Legend()): String = collection(
        list.mapNotNull { f ->
            val g = f.geometry ?: return@mapNotNull null
            record(f, g, selectedId, memberId, legend)
        } + list.mapNotNull(::attitudeAnchor),
    )

    /**
     * A line or polygon with an attitude (a fault plane, a contact) gets its
     * strike-and-dip symbol on an extra point half way along it, which
     * selects the record itself when tapped.
     */
    private fun attitudeAnchor(f: GeoFeature): JsonObject? {
        val g = f.geometry ?: return null
        if (g is Geometry.Point || g is Geometry.MultiPoint) return null
        val a = f.attitude ?: return null
        val path = when (g) {
            is Geometry.LineString -> g.positions
            is Geometry.MultiLineString -> g.lines.maxByOrNull { Geodesy.length(it) }
            is Geometry.Polygon -> g.rings.firstOrNull()
            is Geometry.MultiPolygon -> g.polygons.firstOrNull()?.rings?.firstOrNull()
            else -> null
        } ?: return null
        val at = Geodesy.midpoint(path) ?: return null
        return feature(Geometry.Point(at)) {
            this["fid"] = f.id
            this["kind"] = f.kind.id
            this["anchor"] = true
            this["dipdir"] = a.dipDirection
            this["dip"] = Math.round(a.dip).toDouble()
        }
    }

    private fun record(f: GeoFeature, g: Geometry, selectedId: String?, memberId: String?, legend: Legend): JsonObject =
            feature(g) {
                this["fid"] = f.id
                this["kind"] = f.kind.id
                this["selected"] = f.id == selectedId
                if (memberId != null) f.task?.let { t ->
                    if (t.assignee == memberId && t.status != TaskStatus.DONE) this["mytask"] = true
                }
                if (f.meetAt != null) this["meet"] = true
                if (g is Geometry.Point) f.attitude?.let { a ->
                    this["dipdir"] = a.dipDirection
                    this["dip"] = Math.round(a.dip).toDouble()
                }
                if (g is Geometry.Point) f.plunge?.let { l ->
                    this["trend"] = l.trend
                    this["plunge"] = Math.round(l.plunge).toDouble()
                }
                if (g !is Geometry.Point && g !is Geometry.MultiPoint) f.lineStyle?.let { ls ->
                    if (ls.dashed) this["dash"] = true
                    this[if (ls.fault) "fault" else "contact"] = true
                }
                simpleStyle(f.properties)
                // A record that brings its own colour (analysis output, an imported file) keeps it.
                if (COLOR_KEYS.none(::has)) legend.colorOf(f)?.takeIf { HEX_COLOR.matches(it) }?.let { c ->
                    for (k in COLOR_KEYS) this[k] = c
                    if ((g is Geometry.Polygon || g is Geometry.MultiPolygon) && !has("fill-opacity")) this["fill-opacity"] = 0.45
                }
            }

    /**
     * Copies GeoJSON simplestyle keys (used by analysis output and by files
     * from other GIS) so layers can colour features individually. Values are
     * checked here so a bad file cannot break the style expressions.
     */
    private fun JsonObjectBuilder.simpleStyle(p: JsonObject) {
        for (k in COLOR_KEYS) {
            val v = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (v != null && HEX_COLOR.matches(v)) this[k] = v
        }
        for ((k, range) in NUMBER_KEYS) {
            val v = (p[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            if (v != null && v in range) this[k] = v
        }
    }

    private val COLOR_KEYS = listOf("fill", "stroke", "marker-color")
    private val NUMBER_KEYS = listOf("fill-opacity" to 0.0..1.0, "stroke-width" to 0.0..20.0)
    private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

    fun me(me: MyLocation?): String = collection(
        buildList {
            if (me == null) return@buildList
            val r = me.accuracy?.toDouble()
            if (r != null && r > 1) add(feature(circle(me.position, r)) { this["role"] = "accuracy" })
            add(feature(Geometry.Point(me.position)) { this["role"] = "dot"; this["bearing"] = me.bearing?.toDouble() })
        },
    )

    fun team(list: List<TeamMarker>): String = collection(
        list.flatMap { m ->
            buildList {
                val path = m.trail + m.position
                if (path.size >= 2) add(feature(Geometry.LineString(path)) { this["mid"] = m.memberId; this["stale"] = m.stale })
                add(
                    feature(Geometry.Point(m.position)) {
                        this["mid"] = m.memberId
                        this["name"] = m.nickname
                        this["stale"] = m.stale
                    },
                )
            }
        },
    )

    fun sketch(points: List<LngLat>, closed: Boolean, picked: Int? = null): String = collection(
        buildList {
            if (points.size >= 2) {
                val line = if (closed && points.size >= 3) points + points.first() else points
                add(feature(Geometry.LineString(line)))
            }
            points.forEachIndexed { i, p ->
                add(feature(Geometry.Point(p)) { this["vertex"] = true; if (i == picked) this["picked"] = true })
            }
        },
    )

    fun grid(lines: List<org.geocrew.core.model.PlaneGrid.Line>): String = collection(
        lines.map { l -> feature(Geometry.LineString(l.positions)) { this["northing"] = l.northing } },
    )

    fun sheets(frames: List<org.geocrew.core.model.Bounds>): String = collection(
        frames.map { b ->
            feature(Geometry.LineString(listOf(LngLat(b.west, b.south), LngLat(b.east, b.south), LngLat(b.east, b.north), LngLat(b.west, b.north), LngLat(b.west, b.south))))
        },
    )

    /** Accuracy circle as a 48-gon. */
    fun circle(center: LngLat, radius: Double): Geometry.Polygon =
        Geometry.Polygon.of((0 until 48).map { Geodesy.destination(center, it * 7.5, radius) })
}

/** Tiny builder so property maps read naturally above. */
internal class JsonObjectBuilder {
    private val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    operator fun set(k: String, v: String?) { map[k] = v?.let(::JsonPrimitive) ?: JsonNull }
    operator fun set(k: String, v: Boolean) { map[k] = JsonPrimitive(v) }
    operator fun set(k: String, v: Double?) { map[k] = v?.let(::JsonPrimitive) ?: JsonNull }
    fun has(k: String) = k in map
    fun build() = JsonObject(map)
}
