package org.geocrew.core.formats

import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.TrackTimes
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

class FormatsTest {
    private val t0 = Instant.parse("2026-11-24T08:00:00Z")
    private var n = 0
    private val ctx = ImportContext(projectId = "p", now = t0, newId = { "id${n++}" })

    private fun f(id: String, kind: FeatureKind, g: Geometry, title: String) = GeoFeature(
        id = id, projectId = "p", kind = kind, geometry = g,
        properties = buildProperties {
            this[Props.TITLE] = title
            this[Props.NOTE] = "灰岩 <层理> & 节理"
            if (kind == FeatureKind.GEO_POINT) Attitude.of(125.0, 42.0).writeTo(this)
        },
        createdAt = t0, sync = SyncInfo(updatedAt = t0),
    )

    private val sample = listOf(
        f("a", FeatureKind.GEO_POINT, Geometry.Point(LngLat(116.39, 39.91, 52.3)), "D001"),
        f("b", FeatureKind.TRACK, Geometry.LineString(listOf(LngLat(116.0, 40.0, 10.0), LngLat(116.01, 40.01, 12.0))), "T1"),
        f("c", FeatureKind.POLYGON, Geometry.Polygon.of(listOf(LngLat(116.0, 40.0), LngLat(116.1, 40.0), LngLat(116.1, 40.1))), "区块"),
    )

    private fun write(format: FeatureFormat, fs: List<GeoFeature>, ectx: ExportContext = ExportContext()): Pair<ExportResult, ByteArray> {
        val out = ByteArrayOutputStream()
        val r = format.write(fs, out, ectx)
        return r to out.toByteArray()
    }

    @Test
    fun geoJsonRoundTripKeepsEverything() {
        val (r, bytes) = write(GeoJsonFormat, sample + sample[0].markDeleted(t0).copy(id = "dead"))
        assertEquals(3, r.written)
        val back = GeoJsonFormat.read(bytes.inputStream(), ctx).features
        assertEquals(sample.map { it.id }, back.map { it.id })
        assertEquals(sample.map { it.kind }, back.map { it.kind })
        assertEquals(sample.map { it.geometry }, back.map { it.geometry })
        assertEquals(Attitude.of(125.0, 42.0), back[0].attitude)
        assertEquals("灰岩 <层理> & 节理", back[0].note)
        assertTrue(Props.KIND !in back[0].properties)
    }

    @Test
    fun geoJsonAcceptsForeignInput() {
        val text = """{"type":"FeatureCollection","features":[
            {"type":"Feature","id":7,"geometry":{"type":"LineString","coordinates":[[1,2],[3,4]]},"properties":{"NAME":"x"}},
            {"type":"Feature","geometry":{"type":"Banana"},"properties":null},
            {"type":"Feature","geometry":null,"properties":{"kind":"note"}}
        ]}"""
        val r = GeoJson.decode(text, ctx)
        assertEquals(2, r.features.size)
        assertEquals(1, r.warnings.size)
        assertEquals("7", r.features[0].id)
        assertEquals(FeatureKind.LINE, r.features[0].kind)
        assertEquals(FeatureKind("note"), r.features[1].kind)
        assertEquals(1, GeoJson.decode("""{"type":"Point","coordinates":[1,2]}""", ctx).features.size)
    }

    @Test
    fun syncFieldsRoundTrip() {
        val synced = sample[0].copy(sync = SyncInfo(version = 3, updatedAt = t0, updatedBy = "m1", seq = 9, dirty = false))
        val back = GeoJson.decodeFeature(GeoJson.encodeFeature(synced, includeSync = true), ctx)
        assertEquals(synced.sync, back.sync)
    }

    @Test
    fun kmlRoundTrip() {
        val (r, bytes) = write(KmlFormat, sample)
        assertEquals(3, r.written)
        val back = KmlFormat.read(bytes.inputStream(), ctx).features
        assertEquals(sample.map { it.kind }, back.map { it.kind })
        assertEquals(sample.map { it.geometry }, back.map { it.geometry })
        assertEquals("D001", back[0].title)
        assertEquals("灰岩 <层理> & 节理", back[0].note)
        assertEquals(JsonPrimitive(42.0), back[0].properties[Props.DIP])
    }

