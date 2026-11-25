package org.geocrew.core.formats

import org.geocrew.core.model.samples
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Legend
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.string
import org.w3c.dom.Element
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/**
 * KML 2.2 placemarks (Point, LineString, Polygon, MultiGeometry). Simple
 * properties travel as ExtendedData so Google Earth and QGIS keep them.
 */
object KmlFormat : FeatureFormat {
    override val id = "kml"
    override val displayName = "KML"
    override val extension = "kml"
    override val mimeType = "application/vnd.google-earth.kml+xml"
    override val canRead = true
    override val canWrite = true

    override fun read(input: InputStream, ctx: ImportContext): ImportResult {
        val root = Xml.parse(input).documentElement
        if (root.name != "kml") throw FormatException("not a KML file")
        val out = mutableListOf<GeoFeature>()
        val warnings = mutableListOf<String>()
        for ((i, pm) in root.descendants("Placemark").withIndex()) {
            val g = try {
                geometry(pm)
            } catch (e: Exception) {
                warnings += "placemark ${i + 1} skipped: ${e.message}"
                continue
            }
            if (g == null) {
                warnings += "placemark ${i + 1} has no supported geometry"
                continue
            }
            val kindHint = pm.child("ExtendedData")?.children("Data")
                ?.firstOrNull { it.getAttribute("name") == Props.KIND }?.text("value")
            out += GeoFeature(
                id = ctx.newId(), projectId = ctx.projectId,
                kind = kindHint?.let(::FeatureKind) ?: GeoJson.inferKind(g), geometry = g,
                properties = buildProperties {
                    pm.text("name")?.let { this[Props.TITLE] = it }
                    pm.text("description")?.let { this[Props.NOTE] = it }
                    pm.child("ExtendedData")?.children("Data")?.forEach { d ->
                        val k = d.getAttribute("name")
                        val v = d.text("value")
                        if (k.isEmpty() || k == Props.KIND || v == null) return@forEach
                        val n = v.toDoubleOrNull()
                        if (n != null) this[k] = n else this[k] = v
                    }
                },
                createdAt = ctx.now, sync = SyncInfo(updatedAt = ctx.now),
            )
        }
        return ImportResult(out, warnings)
    }

    private fun geometry(pm: Element): Geometry? {
        val parts = pm.children().mapNotNull(::geometryOf)
        return when (parts.size) {
            0 -> null
            1 -> parts.first()
            else -> Geometry.GeometryCollection(parts)
        }
    }

    private fun geometryOf(e: Element): Geometry? = when (e.name) {
        "Point" -> coords(e).firstOrNull()?.let { Geometry.Point(it) }
        "LineString" -> Geometry.LineString(coords(e))
        "LinearRing" -> Geometry.LineString(coords(e))
        "Polygon" -> {
            val outer = e.child("outerBoundaryIs")?.child("LinearRing")?.let(::coords).orEmpty()
            val inner = e.children("innerBoundaryIs").mapNotNull { it.child("LinearRing")?.let(::coords) }
            if (outer.size < 4) throw FormatException("polygon ring too short") else Geometry.Polygon(listOf(outer) + inner)
        }
        "MultiGeometry" -> {
            val parts = e.children().mapNotNull(::geometryOf)
            when {
                parts.isEmpty() -> null
                parts.all { it is Geometry.Point } -> Geometry.MultiPoint(parts.map { (it as Geometry.Point).position })
                parts.all { it is Geometry.LineString } -> Geometry.MultiLineString(parts.map { (it as Geometry.LineString).positions })
                parts.all { it is Geometry.Polygon } -> Geometry.MultiPolygon(parts.map { it as Geometry.Polygon })
                else -> Geometry.GeometryCollection(parts)
            }
        }
        else -> null
    }

