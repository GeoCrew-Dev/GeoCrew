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
import org.geocrew.core.model.LngLat

/** The user's own position as drawn on the map. */
data class MyLocation(val position: LngLat, val accuracy: Float?, val bearing: Float?)

/** Another team member's live position. */
data class TeamMarker(val memberId: String, val nickname: String, val position: LngLat, val accuracy: Float?, val stale: Boolean)

/** Everything drawn above the base map. */
data class MapOverlays(
    val features: List<GeoFeature> = emptyList(),
    val selectedId: String? = null,
    val me: MyLocation? = null,
    val team: List<TeamMarker> = emptyList(),
    /** Vertices of a line or polygon being drawn. */
    val sketch: List<LngLat> = emptyList(),
    val sketchClosed: Boolean = false,
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

    fun features(list: List<GeoFeature>, selectedId: String?): String = collection(
        list.mapNotNull { f ->
            val g = f.geometry ?: return@mapNotNull null
            feature(g) {
                this["fid"] = f.id
                this["kind"] = f.kind.id
                this["selected"] = f.id == selectedId
                if (g is Geometry.Point) f.attitude?.let { a ->
                    this["dipdir"] = a.dipDirection
                    this["dip"] = Math.round(a.dip).toDouble()
                }
                simpleStyle(f.properties)
            }
        },
    )

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
        list.map { m ->
            feature(Geometry.Point(m.position)) {
                this["mid"] = m.memberId
                this["name"] = m.nickname
                this["stale"] = m.stale
            }
        },
    )

    fun sketch(points: List<LngLat>, closed: Boolean): String = collection(
        buildList {
            if (points.size >= 2) {
                val line = if (closed && points.size >= 3) points + points.first() else points
                add(feature(Geometry.LineString(line)))
            }
            points.forEach { add(feature(Geometry.Point(it)) { this["vertex"] = true }) }
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
    fun build() = JsonObject(map)
}
