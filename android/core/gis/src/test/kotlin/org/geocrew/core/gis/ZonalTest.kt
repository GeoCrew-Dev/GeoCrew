package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.double
import org.geocrew.core.model.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ZonalTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")

    private fun point(id: String, lng: Double, lat: Double, rock: String, grade: Double) =
        GeoFeature(id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(lng, lat)), buildProperties {
            this[Props.TITLE] = id
            this["岩性"] = rock
            this["grade"] = grade
        }, at, SyncInfo(updatedAt = at))

    private fun square(id: String, west: Double, south: Double, size: Double, hole: Boolean = false): GeoFeature {
        fun ring(w: Double, s: Double, d: Double) = listOf(LngLat(w, s), LngLat(w + d, s), LngLat(w + d, s + d), LngLat(w, s + d), LngLat(w, s))
        val rings = listOf(ring(west, south, size)) + if (hole) listOf(ring(west + size / 4, south + size / 4, size / 2)) else emptyList()
        return GeoFeature(id, "p", FeatureKind.POLYGON, Geometry.Polygon(rings), buildProperties { this[Props.TITLE] = id }, at, SyncInfo(updatedAt = at))
    }

    @Test fun countsPointsCategoriesAndValuesPerPolygon() {
        val fs = listOf(
            square("A", 116.0, 40.0, 0.01),
            square("B", 116.02, 40.0, 0.01, hole = true),
            point("D1", 116.002, 40.002, "花岗岩", 1.0),
            point("D2", 116.005, 40.005, "花岗岩", 3.0),
            point("D3", 116.008, 40.001, "砂岩", 2.0),
            // In B's hole: not counted.
            point("D4", 116.025, 40.005, "砂岩", 9.0),
            // In B's ring around the hole.
            point("D5", 116.021, 40.001, "砂岩", 4.0),
            // On A's east edge.
            point("D6", 116.01, 40.005, "灰岩", 5.0),
            // Outside both.
            point("D7", 116.5, 40.5, "灰岩", 6.0),
        )
        val tool = ZonalTool()
        val input = ToolInput(fs, mapOf("category" to "岩性", "value" to "grade"))
        assertEquals(null, tool.check(input))
        val zones = tool.zones(input).associateBy { it.polygon.id }
        assertEquals(listOf("D1", "D2", "D3", "D6"), zones["A"]!!.points.map { it.id })
        assertEquals(listOf("花岗岩" to 2, "灰岩" to 1, "砂岩" to 1), zones["A"]!!.categories)
        assertEquals(listOf("D5"), zones["B"]!!.points.map { it.id })
        // About 0.85 km × 1.11 km at 40°N.
        assertEquals(0.947, zones["A"]!!.areaM2 / 1e6, 0.02)

        val out = tool.run(input)
        val a = out.features.first { it.properties.string(Props.TITLE) == "面内统计 A" }.properties
        assertEquals(4.0, a.double(ZonalTool.COUNT)!!, 0.0)
        assertEquals(2.75, a.double(ZonalTool.MEAN)!!, 1e-9)
        assertEquals("花岗岩 2、灰岩 1、砂岩 1", a.string(ZonalTool.CATEGORIES))
        assertNotNull(a.string(OutputProps.FILL))
        assertTrue(out.summary, out.summary.startsWith("2 个面，共落入 5 个点（全部 7 个）"))
    }

    @Test fun pointOnTheHoleEdgeCountsAndMultiPolygonsWork() {
        val ring = listOf(LngLat(0.0, 0.0), LngLat(2.0, 0.0), LngLat(2.0, 2.0), LngLat(0.0, 2.0), LngLat(0.0, 0.0))
        val hole = listOf(LngLat(0.5, 0.5), LngLat(1.5, 0.5), LngLat(1.5, 1.5), LngLat(0.5, 1.5), LngLat(0.5, 0.5))
        val far = ring.map { LngLat(it.lng + 10, it.lat) }
        val g = Geometry.MultiPolygon(listOf(Geometry.Polygon(listOf(ring, hole)), Geometry.Polygon(listOf(far))))
        assertTrue(ZonalTool.contains(g, LngLat(0.5, 1.0)))
        assertFalse(ZonalTool.contains(g, LngLat(1.0, 1.0)))
        assertTrue(ZonalTool.contains(g, LngLat(11.0, 1.0)))
        assertFalse(ZonalTool.contains(g, LngLat(5.0, 1.0)))
    }

    @Test fun offersTextPropertiesAsCategoriesButNotNames() {
        val fs = listOf(point("D1", 0.0, 0.0, "花岗岩", 1.0), point("D2", 0.0, 0.0, "砂岩", 2.0))
        assertEquals(listOf("岩性"), categoryFields(fs))
        assertEquals(emptyList<String>(), categoryFields(fs, maxValues = 1))
    }

    @Test fun needsPolygonsAndPoints() {
        val tool = ZonalTool()
        assertEquals("没有面可统计，请先画面或导入面", tool.check(ToolInput(listOf(point("D1", 0.0, 0.0, "x", 1.0)))))
        assertEquals("没有点可统计", tool.check(ToolInput(listOf(square("A", 0.0, 0.0, 1.0)))))
    }
}
