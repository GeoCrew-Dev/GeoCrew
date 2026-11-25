package org.geocrew.core.formats

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
import java.io.ByteArrayOutputStream
import java.time.Instant

class StructureTableTest {
    private fun f(id: String, kind: FeatureKind, g: Geometry, props: org.geocrew.core.model.PropertiesBuilder.() -> Unit) = GeoFeature(
        id, "p", kind, g, buildProperties(props), Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH),
    )

    private val list = listOf(
        f("b", FeatureKind.GEO_POINT, Geometry.Point(LngLat(110.5, 30.25, 812.0))) {
            this[Props.TITLE] = "D10"
            this[Props.LITHOLOGY] = "灰岩"
            this[Props.DIP_DIRECTION] = 120.0
            this[Props.DIP] = 35.0
            this[Props.TREND] = 160.0
            this[Props.PLUNGE] = 30.0
            this[Props.LINEATION_TYPE] = "擦痕"
        },
        f("a", FeatureKind.LINE, Geometry.LineString(listOf(LngLat(110.0, 30.0), LngLat(110.1, 30.0)))) {
            this[Props.TITLE] = "D2"
            this[Props.LINE_STYLE] = "fault"
            this[Props.DIP_DIRECTION] = 10.0
            this[Props.DIP] = 70.0
        },
        f("c", FeatureKind.POINT, Geometry.Point(LngLat(110.0, 30.0))) { this[Props.TITLE] = "D3" },
    )

    @Test
    fun planesAndLinesOneRowEach() {
        val out = ByteArrayOutputStream()
        val r = StructureTableFormat.write(list, out, ExportContext())
        assertEquals(3, r.written)
        val lines = out.toString(Charsets.UTF_8).removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(StructureTableFormat.HEADER.joinToString(","), lines[0])
        assertEquals("D2,面状,实测断层,10.0,70.0,280.0,110.0000000,30.0000000,,,a", lines[1])
        assertEquals("D10,面状,,120.0,35.0,30.0,110.5000000,30.2500000,812.0,灰岩,b", lines[2])
        assertEquals("D10,线状,擦痕,160.0,30.0,,110.5000000,30.2500000,812.0,灰岩,b", lines[3])
    }

    @Test
    fun nothingWithoutAttitudes() {
        assertNotNull(StructureTableFormat.nothingToWrite(list.takeLast(1)))
        assertNull(StructureTableFormat.nothingToWrite(list))
    }
}
