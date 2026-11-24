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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MapSectionTest {
    private val at = Instant.parse("2026-11-24T00:00:00Z")
    private val tool = MapSectionTool()

    private fun feature(id: String, kind: FeatureKind, g: Geometry, edit: org.geocrew.core.model.PropertiesBuilder.() -> Unit = {}) =
        GeoFeature(id, "p", kind, g, buildProperties { this[Props.TITLE] = id; edit() }, at, SyncInfo(updatedAt = at))

    private fun box(id: String, west: Double, east: Double, rock: String) = feature(
        id, FeatureKind.POLYGON,
        Geometry.Polygon(listOf(listOf(LngLat(west, 40.0), LngLat(east, 40.0), LngLat(east, 40.01), LngLat(west, 40.01), LngLat(west, 40.0)))),
    ) { this["岩性"] = rock }

    private fun line(id: String, vararg p: LngLat, edit: org.geocrew.core.model.PropertiesBuilder.() -> Unit = {}) =
        feature(id, FeatureKind.LINE, Geometry.LineString(p.toList()), edit)

    // A west-to-east section across granite, then two pieces of sandstone, with a gap of
    // unmapped ground at the end, cut by a north-south fault dipping east at 60°.
    private val section = line("S", LngLat(116.0, 40.005), LngLat(116.05, 40.005))
    private val features = listOf(
        section,
        box("A", 115.99, 116.01, "花岗岩"),
        box("B1", 116.01, 116.02, "砂岩"),
        box("B2", 116.02, 116.03, "砂岩"),
        // A small dyke inside the granite: the smaller polygon wins.
        box("D", 116.004, 116.005, "辉绿岩"),
        line("F1", LngLat(116.01, 39.99), LngLat(116.01, 40.02)) {
            this[Props.LINE_STYLE] = "fault"
            this[Props.DIP_DIRECTION] = 90.0
            this[Props.DIP] = 60.0
        },
        // A contact that never reaches the section.
        line("C9", LngLat(116.04, 40.006), LngLat(116.04, 40.02)),
    )
    private val input = ToolInput(features, mapOf("line" to "S", "unit" to "岩性"))

    @Test fun walksUnitsInOrderAndMergesPiecesOfTheSameUnit() {
        val r = tool.section(input)
        assertEquals(listOf("花岗岩", "辉绿岩", "花岗岩", "砂岩"), r.intervals.map { it.unit })
        val m = 111_320.0 * kotlin.math.cos(40.005 * Math.PI / 180)
        assertEquals(0.004 * m, r.intervals[0].to, 0.5)
        assertEquals(0.01 * m, r.intervals[2].to, 0.5)
        // Both sandstone boxes read as one interval, ending where the mapping stops.
        assertEquals(0.01 * m, r.intervals[3].from, 0.5)
        assertEquals(0.03 * m, r.intervals[3].to, 0.5)
        assertEquals(0.05 * m, r.length, 0.5)
    }

    @Test fun findsTheFaultAndItsApparentDip() {
        val r = tool.section(input)
        assertEquals(1, r.cuts.size)
        val c = r.cuts.single()
        assertEquals("F1", c.line.id)
        assertEquals(0.01 * 111_320.0 * kotlin.math.cos(40.005 * Math.PI / 180), c.chainage, 0.5)
        // The section runs east, along the dip: the full 60°, toward its end.
        assertEquals(60.0, c.apparentDip!!, 0.1)
    }

    @Test fun outputsColouredIntervalsAndCutPoints() {
        val out = tool.run(input)
        val lines = out.features.filter { it.kind == FeatureKind.LINE }
        val points = out.features.filter { it.kind == FeatureKind.POINT }
        assertEquals(4, lines.size)
        assertEquals(1, points.size)
        assertEquals("砂岩", lines.last().properties.string(MapSectionTool.UNIT))
        // The same unit keeps one colour.
        assertEquals(lines[0].properties.string(OutputProps.STROKE), lines[2].properties.string(OutputProps.STROKE))
        assertEquals(3, out.legend.size)
        assertEquals("fault", points.single().properties.string(Props.LINE_STYLE))
        assertEquals(60.0, points.single().properties.double(SectionTool.APPARENT_DIP)!!, 0.1)
        assertTrue(out.summary, out.summary.contains("在面外"))
        assertTrue(out.summary, out.summary.contains("视倾角 60°向终点"))
    }

    @Test fun needsALineAndSomethingToCut() {
        assertNotNull(tool.check(ToolInput(features)))
        assertNotNull(tool.check(ToolInput(listOf(section), mapOf("line" to "S"))))
        assertNull(tool.check(input))
    }
}
