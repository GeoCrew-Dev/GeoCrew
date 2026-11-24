package org.geocrew.feature.analysis

import org.geocrew.core.gis.MapSectionTool
import org.geocrew.core.gis.ToolInput
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class StripTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")
    private fun f(id: String, kind: FeatureKind, g: Geometry, rock: String? = null) =
        GeoFeature(id, "p", kind, g, buildProperties { this[Props.TITLE] = id; this["岩性"] = rock }, at, SyncInfo(updatedAt = at))

    private val features = listOf(
        f("S", FeatureKind.LINE, Geometry.LineString(listOf(LngLat(116.0, 40.005), LngLat(116.02, 40.005)))),
        f("A", FeatureKind.POLYGON, Geometry.Polygon(listOf(listOf(LngLat(115.99, 40.0), LngLat(116.01, 40.0), LngLat(116.01, 40.01), LngLat(115.99, 40.01), LngLat(115.99, 40.0)))), "灰岩"),
    )
    private val input = ToolInput(features, mapOf("line" to "S", "unit" to "岩性"))

    @Test fun readsTheGroundAndPicksAnExaggeration() {
        // 1.7 km long, rising 60 m: gentle, so the relief gets stretched.
        val out = MapSectionTool().run(input.copy(elevation = { pts -> pts.map { 1000 + (it.lng - 116.0) * 3000 } })) {}
        val s = Strip.of(out)!!
        val g = s.ground
        assertNotNull(g)
        g!!
        assertEquals(1000.0, g.first().second, 0.1)
        assertEquals(1030.0, s.groundAt(s.length / 2)!!, 0.5)
        val ve = s.exaggeration(aspect = 0.45)
        assertEquals(5.0, ve, 0.0) // 60 m over 1.7 km is 8 % of a 0.45 canvas: ×5 fills 39 %, ×10 would overflow
        assertEquals(1, s.bands.size)
    }

    @Test fun noGroundWithoutTerrain() {
        val s = Strip.of(MapSectionTool().run(input) {})!!
        assertNull(s.ground)
        assertEquals(1.0, s.exaggeration(0.45), 0.0)
    }

    @Test fun printScaleAndSteps() {
        val s = Strip(length = 1700.0, bands = emptyList(), ticks = emptyList(), title = "S")
        // 1.7 km on 250 mm of paper needs 1:6800, so the next round scale.
        assertEquals(10_000.0, s.scaleToFit(250.0), 0.0)
        assertEquals(2_000.0, Strip(400.0, emptyList(), emptyList()).scaleToFit(250.0), 0.0)
        assertEquals(50.0, niceStep(300.0, 7), 0.0)
        assertEquals(200.0, niceStep(1700.0, 10), 0.0)
        assertEquals(1.0, niceStep(0.0, 5), 0.0)
    }

    @Test fun labelsComeAlong() {
        val s = Strip.of(MapSectionTool().run(input) {})!!
        assertEquals("S", s.title)
        assertEquals("灰岩", s.bands.single().label)
    }
}
