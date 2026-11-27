package org.geocrew.storage

import java.io.Writer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.geocrew.core.project.Templates

/**
 * Layers of a project as GPX 1.1, for GPS units and navigation apps (ADR
 * 0004: exports): points become waypoints and lines tracks, in WGS84, each
 * named by its layer's name field and described by its description field.
 * GPX has no areas: a polygon layer adds nothing. Waypoints come before
 * tracks, as the schema has them.
 */
internal class Gpx(private val project: Project) {
    /** Whether [layer] has anything GPX holds: points or lines. */
    fun holds(layer: Project.Layer): Boolean {
        val g = layer.geometry?.uppercase() ?: return false
        return "POINT" in g || "LINE" in g || g == "GEOMETRY"
    }

    fun write(out: Writer, layers: List<Project.Layer>) {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        out.write("""<gpx version="1.1" creator="GeoCrew" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n")
        out.write("<metadata><name>${Kml.xml(project.name)}</name></metadata>\n")
        val read = layers.filter(::holds).map { layer ->
            val store = project.target(layer.name).store
            val names = store.fields.map { it.name }
            val features = store.features()
            Triple(layer, Pair(Kml.nameField(layer, names), descriptionField(layer, names)), features.zip(Crs.transform(features.map { it.geometry }, layer.srs ?: Crs.LONGITUDE_LATITUDE, WGS84)))
        }
        for ((_, fields, features) in read) {
            for ((feature, geometry) in features) {
                for (p in points(geometry)) {
                    out.write("<wpt ${latLon(p) ?: continue}>")
                    elevation(p)?.let { out.write("<ele>$it</ele>") }
                    texts(out, fields, feature.properties)
                    out.write("</wpt>\n")
                }
            }
        }
        for ((_, fields, features) in read) {
            for ((feature, geometry) in features) {
                val lines = lines(geometry).mapNotNull { line -> line.mapNotNull { p -> latLon(p)?.let { it to elevation(p) } }.takeIf { it.isNotEmpty() } }
                if (lines.isEmpty()) continue
                out.write("<trk>")
                texts(out, fields, feature.properties)
                for (line in lines) {
                    out.write("<trkseg>")
                    for ((at, ele) in line) out.write(if (ele == null) "<trkpt $at/>" else "<trkpt $at><ele>$ele</ele></trkpt>")
                    out.write("</trkseg>")
                }
                out.write("</trk>\n")
            }
        }
        out.write("</gpx>\n")
    }

    // A feature's name and description, from the fields that hold them.
    private fun texts(out: Writer, fields: Pair<String?, String?>, properties: JsonObject) {
        fields.first?.let { f -> text(properties[f])?.let { out.write("<name>${Kml.xml(it)}</name>") } }
        fields.second?.let { f -> text(properties[f])?.let { out.write("<desc>${Kml.xml(it)}</desc>") } }
    }

    // The positions of a point or multipoint (of a collection's too).
    private fun points(g: JsonObject?): List<JsonElement> = when (type(g)) {
        "Point" -> listOfNotNull(g?.get("coordinates"))
        "MultiPoint" -> (g?.get("coordinates") as? JsonArray).orEmpty()
        "GeometryCollection" -> (g?.get("geometries") as? JsonArray).orEmpty().flatMap { points(it as? JsonObject) }
        else -> emptyList()
    }

    // The lines of a line or multiline (of a collection's too), each a list of positions.
    private fun lines(g: JsonObject?): List<List<JsonElement>> = when (type(g)) {
        "LineString" -> listOfNotNull(g?.get("coordinates") as? JsonArray)
        "MultiLineString" -> (g?.get("coordinates") as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }
        "GeometryCollection" -> (g?.get("geometries") as? JsonArray).orEmpty().flatMap { lines(it as? JsonObject) }
        else -> emptyList()
    }

    private fun type(g: JsonObject?): String? = (g?.get("type") as? JsonPrimitive)?.contentOrNull

    private fun numbers(p: JsonElement): List<Double> = (p as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()

    // lat="…" lon="…", to nine places (well under a millimetre).
    private fun latLon(p: JsonElement): String? {
        val n = numbers(p)
        if (n.size < 2 || !n[0].isFinite() || !n[1].isFinite()) return null
        return "lat=\"${round(n[1], 9)}\" lon=\"${round(n[0], 9)}\""
    }

    private fun elevation(p: JsonElement): String? = numbers(p).getOrNull(2)?.takeIf { it.isFinite() }?.let { round(it, 3) }

    private fun round(v: Double, places: Int): String =
        java.math.BigDecimal(v).setScale(places, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun text(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() }
        else -> e.toString()
    }

    companion object {
        private const val WGS84 = "EPSG:4326"

        // Fields that describe a feature when the layer's template does not say which.
        private val DESCRIPTION_FIELDS = setOf("description", "desc", "描述", "备注", "说明")

        /** The field that describes [layer]'s features: its template's description field, else one called so. */
        fun descriptionField(layer: Project.Layer, names: List<String>): String? =
            Templates.byId(layer.template)?.column(Templates.DESCRIPTION, names) ?: names.firstOrNull { it.trim().lowercase() in DESCRIPTION_FIELDS }
    }
}
