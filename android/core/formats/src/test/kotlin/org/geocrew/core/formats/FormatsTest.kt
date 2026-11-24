package org.geocrew.core.formats

import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
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