    @Test
    fun kmlCarriesLegendColoursAsSharedStyles() {
        val withRock = sample.map { it.copy(properties = it.properties.edit { this[Props.LITHOLOGY] = "灰岩" }) }
        val legend = org.geocrew.core.model.Legend(Props.LITHOLOGY, listOf(org.geocrew.core.model.LegendEntry("灰岩", "#64B5F6")))
        val xml = write(KmlFormat, withRock, ExportContext(legend = legend)).second.decodeToString()
        assertEquals(1, Regex("<Style id=\"c64B5F6\">").findAll(xml).count())
        assertTrue(xml.contains("<LineStyle><color>fff6b564</color>"))
        assertTrue(xml.contains("<PolyStyle><color>73f6b564</color>"))
        assertEquals(3, Regex("<styleUrl>#c64B5F6</styleUrl>").findAll(xml).count())
        // Styles do not get in the way of reading the file back.
        assertEquals(3, KmlFormat.read(xml.byteInputStream(), ctx).features.size)
        assertTrue("<Style" !in write(KmlFormat, sample).second.decodeToString())
    }

    @Test
    fun gpxRoundTripWithTimes() {
        val times = listOf(t0, t0.plusSeconds(5))
        val (r, bytes) = write(GpxFormat, sample, ExportContext(trackTimes = { if (it == "b") times else null }))
        assertEquals(2, r.written)
        assertEquals(1, r.skipped)
        val xml = bytes.decodeToString()
        assertTrue(xml.indexOf("<wpt") < xml.indexOf("<trk>"))
        val back = GpxFormat.read(bytes.inputStream(), ctx).features
        assertEquals(listOf(FeatureKind.POINT, FeatureKind.TRACK), back.map { it.kind })
        assertEquals(sample[1].geometry, back[1].geometry)
        assertEquals(t0.toString(), back[1].properties[Props.STARTED_AT]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun gpxTimesTravelWithTheTrack() {
        val times = listOf(t0, t0.plusSeconds(5))
        val (_, bytes) = write(GpxFormat, sample, ExportContext(trackTimes = { if (it == "b") times else null }))
        val back = GpxFormat.read(bytes.inputStream(), ctx).features[1]
        assertEquals(times, TrackTimes.of(back))
        // A track recorded elsewhere has no local fixes; its synced times still go out.
        val again = write(GpxFormat, listOf(back)).second.decodeToString()
        assertTrue(again.contains("<time>${t0.plusSeconds(5)}</time>"))
        // KML shows the span, not the list.
        val kml = write(KmlFormat, listOf(back.copy(properties = back.properties.edit { this[Props.ENDED_AT] = times[1].toString() }))).second.decodeToString()
        assertTrue(kml.contains("<TimeSpan><begin>$t0</begin><end>${times[1]}</end></TimeSpan>"))
        assertTrue(Props.TIMES_S !in kml)
    }

    @Test
    fun xmlWithDoctypeIsRejected() {
        val evil = """<?xml version="1.0"?><!DOCTYPE kml [<!ENTITY x SYSTEM "file:///etc/passwd">]><kml><Placemark><name>&x;</name></Placemark></kml>"""
        val ok = runCatching { KmlFormat.read(evil.byteInputStream(), ctx) }
        assertTrue(ok.isFailure || ok.getOrNull()!!.features.none { (it.title ?: "").contains("root") })
    }

    @Test
    fun detect() {
        assertEquals(KmlFormat, FeatureFormats.detect("trip.KML", null))
        assertEquals(GeoJsonFormat, FeatureFormats.detect("a.json", null))
        assertEquals(GpxFormat, FeatureFormats.detect(null, "application/gpx+xml"))
    }
}
