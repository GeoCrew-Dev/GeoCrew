package org.geocrew.core.formats

import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.MeasuredSection
import org.geocrew.core.model.Props
import org.geocrew.core.model.SectionLayer
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.TraverseLeg
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

class SectionTableTest {
    private fun section(id: String, title: String, s: MeasuredSection, kind: FeatureKind = FeatureKind.SECTION) = GeoFeature(
        id = id, projectId = "p", kind = kind, geometry = null,
        properties = buildProperties {
            this[Props.TITLE] = title
            put(Props.SECTION, s.toJson())
        },
        createdAt = Instant.EPOCH, sync = SyncInfo(updatedAt = Instant.EPOCH),
    )

    @Test
    fun oneRowPerLayerWithThicknessAndSource() {
        val s = MeasuredSection(
            listOf(TraverseLeg(90.0, 0.0, 20.0)),
            listOf(
                SectionLayer("1", 10.0, "灰岩", Attitude(90.0, 30.0)),
                SectionLayer("2", 20.0, "=页岩", note = "含笔石"),
            ),
        )
        val out = ByteArrayOutputStream()
        val r = SectionTableFormat.write(listOf(section("b", "P10", s), section("a", "P2", s)), out, ExportContext())
        assertEquals(4, r.written)
        val lines = out.toString(Charsets.UTF_8).removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(SectionTableFormat.HEADER.joinToString(","), lines[0])
        assertEquals("P2,1,0.00,10.00,灰岩,90,30,实测,5.00,,a", lines[1])
        assertEquals("P2,2,10.00,20.00,'=页岩,90,30,借用,5.00,含笔石,a", lines[2])
        assertEquals("P10", lines[3].substringBefore(','))
    }

    @Test
    fun nothingToWriteWithoutLayers() {
        assertNotNull(SectionTableFormat.nothingToWrite(listOf(section("a", "P1", MeasuredSection()))))
        val s = MeasuredSection(layers = listOf(SectionLayer("1", 5.0)))
        assertNotNull(SectionTableFormat.nothingToWrite(listOf(section("a", "P1", s, FeatureKind.LINE))))
        assertNull(SectionTableFormat.nothingToWrite(listOf(section("a", "P1", s))))
    }
}
