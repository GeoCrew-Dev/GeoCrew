package org.geocrew.core.gis

import org.geocrew.core.model.Elevation
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HikeTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")
    private val tool = HikeTool()

    /** About 2.2 km due north. */
    private val line = GeoFeature(
        "L", "p", FeatureKind.LINE, Geometry.LineString(listOf(LngLat(116.0, 40.0), LngLat(116.0, 40.02))),
        buildProperties { this[Props.TITLE] = "北坡路线" }, at, SyncInfo(updatedAt = at),
    )

    /** Ground rising 15 % to the north. */
    private val ramp = Elevation { pts -> pts.map { 800 + (it.lat - 40.0) * 111_000 * 0.15 } }

    @Test
    fun upIsSlowerThanBack() {
        val r = tool.run(ToolInput(listOf(line), emptyMap(), elevation = ramp))
        val f = r.features.single()
        val go = f.properties.double(HikeTool.HOURS)!!
        val back = f.properties.double(HikeTool.BACK_HOURS)!!
        assertTrue(go > back)
        assertEquals(333.0, f.properties.double(HikeTool.ASCENT)!!, 5.0)
        assertTrue(r.summary, "北坡路线" in r.summary && "原路返回" in r.summary)
        val off = tool.run(ToolInput(listOf(line), mapOf("ground" to "off"), elevation = ramp)).features.single()
        assertEquals(go / 0.6, off.properties.double(HikeTool.HOURS)!!, 1e-6)
    }

    @Test
    fun needsHeights() {
        assertNotNull(tool.check(ToolInput(listOf(line), emptyMap())))
    }
}
