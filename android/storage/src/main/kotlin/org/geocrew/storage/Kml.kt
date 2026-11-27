package org.geocrew.storage

import java.io.File
import java.io.Writer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.geocrew.core.edit.LayerStore
import org.geocrew.core.project.LayerColors
import org.geocrew.core.project.Templates

/**
 * Layers of a project as KML 2.2, for Google Earth, 奥维 and the like (ADR
 * 0004: exports). Each layer is a folder drawn in its colour; each feature a
 * placemark in WGS84 longitude and latitude, named by the layer's name field,
 * its fields as a table in the description and as ExtendedData. With [photos],
 * a feature's images show in its description as files/<name> (a KMZ carries
 * them); [photos] gives each image's file in the project and its name there.
 */
internal class Kml(private val project: Project, private val photos: Boolean) {
    /** The images the placemarks show, by the name they have in the KMZ: what [write] used. */
    val images = LinkedHashMap<String, File>()

    fun write(out: Writer, layers: List<Project.Layer>) {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        out.write("""<kml xmlns="http://www.opengis.net/kml/2.2">""" + "\n<Document>\n")
        out.write("<name>${xml(project.name)}</name>\n")
        val styles = project.styles
        for (layer in layers) {
            val color = (styles[layer.id]?.get("color") as? JsonPrimitive)?.contentOrNull ?: LayerColors.of(layer.made)
            val style = "layer-${layer.made}"
            out.write("<Style id=\"$style\">")
            // No icon of its own: the viewer tints its default, with nothing fetched from the network.
            out.write("<IconStyle><color>${kmlColor(color, 0xff)}</color><scale>0.8</scale></IconStyle>")
            out.write("<LineStyle><color>${kmlColor(color, 0xff)}</color><width>3</width></LineStyle>")
            out.write("<PolyStyle><color>${kmlColor(color, 0x66)}</color></PolyStyle></Style>\n")
        }
        for (layer in layers) folder(out, layer, "layer-${layer.made}")
        out.write("</Document>\n</kml>\n")
    }

