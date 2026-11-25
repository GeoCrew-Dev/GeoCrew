package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GisToolsTest {
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun point(lng: Double, lat: Double, grade: Double? = null, id: String = "$lng,$lat") = GeoFeature(
        id = id, projectId = "p", kind = FeatureKind.POINT, geometry = Geometry.Point(LngLat(lng, lat)),
        properties = buildProperties { grade?.let { this["grade"] = it } }, createdAt = t0, sync = SyncInfo(updatedAt = t0),
    )

    private val square = listOf(
        point(116.0, 40.0, 1.0), point(116.01, 40.0, 2.0), point(116.0, 40.01, 3.0), point(116.01, 40.01, 4.0),
        point(116.005, 40.005, null),
    )

    @Test fun registryHasUniqueTools() {
        val r = ToolRegistry(ToolRegistry.builtIn())
        assertNotNull(r.find("idw"))
        assertEquals(14, r.all.size)
        assertNotNull(r.find("map-section"))
        assertNotNull(r.find("zonal"))
        assertNotNull(r.find("density"))
        assertNotNull(r.find("terrain"))
    }

    @Test fun numericFieldsAreFound() {
        assertEquals(listOf("grade"), numericFields(square))
    }

    @Test fun idwDrawsContoursUnlessTurnedOff() {
        val lines = { opts: Map<String, String> ->
            IdwTool().run(ToolInput(square, mapOf("field" to "grade", "cell" to "100") + opts)).features.filter { it.kind == org.geocrew.core.model.FeatureKind.LINE }
        }
        val auto = lines(emptyMap())
        assertTrue(auto.isNotEmpty())
        assertTrue(auto.all { (it.geometry as org.geocrew.core.model.Geometry.LineString).positions.size >= 2 })
        assertEquals(setOf(2.0, 3.0), lines(mapOf("contour" to "1")).map { it.properties.double(OutputProps.VALUE)!! }.toSet())
        assertTrue(lines(mapOf("contour" to "0")).isEmpty())
    }

    @Test fun idwStaysWithinSampleRangeAndHitsSamples() {
        val tool = IdwTool()
        val input = ToolInput(square, mapOf("field" to "grade", "cell" to "100"))
        assertNull(tool.check(input))
        val out = tool.run(input)
        assertTrue(out.features.isNotEmpty())
        val values = out.features.map { it.properties.double(OutputProps.VALUE)!! }
        assertTrue(values.all { it in 1.0..4.0 })
        assertTrue(values.max() > 3.5 && values.min() < 1.5)
        assertEquals(6, out.legend.size)
    }

    @Test fun idwNeedsThreeSamples() {
        val msg = IdwTool().check(ToolInput(square.take(2), mapOf("field" to "grade")))
        assertNotNull(msg)
    }

    @Test fun idwCapsGridSize() {
        val out = IdwTool().run(ToolInput(square, mapOf("field" to "grade", "cell" to "1")))
        assertTrue(out.features.size <= 6_600)
    }

    @Test fun hullOfSquareIgnoresInteriorPoint() {
        val tool = ConvexHullTool()
        val ring = tool.hull(square)
        assertEquals(4, ring.size)
        val area = tool.run(ToolInput(square)).features.single().geometry.areaSquareMeters()
        // About 853 m x 1113 m at 40°N.
        assertEquals(853.0 * 1113.0, area, 853.0 * 1113.0 * 0.01)
    }

    @Test fun bufferAreaMatchesCircle() {
        val out = BufferTool().run(ToolInput(listOf(point(116.0, 40.0)), mapOf("radius" to "100")))
        val a = out.features.single().geometry.areaSquareMeters()
        assertEquals(Math.PI * 100 * 100, a, Math.PI * 100 * 100 * 0.01)
    }

    @Test fun statisticsOfField() {
        val out = StatisticsTool().run(ToolInput(square, mapOf("field" to "grade")))
        assertTrue(out.summary, out.summary.contains("平均 2.50"))
        assertTrue(out.summary.contains("点 5"))
    }

    @Test fun cancellationStopsIdw() {
        var threw = false
        try {
            IdwTool().run(ToolInput(square, mapOf("field" to "grade"), cancelled = { true }))
        } catch (_: ToolCancelled) {
            threw = true
        }
        assertTrue(threw)
    }
}
