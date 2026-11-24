package org.geocrew.core.gis

import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SectionTest {
    private val origin = LngLat(116.0, 40.0)

    private fun at(east: Double, north: Double) =
        Geodesy.destination(Geodesy.destination(origin, 90.0, east), 0.0, north)

    private fun feature(id: String, g: Geometry, a: Attitude? = null) = GeoFeature(
        id, "p", if (g is Geometry.Point) FeatureKind.GEO_POINT else FeatureKind.LINE, g,
        buildProperties { this[Props.TITLE] = id; a?.writeTo(this) }, Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH),
    )

    @Test fun apparentDipFollowsTheClassicFormula() {
        val a = Attitude.of(90.0, 40.0)
        assertEquals(40.0, apparentDip(a, 90.0), 1e-9)
        assertEquals(-40.0, apparentDip(a, 270.0), 1e-9)
        assertEquals(0.0, apparentDip(a, 0.0), 1e-9)
        // 60° off the dip direction: tan β = tan 40° · cos 60°.
        assertEquals(Math.toDegrees(Math.atan(Math.tan(Math.toRadians(40.0)) * 0.5)), apparentDip(a, 30.0), 1e-9)
        assertEquals(90.0, apparentDip(Attitude.of(10.0, 90.0), 200.0), 1e-9)
    }

    @Test fun projectsOntoTheNearestSegment() {
        val poly = Polyline(listOf(at(0.0, 0.0), at(1000.0, 0.0), at(1000.0, 1000.0)))
        val s = poly.project(at(400.0, -50.0))!!
        assertEquals(400.0, s.chainage, 1.0)
        assertEquals(50.0, s.offset, 1.0) // south of an eastward line is on the right
        assertEquals(90.0, s.azimuth, 0.5)
        val t = poly.project(at(1030.0, 600.0))!!
        assertEquals(1600.0, t.chainage, 2.0)
        assertEquals(0.0, t.azimuth, 0.5)
    }

    @Test fun toolKeepsPointsInTheBandAndOrdersThemAlongTheLine() {
        val line = feature("A-A'", Geometry.LineString(listOf(at(0.0, 0.0), at(1000.0, 0.0))))
        val input = ToolInput(
            listOf(
                line,
                feature("D2", Geometry.Point(at(700.0, 30.0)), Attitude.of(270.0, 30.0)),
                feature("D1", Geometry.Point(at(200.0, -80.0)), Attitude.of(90.0, 45.0)),
                feature("far", Geometry.Point(at(500.0, 900.0)), Attitude.of(90.0, 45.0)),
                feature("beyond", Geometry.Point(at(1200.0, 0.0)), Attitude.of(90.0, 45.0)),
                feature("flat", Geometry.Point(at(300.0, 0.0))),
            ),
            mapOf("line" to "A-A'", "width" to "100"),
        )
        val tool = SectionTool()
        assertNull(tool.check(input))
        val out = tool.run(input)
        assertEquals(listOf("D1", "D2"), out.features.map { it.properties[SectionTool.SOURCE]!!.toString().trim('"') })
        assertEquals(45.0, out.features[0].properties.double(SectionTool.APPARENT_DIP)!!, 0.1)
        assertEquals(-30.0, out.features[1].properties.double(SectionTool.APPARENT_DIP)!!, 0.1)
        assertEquals(200.0, out.features[0].properties.double(SectionTool.CHAINAGE_M)!!, 1.0)
    }

    @Test fun needsALine() {
        val input = ToolInput(listOf(feature("D1", Geometry.Point(at(0.0, 0.0)), Attitude.of(90.0, 45.0))))
        assertNotNull(SectionTool().check(input))
    }
}