    private fun folder(out: Writer, layer: Project.Layer, style: String) {
        // The saved layer, as exports hold only what is saved.
        val store = project.target(layer.name).store
        val names = store.fields.map { it.name }
        // The name field of the layer's template, else one called so.
        val nameField = Templates.byId(layer.template)?.column(Templates.NAME, names)
            ?: names.firstOrNull { it.trim().lowercase() in NAME_FIELDS }
        val features = store.features()
        val geometries = Crs.transform(features.map { it.geometry }, layer.srs ?: Crs.LONGITUDE_LATITUDE, WGS84)
        out.write("<Folder><name>${xml(layer.name)}</name>\n")
        for ((feature, geometry) in features.zip(geometries)) {
            // A feature without a place is a placemark too, listed without a shape, as GDAL writes it.
            val shape = geometry?.let(::geometryKml)
            out.write("<Placemark>")
            nameField?.let { f -> text(feature.properties[f])?.let { out.write("<name>${xml(it)}</name>") } }
            out.write("<styleUrl>#$style</styleUrl>")
            val rows = names.filter { it != nameField }.mapNotNull { n -> text(feature.properties[n])?.let { n to it } }
            val shown = if (photos) imagesOf(store, feature.fid) else emptyList()
            if (rows.isNotEmpty() || shown.isNotEmpty()) {
                val html = StringBuilder()
                if (rows.isNotEmpty()) {
                    html.append("<table>")
                    for ((n, v) in rows) html.append("<tr><td><b>${xml(n)}</b></td><td>${xml(v)}</td></tr>")
                    html.append("</table>")
                }
                for (image in shown) html.append("<p><img src=\"files/${xml(image)}\" width=\"480\"/></p>")
                out.write("<description><![CDATA[${html.toString().replace("]]>", "]]]]><![CDATA[>")}]]></description>")
            }
            if (rows.isNotEmpty() || nameField != null) {
                out.write("<ExtendedData>")
                for (n in names) text(feature.properties[n])?.let { out.write("<Data name=\"${xml(n)}\"><value>${xml(it)}</value></Data>") }
                out.write("</ExtendedData>")
            }
            shape?.let(out::write)
            out.write("</Placemark>\n")
        }
        out.write("</Folder>\n")
    }

    // A feature's images, kept for the KMZ by their names in the project's attachment folder.
    private fun imagesOf(store: LayerStore, fid: Long): List<String> =
        store.attachments(fid).filter { it.type.startsWith("image/") }.mapNotNull { a ->
            val file = AttachmentFiles.fileOf(project.attachments, a.file, a.type)
            if (!file.isFile) return@mapNotNull null
            images[file.name] = file
            file.name
        }

    private fun geometryKml(g: JsonObject): String? {
        val coordinates = g["coordinates"]
        return when ((g["type"] as? JsonPrimitive)?.contentOrNull) {
            "Point" -> "<Point><coordinates>${position(coordinates) ?: return null}</coordinates></Point>"
            "LineString" -> line(coordinates)
            "Polygon" -> polygon(coordinates)
            "MultiPoint" -> multi((coordinates as? JsonArray)?.mapNotNull { p -> position(p)?.let { "<Point><coordinates>$it</coordinates></Point>" } })
            "MultiLineString" -> multi((coordinates as? JsonArray)?.mapNotNull(::line))
            "MultiPolygon" -> multi((coordinates as? JsonArray)?.mapNotNull(::polygon))
            "GeometryCollection" -> multi((g["geometries"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::geometryKml) })
            else -> null
        }
    }

    private fun multi(parts: List<String>?): String? = parts?.takeIf { it.isNotEmpty() }?.joinToString("", "<MultiGeometry>", "</MultiGeometry>")

    private fun line(e: JsonElement?): String? = positions(e)?.let { "<LineString><tessellate>1</tessellate><coordinates>$it</coordinates></LineString>" }

    private fun polygon(e: JsonElement?): String? {
        val rings = (e as? JsonArray)?.mapNotNull(::positions)?.takeIf { it.isNotEmpty() } ?: return null
        val outer = "<outerBoundaryIs><LinearRing><coordinates>${rings.first()}</coordinates></LinearRing></outerBoundaryIs>"
        val inner = rings.drop(1).joinToString("") { "<innerBoundaryIs><LinearRing><coordinates>$it</coordinates></LinearRing></innerBoundaryIs>" }
        return "<Polygon>$outer$inner</Polygon>"
    }

    private fun positions(e: JsonElement?): String? = (e as? JsonArray)?.mapNotNull(::position)?.takeIf { it.isNotEmpty() }?.joinToString(" ")

    // Longitude, latitude and, when known, height, as KML writes a position.
    private fun position(e: JsonElement?): String? {
        val p = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
        if (p.size < 2 || !p[0].isFinite() || !p[1].isFinite()) return null
        // Degrees to nine places (well under a millimetre), a height to the millimetre: no float noise from the transform.
        return p.take(3).mapIndexed { i, v -> java.math.BigDecimal(v).setScale(if (i < 2) 9 else 3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() }
            .joinToString(",")
    }

    private fun text(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() }
        else -> e.toString()
    }

    companion object {
        private const val WGS84 = "EPSG:4326"

        // Fields that name a feature when the layer's template does not say which.
        private val NAME_FIELDS = setOf("name", "名称", "点名", "title", "标题")

        // [hex] (#rrggbb, or #rrggbbaa) as KML writes colours: alpha, blue, green, red; [alpha] from 0 to 255.
        fun kmlColor(hex: String, alpha: Int): String {
            val rgb = hex.removePrefix("#").take(6).padEnd(6, '0')
            return "%02x".format(alpha) + rgb.substring(4, 6) + rgb.substring(2, 4) + rgb.substring(0, 2)
        }

        // [s] as XML text: characters XML cannot hold (control characters other than tab and line breaks) left out.
        fun xml(s: String): String = s.filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
