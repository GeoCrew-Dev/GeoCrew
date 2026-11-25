package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.double
import org.geocrew.core.model.edit
import org.geocrew.core.model.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ThiessenTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")
    private val tool = ThiessenTool()
    private val dLng = 1.0 / (111_320.0 * Math.cos(Math.toRadians(40.0))) // a metre east
    private val dLat = 1.0 / 111_320.0 // a metre north
    private fun at(x: Double, y: Double) = LngLat(116.0 + x * dLng, 40.0 + y * dLat)

    private fun pt(id: String, x: Double, y: Double, grade: Double?) = GeoFeature(id, "p", FeatureKind.GEO_POINT, Geometry.Point(at(x, y)),
        buildProperties { this[Props.TITLE] = id.uppercase(); grade?.let { this["cu"] = it } }, at, SyncInfo(updatedAt = at))

    // A 400 × 200 m block; samples at the centres of its left and right halves.
    private val block = GeoFeature("blk", "p", FeatureKind.POLYGON,
        Geometry.Polygon.of(listOf(at(0.0, 0.0), at(400.0, 0.0), at(400.0, 200.0), at(0.0, 200.0))), buildProperties {}, at, SyncInfo(updatedAt = at))

    @Test fun splitsTheBlockBetweenItsSamplesAndWeighsByArea() {
        val a = pt("a", 100.0, 100.0, 1.0)
        val b = pt("b", 300.0, 100.0, 3.0)
        // A third sample near the right edge takes part of b's half.
        val c = pt("c", 380.0, 100.0, 5.0)
        val r = tool.cells(ToolInput(listOf(a, b, c, block), mapOf("value" to "cu", "boundary" to "blk")))
        val byId = r.cells.associateBy { it.point.id }
        // Areas are geodesic, the test grid a local plane: allow half a percent.
        assertEquals(80_000.0, r.cells.sumOf { it.areaM2 }, 400.0)
        assertEquals(200.0 * 200, byId.getValue("a").areaM2, 200.0)  // x 0..200
        assertEquals(140.0 * 200, byId.getValue("b").areaM2, 140.0)  // x 200..340
        assertEquals(60.0 * 200, byId.getValue("c").areaM2, 60.0)   // x 340..400

        val out = tool.run(ToolInput(listOf(a, b, c, block), mapOf("value" to "cu", "boundary" to "blk")))
        assertEquals(3, out.features.size)
        val fa = out.features.first { it.properties.string(ThiessenTool.SOURCE) == "a" }
        assertEquals(1.0, fa.properties.double("cu")!!, 1e-9)
        // (1 × 40000 + 3 × 28000 + 5 × 12000) / 80000 = 2.3
        assertTrue(out.summary, out.summary.contains("按面积加权平均 2.30"))
        assertTrue(out.summary.contains("算术平均 3.00"))
        assertTrue(out.legend.isNotEmpty())
    }

    @Test fun thicknessGivesVolumeTonnageAndVolumeWeightedGrade() {
        val a = pt("a", 100.0, 100.0, 1.0).let { it.copy(properties = it.properties.edit { this["h"] = 2.0 }) }
        val b = pt("b", 300.0, 100.0, 3.0).let { it.copy(properties = it.properties.edit { this["h"] = 6.0 }) }
        val out = tool.run(ToolInput(listOf(a, b, block), mapOf("value" to "cu", "boundary" to "blk", "thickness" to "h", "density" to "2.5")))
        // 40 000 m² × 2 m + 40 000 m² × 6 m = 32 万 m³, × 2.5 = 80 万 t (a little less on the ellipsoid); (1 × 8 + 3 × 24) / 32 = 2.5.
        assertTrue(out.summary, out.summary.contains("体积 31.9"))
        assertTrue(out.summary, out.summary.contains("矿石量 79.8"))
        assertTrue(out.summary, out.summary.contains("按体积（面积×厚度）加权平均 2.50"))
        assertEquals(80_000.0, out.features.first { it.properties.string(ThiessenTool.SOURCE) == "a" }.properties.double(ThiessenTool.VOLUME_M3)!!, 400.0)
    }

    @Test fun withoutABoundaryUsesThePaddedExtentAndMergesDuplicates() {
        val pts = listOf(pt("a", 0.0, 0.0, null), pt("b", 1000.0, 0.0, null), pt("c", 0.0, 1000.0, null), pt("d", 1000.0, 1000.0, null), pt("e", 1000.0, 1000.0, null))
        val r = tool.cells(ToolInput(pts))
        assertEquals(4, r.cells.size)
        assertEquals(1, r.duplicates)
        // Extent 1000 m padded by 100 m on each side: 1200 × 1200, a quarter each.
        r.cells.forEach { assertEquals(600.0 * 600, it.areaM2, 1800.0) }
        assertTrue(tool.run(ToolInput(pts)).summary.contains("1 个点与别的点位置重合"))
    }

    @Test fun pointsOutsideTheBoundaryAreLeftOut() {
        val r = tool.cells(ToolInput(listOf(pt("a", 100.0, 100.0, 1.0), pt("b", 300.0, 100.0, 2.0), pt("x", 900.0, 100.0, 9.0), block), mapOf("boundary" to "blk")))
        assertEquals(setOf("a", "b"), r.cells.map { it.point.id }.toSet())
        assertEquals(1, r.outside)
    }

    @Test fun halfPlaneKeepsTheNearSide() {
        val square = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0), doubleArrayOf(10.0, 10.0), doubleArrayOf(0.0, 10.0))
        val cut = ThiessenTool.halfPlane(square, doubleArrayOf(2.0, 5.0), doubleArrayOf(8.0, 5.0))
        assertEquals(4, cut.size)
        assertTrue(cut.all { it[0] <= 5.0 + 1e-9 })
    }
}
