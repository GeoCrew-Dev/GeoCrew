package org.geocrew.core.formats

import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.Sample
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.samples
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class SampleLabelsTest {
    @Test
    fun labelsCarryStationTypeAndDate() {
        val f = GeoFeature(
            id = "a", projectId = "p", kind = FeatureKind.GEO_POINT, geometry = Geometry.Point(LngLat(116.5, 39.9)),
            properties = buildProperties {
                this[Props.TITLE] = "D10"
                this[Props.LITHOLOGY] = "花岗岩"
                samples(listOf(Sample("D10-2"), Sample("D10-1", "薄片", takenAt = Instant.parse("2026-11-24T20:00:00Z"))))
            },
            createdAt = Instant.parse("2026-11-20T01:00:00Z"), sync = SyncInfo(updatedAt = Instant.EPOCH),
        )
        val labels = SampleLabels.build("北山", listOf(f), CoordinateFormat.DECIMAL, ZoneOffset.ofHours(8))
        assertEquals(listOf("D10-1", "D10-2"), labels.map { it.no })
        assertEquals("薄片 · 花岗岩", labels[0].kind)
        assertEquals("D10 · 2026-11-25", labels[0].station)
        assertEquals("花岗岩", labels[1].kind)
        assertEquals("D10 · 2026-11-20", labels[1].station)
        assertEquals(listOf("D10-2"), SampleLabels.build("北山", listOf(f), only = setOf("D10-2")).map { it.no })
    }

    @Test
    fun sheetFillsRowsThenPages() {
        val s = LabelSheet()
        assertEquals(24, s.perPage)
        assertEquals(2, s.pages(25))
        val (p0, first) = s.cell(0)
        assertEquals(0, p0)
        assertEquals(18f, first[0], 0.01f)
        assertEquals(s.marginX + s.labelWidth, s.cell(1).second[0], 0.01f)
        assertEquals(s.marginY + s.labelHeight, s.cell(3).second[1], 0.01f)
        assertEquals(1, s.cell(24).first)
        assertEquals(842f - 24f, s.cell(23).second[3], 0.01f)
    }
}