    private fun coords(e: Element): List<LngLat> {
        val text = e.descendants("coordinates").firstOrNull()?.textContent ?: return emptyList()
        return text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { tuple ->
            val n = tuple.split(',').map { it.trim().toDouble() }
            if (n.size < 2) throw FormatException("bad coordinate tuple: $tuple")
            LngLat(n[0], n[1], n.getOrNull(2))
        }
    }

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""").append('\n')
        sb.append("<name>").append(Xml.escape(ctx.title)).append("</name>\n")
        val live = features.filterNot { it.deleted }
        val colors = live.associate { it.id to colorOf(it, ctx.legend) }
        // One shared style per colour, so Google Earth and 奥维 users see the map's colours.
        for (c in colors.values.filterNotNull().distinct()) style(sb, c)
        var written = 0
        var skipped = 0
        for (f in live) {
            val g = f.geometry
            if (g == null) {
                skipped++
                continue
            }
            sb.append("<Placemark>")
            f.title?.let { sb.append("<name>").append(Xml.escape(it)).append("</name>") }
            f.note?.let { sb.append("<description>").append(Xml.escape(it)).append("</description>") }
            // KML wants the time span before the style.
            timeSpan(sb, f)
            colors[f.id]?.let { sb.append("<styleUrl>#").append(styleId(it)).append("</styleUrl>") }
            extendedData(sb, f)
            geometryKml(sb, g)
            sb.append("</Placemark>\n")
            written++
        }
        sb.append("</Document></kml>\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        return ExportResult(written, skipped)
    }

    /** A record's own simplestyle colour, else its legend colour, as #RRGGBB; null when it has neither. */
    internal fun colorOf(f: GeoFeature, legend: Legend): String? {
        for (k in listOf("stroke", "fill", "marker-color")) {
            val v = (f.properties[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (v != null && Legend.isColor(v)) return v.uppercase()
        }
        return legend.colorOf(f)?.uppercase()
    }

    private fun styleId(hex: String) = "c" + hex.drop(1)

    /** KML colours are aabbggrr. */
    private fun kmlColor(hex: String, alpha: String): String =
        (alpha + hex.substring(5, 7) + hex.substring(3, 5) + hex.substring(1, 3)).lowercase()

    private fun style(sb: StringBuilder, hex: String) {
        sb.append("<Style id=\"").append(styleId(hex)).append("\">")
            .append("<IconStyle><color>").append(kmlColor(hex, "ff")).append("</color></IconStyle>")
            .append("<LineStyle><color>").append(kmlColor(hex, "ff")).append("</color><width>3</width></LineStyle>")
            .append("<PolyStyle><color>").append(kmlColor(hex, "73")).append("</color></PolyStyle>")
            .append("</Style>\n")
    }

    /** A track's start and end as a KML TimeSpan, so Google Earth's time slider can show it. */
    private fun timeSpan(sb: StringBuilder, f: GeoFeature) {
        if (f.kind != FeatureKind.TRACK) return
        fun at(key: String) = f.properties.string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val begin = at(Props.STARTED_AT) ?: return
        sb.append("<TimeSpan><begin>").append(begin).append("</begin>")
        at(Props.ENDED_AT)?.takeIf { !it.isBefore(begin) }?.let { sb.append("<end>").append(it).append("</end>") }
        sb.append("</TimeSpan>")
    }

    private fun extendedData(sb: StringBuilder, f: GeoFeature) {
        // Fix times go out as the track's time span, not as a list thousands long.
        val simple = f.properties.filter { (k, v) -> k != Props.TITLE && k != Props.NOTE && k != Props.TIMES_S && (v is JsonPrimitive || v is JsonArray) }
        sb.append("<ExtendedData>")
        data(sb, Props.KIND, f.kind.id)
        for ((k, v) in simple) {
            val text = when {
                // Sample objects would read as JSON; their numbers are what a map user needs.
                k == Props.SAMPLES -> f.samples.joinToString(",") { it.no }.takeIf { it.isNotEmpty() } ?: continue
                v is JsonPrimitive -> v.content
                v is JsonArray -> v.joinToString(",") { (it as? JsonPrimitive)?.content ?: it.toString() }
                else -> continue
            }
            data(sb, k, text)
        }
        sb.append("</ExtendedData>")
    }

    private fun data(sb: StringBuilder, k: String, v: String) {
        sb.append("<Data name=\"").append(Xml.escape(k)).append("\"><value>").append(Xml.escape(v)).append("</value></Data>")
    }

    private fun geometryKml(sb: StringBuilder, g: Geometry) {
        when (g) {
            is Geometry.Point -> sb.append("<Point><coordinates>").append(coord(g.position)).append("</coordinates></Point>")
            is Geometry.LineString -> line(sb, g.positions)
            is Geometry.Polygon -> polygon(sb, g)
            is Geometry.MultiPoint -> multi(sb) { g.positions.forEach { geometryKml(sb, Geometry.Point(it)) } }
            is Geometry.MultiLineString -> multi(sb) { g.lines.forEach { line(sb, it) } }
            is Geometry.MultiPolygon -> multi(sb) { g.polygons.forEach { polygon(sb, it) } }
            is Geometry.GeometryCollection -> multi(sb) { g.geometries.forEach { geometryKml(sb, it) } }
        }
    }

    private inline fun multi(sb: StringBuilder, body: () -> Unit) {
        sb.append("<MultiGeometry>")
        body()
        sb.append("</MultiGeometry>")
    }

    private fun line(sb: StringBuilder, pts: List<LngLat>) {
        sb.append("<LineString><coordinates>").append(pts.joinToString(" ", transform = ::coord)).append("</coordinates></LineString>")
    }

    private fun polygon(sb: StringBuilder, p: Geometry.Polygon) {
        sb.append("<Polygon>")
        p.rings.forEachIndexed { i, r ->
            val tag = if (i == 0) "outerBoundaryIs" else "innerBoundaryIs"
            sb.append('<').append(tag).append("><LinearRing><coordinates>")
                .append(r.joinToString(" ", transform = ::coord))
                .append("</coordinates></LinearRing></").append(tag).append('>')
        }
        sb.append("</Polygon>")
    }

    private fun coord(p: LngLat) = if (p.alt != null) "${p.lng},${p.lat},${p.alt}" else "${p.lng},${p.lat}"
}

