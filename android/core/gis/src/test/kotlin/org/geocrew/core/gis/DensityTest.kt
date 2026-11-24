package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.double
import org.geocrew.core.model.positions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DensityTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")
    private val tool = DensityTool()

    private fun point(id: String, lng: Double, lat: Double, n: Double? = null) =
        GeoFeature(id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(lng, lat)), buildProperties {
            this[Props.TITLE] = id
            n?.let { this["矿化"] = it }
        }, at, SyncInfo(updatedAt = at))

    /** Eight points close together near 116.000E and two far apart to the east. */
    private val points = (0 until 8).map { point("C$it", 116.0 + it % 3 * 0.0005, 40.0 + it / 3 * 0.0005, n = 2.0) } +
        listOf(point("E1", 116.03, 40.0, n = 1.0), point("E2", 116.03, 40.02, n = 1.0))

    private fun grid(out: ToolOutput) = out.features.filter { it.properties.double(OutputProps.VALUE) != null && it.kind == FeatureKind.POLYGON }

    /** Density times cell area, summed: the number of points (or total weight) the grid holds. */
    private fun integral(out: ToolOutput) = grid(out).sumOf { it.properties.double(OutputProps.VALUE)!! * it.geometry.areaSquareMeters() / 1e6 }

    @Test fun peaksWhereThePointsCluster() {
        val out = tool.run(ToolInput(points, mapOf("radius" to "800")))
        val top = grid(out).maxBy { it.properties.double(OutputProps.VALUE)!! }
        val c = top.geometry.positions().let { p -> LngLat(p.map { it.lng }.average(), p.map { it.lat }.average()) }
        assertEquals(116.0005, c.lng, 0.003)
        assertEquals(40.0005, c.lat, 0.003)
        // The grid holds nearly all ten points; only the thin fringe left out is missing.
        assertEquals(10.0, integral(out), 0.6)
        assertTrue(out.summary.contains("800 m"))
        assertTrue(out.legend.isNotEmpty())
    }

    @Test fun weightsCountTowardTheTotal() {
        val out = tool.run(ToolInput(points, mapOf("radius" to "800", "weight" to "矿化")))
        assertEquals(18.0, integral(out), 1.0)
        assertTrue(out.summary.contains("合计 18"))
    }

    @Test fun picksARadiusWhenNoneIsGiven() {
        val out = tool.run(ToolInput(points))
        assertTrue(grid(out).isNotEmpty())
        assertNotNull(Regex("搜索半径 (\\d+) m").find(out.summary)?.groupValues?.get(1)?.toInt()?.takeIf { it in 50..5_000 })
    }

    @Test fun needsTwoPoints() {
        assertNotNull(tool.check(ToolInput(points.take(1))))
        assertNotNull(tool.check(ToolInput(listOf(point("A", 116.0, 40.0), point("B", 116.1, 40.0)), mapOf("weight" to "矿化"))))
        assertEquals(null, tool.check(ToolInput(points)))
    }
}
