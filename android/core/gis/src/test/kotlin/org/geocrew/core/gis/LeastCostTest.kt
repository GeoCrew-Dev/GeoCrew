package org.geocrew.core.gis

import org.geocrew.core.model.Elevation
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LeastCostTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")
    private val tool = LeastCostTool()

    private fun pt(id: String, p: LngLat) = GeoFeature(id, "p", FeatureKind.POINT, Geometry.Point(p), buildProperties {}, at, SyncInfo(updatedAt = at))

    private val a = pt("a", LngLat(116.0, 40.0))
    private val b = pt("b", LngLat(116.0, 40.018)) // about 2 km north

    private fun input(dem: Elevation, extra: Map<String, String> = emptyMap()) =
        ToolInput(listOf(a, b), mapOf("from" to "a", "to" to "b") + extra, elevation = dem)

    @Test
    fun flatGroundGoesStraight() {
        val r = tool.run(input(Elevation { pts -> pts.map { 500.0 } }))
        val line = r.features.single()
        val g = line.geometry as Geometry.LineString
        assertTrue("${g.positions.size}", g.positions.size <= 4)
        // About 2 km at 0.6 × Tobler's 5 km/h on the flat.
        assertEquals(2.0 / (6 * Math.exp(-3.5 * 0.05) * 0.6), line.properties.double(HikeTool.HOURS)!!, 0.08)
    }

    @Test
    fun goesRoundAWall() {
        // A 300 m cliff band across the way, open only at its east end.
        val wall = Elevation { pts ->
            pts.map { p ->
                val y = (p.lat - 40.0) * 111_320
                val x = (p.lng - 116.0) * 111_320 * Math.cos(Math.toRadians(40.0))
                if (y in 900.0..1100.0 && x < 500) 800.0 else 500.0
            }
        }
        val r = tool.run(input(wall))
        val g = r.features.single().geometry as Geometry.LineString
        val maxX = g.positions.maxOf { (it.lng - 116.0) * 111_320 * Math.cos(Math.toRadians(40.0)) }
        assertTrue("went round at x=$maxX", maxX >= 480)
        assertTrue(r.summary, "陡坡" in r.summary)
    }

    @Test
    fun refusesWithoutAWay() {
        val sealed = Elevation { pts -> pts.map { p -> if (((p.lat - 40.0) * 111_320) in 900.0..1100.0) 2000.0 else 500.0 } }
        val e = runCatching { tool.run(input(sealed)) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e!!.message!!, "最大坡度" in e.message!!)
    }

    @Test
    fun checks() {
        assertNotNull(tool.check(ToolInput(listOf(a, b), mapOf("from" to "a", "to" to "b"))))
        assertNotNull(tool.check(input(Elevation { it.map { 0.0 } }, mapOf("to" to "a"))))
        assertNull(tool.check(input(Elevation { it.map { 0.0 } })))
    }

    @Test
    fun cornersKeepTurnsOnly() {
        // 3×3 grid: 0 → 1 → 2 → 5 → 8 turns once at 2.
        assertEquals(listOf(0, 2, 8), LeastCost.corners(listOf(0, 1, 2, 5, 8), 3))
    }
}
