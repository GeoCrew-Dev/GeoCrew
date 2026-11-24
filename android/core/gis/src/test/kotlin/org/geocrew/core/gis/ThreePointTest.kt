package org.geocrew.core.gis

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
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class ThreePointTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")
    private val r = 6371008.8

    /** A point [east], [north] metres from (116, 40) on a plane through 1000 m dipping [dip] toward [dipDir]. */
    private fun onPlane(id: String, east: Double, north: Double, dipDir: Double, dip: Double, alt: Boolean = true): GeoFeature {
        val down = tan(dip * PI / 180) * (east * sin(dipDir * PI / 180) + north * cos(dipDir * PI / 180))
        val p = LngLat(116 + east / (r * cos(40 * PI / 180)) * 180 / PI, 40 + north / r * 180 / PI, if (alt) 1000 - down else null)
        return GeoFeature(id, "p", FeatureKind.GEO_POINT, Geometry.Point(p), buildProperties {
            this[Props.TITLE] = id
            this["elev"] = 1000 - down
        }, at, SyncInfo(updatedAt = at))
    }

    @Test fun recoversTheAttitudeOfThePlane() {
        val fs = listOf(onPlane("D1", 0.0, 0.0, 120.0, 30.0), onPlane("D2", 500.0, 0.0, 120.0, 30.0), onPlane("D3", 0.0, 500.0, 120.0, 30.0))
        val input = ToolInput(fs, mapOf("a" to "D1", "b" to "D2", "c" to "D3"))
        val tool = ThreePointTool()
        assertEquals(null, tool.check(input))
        val out = tool.run(input)
        val att = org.geocrew.core.model.Attitude.fromProperties(out.features.single().properties)!!
        assertEquals(120.0, att.dipDirection, 0.2)
        assertEquals(30.0, att.dip, 0.2)
        assertTrue(out.summary, out.summary.contains("120°∠30°"))
    }

    @Test fun usesAPropertyForHeightAndRejectsALine() {
        val fs = listOf(onPlane("D1", 0.0, 0.0, 300.0, 10.0, alt = false), onPlane("D2", 800.0, 100.0, 300.0, 10.0, alt = false), onPlane("D3", -200.0, 700.0, 300.0, 10.0, alt = false))
        val tool = ThreePointTool()
        assertTrue(tool.check(ToolInput(fs, mapOf("a" to "D1", "b" to "D2", "c" to "D3")))!!.contains("海拔"))
        val att = tool.run(ToolInput(fs, mapOf("a" to "D1", "b" to "D2", "c" to "D3", "elevation" to "elev"))).features.single()
            .properties.let { org.geocrew.core.model.Attitude.fromProperties(it)!! }
        assertEquals(300.0, att.dipDirection, 0.3)
        assertEquals(10.0, att.dip, 0.2)

        val line = listOf(onPlane("A", 0.0, 0.0, 90.0, 20.0), onPlane("B", 100.0, 1.0, 90.0, 20.0), onPlane("C", 200.0, 0.0, 90.0, 20.0))
        assertTrue(tool.check(ToolInput(line, mapOf("a" to "A", "b" to "B", "c" to "C")))!!.contains("直线"))
    }
}
