package org.geocrew.core.formats

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GaussKruger
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

class DxfFormatTest {
    private val t0 = Instant.parse("2026-11-24T00:00:00Z")

    private fun feature(id: String, kind: FeatureKind, g: Geometry, props: Map<String, Any> = emptyMap()) = GeoFeature(
        id = id, projectId = "p", kind = kind, geometry = g,
        properties = buildProperties {
            props.forEach { (k, v) -> if (v is Number) this[k] = v else this[k] = v.toString() }
        },
        createdAt = t0, sync = SyncInfo(updatedAt = t0),
    )

    /** (group code, value) pairs of a DXF text. */
    private fun groups(text: String) = text.lines().chunked(2).filter { it.size == 2 }.map { it[0].trim().toInt() to it[1] }

    @Test
    fun writesGaussKrugerEntitiesOnLayersInGbk() {
        val p = LngLat(117.3, 31.8)
        val list = listOf(
            feature("a", FeatureKind.GEO_POINT, Geometry.Point(p), mapOf(Props.TITLE to "D001", Props.DIP_DIRECTION to 135.0, Props.DIP to 40.0)),
            feature("b", FeatureKind.POLYGON, Geometry.Polygon.of(listOf(LngLat(117.3, 31.8), LngLat(117.31, 31.8), LngLat(117.31, 31.81)))),
            feature("c", FeatureKind.LINE, Geometry.LineString(listOf(LngLat(117.3, 31.8)))),
        )
        val out = ByteArrayOutputStream()
        val r = DxfFormat.write(list, out)
        assertEquals(2, r.written)
        assertEquals(1, r.skipped)
        val text = String(out.toByteArray(), charset("GB18030"))
        val g = groups(text)
        assertEquals(0 to "EOF", g.last())
        assertTrue(g.contains(1 to "D001"))
        val layers = g.zipWithNext().filter { (a, _) -> a == (0 to "LAYER") }.map { (_, b) -> b.second }
        assertTrue(layers.containsAll(listOf("地质点", "面", "注记")))
        // The point sits at its 3° zone coordinates, zone number in front of X.
        val gk = GaussKruger.project(p, 3)
        val i = g.indexOf(0 to "POINT")
        assertEquals(gk.yWithZone, g[i + 2].second.toDouble(), 0.001)
        assertEquals(gk.x, g[i + 3].second.toDouble(), 0.001)
        // The polygon is one closed polyline of three vertices.
        assertEquals(3, g.count { it == 0 to "VERTEX" })
        assertTrue(g.contains(70 to "1"))
        // GBK bytes, as R12 readers in China expect.
        assertTrue(out.toByteArray().toList().containsAll("地质点".toByteArray(charset("GBK")).toList()))
    }

    @Test
    fun mapsLegendColoursToCadColours() {
        assertEquals(1, DxfFormat.aci("#E53935"))
        assertEquals(5, DxfFormat.aci("#1010F0"))
        assertEquals(null, DxfFormat.aci("red"))
    }
}
