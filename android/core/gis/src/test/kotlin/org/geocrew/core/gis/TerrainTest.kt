package org.geocrew.core.gis

import org.geocrew.core.model.Elevation
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos

class TerrainTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")
    private val tool = TerrainTool()

    /** About 1.7 km × 1.1 km. */
    private val area = GeoFeature(
        "A", "p", FeatureKind.POLYGON,
        Geometry.Polygon(listOf(listOf(LngLat(116.0, 40.0), LngLat(116.02, 40.0), LngLat(116.02, 40.01), LngLat(116.0, 40.01), LngLat(116.0, 40.0)))),
        buildProperties { this[Props.TITLE] = "测区" }, at, SyncInfo(updatedAt = at),
    )

    /** Ground rising 10 % to the east: slope 5.7°, facing west. */
    private val ramp = Elevation { pts -> pts.map { 1000 + (it.lng - 116.0) * 111_320 * cos(40.0 * PI / 180) * 0.1 } }

    private fun run(vararg values: Pair<String, String>, elevation: Elevation? = ramp) =
        tool.run(ToolInput(listOf(area), mapOf("area" to "A", *values), elevation = elevation))

    @Test fun contoursRunNorthSouthWithIndexLines() {
        val out = run("interval" to "10")
        assertTrue(out.features.isNotEmpty())
        out.features.forEach { f ->
            val pts = (f.geometry as Geometry.LineString).positions
            // Constant height means constant longitude on this ramp.
            assertEquals(pts.first().lng, pts.last().lng, 1e-4)
        }
        val index = out.features.filter { it.properties[TerrainTool.INDEX].toString() == "true" }
        assertTrue(index.isNotEmpty())
        assertTrue(index.all { f -> f.properties.string(Props.TITLE)!!.let { it.contains("1050") || it.contains("1100") || it.contains("1150") } })
        assertTrue(out.summary, out.summary.contains("平均坡度 6°"))
    }

    @Test fun slopeAndAspectClasses() {
        val slope = run("output" to TerrainTool.OUT_SLOPE)
        assertTrue(slope.features.isNotEmpty())
        assertTrue(slope.features.all { it.properties.string(TerrainTool.CLASS) == "5–15° 缓坡" })
        val aspect = run("output" to TerrainTool.OUT_ASPECT)
        assertTrue(aspect.features.all { it.properties.string(TerrainTool.CLASS) == "朝西" })
        assertEquals(9, aspect.legend.size) // flat plus eight directions, north only once
    }

    @Test fun needsTheDem() {
        assertNotNull(tool.check(ToolInput(listOf(area), mapOf("area" to "A"))))
        val e = runCatching { run(elevation = { pts -> pts.map { null } }) }.exceptionOrNull()
        assertEquals("这一带没有地形数据", e?.message)
    }
}
