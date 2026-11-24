package org.geocrew.core.formats

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.w3c.dom.Element
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/** GPX 1.1: waypoints, tracks and routes. Polygons cannot be expressed and are skipped. */
object GpxFormat : FeatureFormat {
    override val id = "gpx"
    override val displayName = "GPX"
    override val extension = "gpx"
    override val mimeType = "application/gpx+xml"
    override val canRead = true
    override val canWrite = true

    override fun read(input: InputStream, ctx: ImportContext): ImportResult {
        val root = Xml.parse(input).documentElement
        if (root.name != "gpx") throw FormatException("not a GPX file")
        val out = mutableListOf<GeoFeature>()
        val warnings = mutableListOf<String>()

        fun feature(kind: FeatureKind, g: Geometry, e: Element, extra: Map<String, String> = emptyMap()) = GeoFeature(
            id = ctx.newId(), projectId = ctx.projectId, kind = kind, geometry = g,
            properties = buildProperties {
                e.text("name")?.let { this[Props.TITLE] = it }
                (e.text("desc") ?: e.text("cmt"))?.let { this[Props.NOTE] = it }
                extra.forEach { (k, v) -> this[k] = v }
            },
            createdAt = ctx.now, sync = SyncInfo(updatedAt = ctx.now),
        )

        for (w in root.children("wpt")) {
            pt(w)?.let { out += feature(FeatureKind.POINT, Geometry.Point(it.first), w) }
        }
        for (trk in root.children("trk")) {
            for ((i, seg) in trk.children("trkseg").withIndex()) {
                val pts = seg.children("trkpt").mapNotNull(::pt)
                if (pts.size < 2) {
                    warnings += "track segment ${i + 1} has fewer than 2 points"
                    continue
                }
                val times = pts.mapNotNull { it.second }
                val extra = buildMap {
                    times.firstOrNull()?.let { put(Props.STARTED_AT, it.toString()) }
                    times.lastOrNull()?.let { put(Props.ENDED_AT, it.toString()) }
                }
                out += feature(FeatureKind.TRACK, Geometry.LineString(pts.map { it.first }), trk, extra)
            }
        }
        for (rte in root.children("rte")) {
            val pts = rte.children("rtept").mapNotNull(::pt)
            if (pts.size >= 2) out += feature(FeatureKind.LINE, Geometry.LineString(pts.map { it.first }), rte)
        }
        return ImportResult(out, warnings)
    }

    private fun pt(e: Element): Pair<LngLat, Instant?>? {
        val lat = e.getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = e.getAttribute("lon").toDoubleOrNull() ?: return null
        val ele = e.text("ele")?.toDoubleOrNull()
        val time = e.text("time")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return runCatching { LngLat(lon, lat, ele) to time }.getOrNull()
    }

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="GeoCrew" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("  <metadata><name>").append(Xml.escape(ctx.title)).append("</name></metadata>\n")
        var written = 0
        var skipped = 0
        val live = features.filterNot { it.deleted }
        // GPX requires all wpt before rte before trk.
        for (f in live) {
            val g = f.geometry as? Geometry.Point ?: continue
            sb.append("  <wpt").append(latLon(g.position)).append('>')
            ele(sb, g.position)
            meta(sb, f)
            sb.append("</wpt>\n")
            written++
        }
        for (f in live) {
            val lines = when (val g = f.geometry) {
                is Geometry.LineString -> listOf(g.positions)
                is Geometry.MultiLineString -> g.lines
                is Geometry.Point -> continue
                else -> { skipped++; continue }
            }
            val times = ctx.trackTimes(f.id)
            sb.append("  <trk>")
            meta(sb, f)
            var idx = 0
            for (line in lines) {
                sb.append("<trkseg>")
                for (p in line) {
                    sb.append("<trkpt").append(latLon(p)).append('>')
                    ele(sb, p)
                    times?.getOrNull(idx)?.let { sb.append("<time>").append(it).append("</time>") }
                    sb.append("</trkpt>")
                    idx++
                }
                sb.append("</trkseg>")
            }
            sb.append("</trk>\n")
            written++
        }
        sb.append("</gpx>\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        return ExportResult(written, skipped)
    }

    private fun latLon(p: LngLat) = """ lat="${p.lat}" lon="${p.lng}""""

    private fun ele(sb: StringBuilder, p: LngLat) {
        p.alt?.let { sb.append("<ele>").append(it).append("</ele>") }
    }

    private fun meta(sb: StringBuilder, f: GeoFeature) {
        f.title?.let { sb.append("<name>").append(Xml.escape(it)).append("</name>") }
        f.note?.let { sb.append("<desc>").append(Xml.escape(it)).append("</desc>") }
    }
}
