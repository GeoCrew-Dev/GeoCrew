package org.geocrew.core.formats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.edit
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/**
 * GeoJSON (RFC 7946) encoding shared by file import/export, the sync
 * protocol and GIS tool plugins.
 */
object GeoJson {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encodeGeometry(g: Geometry): JsonObject = buildJsonObject {
        put("type", g.type)
        when (g) {
            is Geometry.Point -> put("coordinates", pos(g.position))
            is Geometry.MultiPoint -> put("coordinates", JsonArray(g.positions.map(::pos)))
            is Geometry.LineString -> put("coordinates", JsonArray(g.positions.map(::pos)))
            is Geometry.MultiLineString -> put("coordinates", JsonArray(g.lines.map { l -> JsonArray(l.map(::pos)) }))
            is Geometry.Polygon -> put("coordinates", rings(g))
            is Geometry.MultiPolygon -> put("coordinates", JsonArray(g.polygons.map(::rings)))
            is Geometry.GeometryCollection -> put("geometries", JsonArray(g.geometries.map(::encodeGeometry)))
        }
    }

    private fun rings(p: Geometry.Polygon) = JsonArray(p.rings.map { r -> JsonArray(r.map(::pos)) })

    private fun pos(p: LngLat): JsonArray = JsonArray(
        listOfNotNull(JsonPrimitive(p.lng), JsonPrimitive(p.lat), p.alt?.let(::JsonPrimitive)),
    )

    fun decodeGeometry(e: JsonElement?): Geometry? {
        if (e == null || e is JsonNull) return null
        val o = e.jsonObject
        val c = o["coordinates"]
        return when (val type = o["type"]?.jsonPrimitive?.contentOrNull) {
            "Point" -> Geometry.Point(decodePos(c!!))
            "MultiPoint" -> Geometry.MultiPoint(c!!.jsonArray.map(::decodePos))
            "LineString" -> Geometry.LineString(c!!.jsonArray.map(::decodePos))
            "MultiLineString" -> Geometry.MultiLineString(c!!.jsonArray.map { l -> l.jsonArray.map(::decodePos) })
            "Polygon" -> decodePolygon(c!!)
            "MultiPolygon" -> Geometry.MultiPolygon(c!!.jsonArray.map(::decodePolygon))
            "GeometryCollection" -> Geometry.GeometryCollection(o["geometries"]!!.jsonArray.mapNotNull(::decodeGeometry))
            else -> throw FormatException("unsupported geometry type: $type")
        }
    }

    private fun decodePolygon(c: JsonElement) = Geometry.Polygon(c.jsonArray.map { r -> r.jsonArray.map(::decodePos) })

    private fun decodePos(e: JsonElement): LngLat {
        val a = e.jsonArray
        if (a.size < 2) throw FormatException("position needs at least 2 numbers")
        return LngLat(
            a[0].jsonPrimitive.doubleOrNull ?: throw FormatException("bad longitude"),
            a[1].jsonPrimitive.doubleOrNull ?: throw FormatException("bad latitude"),
            a.getOrNull(2)?.jsonPrimitive?.doubleOrNull,
        )
    }

    /**
     * Encodes a feature. The kind travels as properties.kind so other GIS
     * software sees it as an ordinary attribute.
     */
    fun encodeFeature(f: GeoFeature, includeSync: Boolean = false): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("id", f.id)
        put("geometry", f.geometry?.let(::encodeGeometry) ?: JsonNull)
        put("properties", f.properties.edit { this[Props.KIND] = f.kind.id })
        if (includeSync) {
            put("sync", buildJsonObject {
                put("version", f.sync.version)
                put("updated_at", f.sync.updatedAt.toString())
                f.sync.updatedBy?.let { put("updated_by", it) }
                put("deleted", f.sync.deleted)
                f.sync.seq?.let { put("seq", it) }
            })
        }
    }

    fun encodeCollection(features: List<GeoFeature>, includeSync: Boolean = false): JsonObject = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features.map { encodeFeature(it, includeSync) }))
    }

    /** Infers a kind for features coming from other software. */
    fun inferKind(g: Geometry?): FeatureKind = when (g) {
        is Geometry.Point, is Geometry.MultiPoint -> FeatureKind.POINT
        is Geometry.LineString, is Geometry.MultiLineString -> FeatureKind.LINE
        is Geometry.Polygon, is Geometry.MultiPolygon -> FeatureKind.POLYGON
        else -> FeatureKind.POINT
    }

    fun decodeFeature(o: JsonObject, ctx: ImportContext): GeoFeature {
        val geometry = decodeGeometry(o["geometry"])
        val props = (o["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val kind = props[Props.KIND]?.jsonPrimitive?.contentOrNull?.let(::FeatureKind) ?: inferKind(geometry)
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 64 } ?: ctx.newId()
        val sync = (o["sync"] as? JsonObject)?.let { s ->
            SyncInfo(
                version = s["version"]?.jsonPrimitive?.longOrNull ?: 1,
                updatedAt = s["updated_at"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse) ?: ctx.now,
                updatedBy = s["updated_by"]?.jsonPrimitive?.contentOrNull,
                deleted = s["deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
                seq = s["seq"]?.jsonPrimitive?.longOrNull,
                dirty = false,
            )
        } ?: SyncInfo(updatedAt = ctx.now)
        return GeoFeature(
            id = id, projectId = ctx.projectId, kind = kind, geometry = geometry,
            properties = props.edit { remove(Props.KIND) }, createdAt = ctx.now, sync = sync,
        )
    }

    /** Accepts a FeatureCollection, a single Feature or a bare geometry. */
    fun decode(text: String, ctx: ImportContext): ImportResult {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw FormatException("not a GeoJSON object", e)
        }
        val warnings = mutableListOf<String>()
        val features = when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "FeatureCollection" -> root["features"]?.jsonArray.orEmpty().mapIndexedNotNull { i, e ->
                try {
                    decodeFeature(e.jsonObject, ctx)
                } catch (ex: Exception) {
                    warnings += "feature $i skipped: ${ex.message}"
                    null
                }
            }
            "Feature" -> listOf(decodeFeature(root, ctx))
            else -> listOf(decodeFeature(buildJsonObject { put("type", "Feature"); put("geometry", root) }, ctx))
        }
        return ImportResult(features, warnings)
    }
}

object GeoJsonFormat : FeatureFormat {
    override val id = "geojson"
    override val displayName = "GeoJSON"
    override val extension = "geojson"
    override val mimeType = "application/geo+json"
    override val canRead = true
    override val canWrite = true

    override fun read(input: InputStream, ctx: ImportContext) =
        GeoJson.decode(input.bufferedReader(Charsets.UTF_8).readText(), ctx)

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val live = features.filterNot { it.deleted }
        output.write(GeoJson.encodeCollection(live).toString().toByteArray(Charsets.UTF_8))
        return ExportResult(live.size)
    }
}
