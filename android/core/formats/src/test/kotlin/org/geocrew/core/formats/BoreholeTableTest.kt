package org.geocrew.core.formats

import org.geocrew.core.model.BoreLayer
import org.geocrew.core.model.Borehole
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

class BoreholeTableTest {
    private fun hole(id: String, title: String, h: Borehole, at: LngLat? = null) = GeoFeature(
        id = id, projectId = "p", kind = FeatureKind.BOREHOLE, geometry = at?.let { Geometry.Point(it) },
        properties = buildProperties {
            this[Props.TITLE] = title
            put(Props.BOREHOLE, h.toJson())
        },
        createdAt = Instant.EPOCH, sync = SyncInfo(updatedAt = Instant.EPOCH),
    )

    @Test
    fun oneRowPerLayerWithTopsFromTheLayerAbove() {
        val h = Borehole(listOf(BoreLayer("1", 1.2, "素填土"), BoreLayer("2", 8.5, "=粉质黏土", "可塑")), depth = 10.0, water = 6.4)
        val out = ByteArrayOutputStream()
        val r = BoreholeTableFormat.write(listOf(hole("b", "ZK10", h), hole("a", "ZK2", h, LngLat(110.5, 30.25))), out, ExportContext())
        assertEquals(4, r.written)
        val lines = out.toString(Charsets.UTF_8).removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(BoreholeTableFormat.HEADER.joinToString(","), lines[0])
        assertEquals("ZK2,110.5000000,30.2500000,,10.00,6.40,1,0.00,1.20,1.20,素填土,,a", lines[1])
        assertEquals("ZK2,110.5000000,30.2500000,,10.00,6.40,2,1.20,8.50,7.30,'=粉质黏土,可塑,a", lines[2])
        assertEquals("ZK10", lines[3].substringBefore(','))
    }

    @Test
    fun nothingToWriteWithoutLayers() {
        assertNotNull(BoreholeTableFormat.nothingToWrite(listOf(hole("a", "ZK1", Borehole(depth = 5.0)))))
        assertNull(BoreholeTableFormat.nothingToWrite(listOf(hole("a", "ZK1", Borehole(listOf(BoreLayer("1", 2.0)))))))
    }
}
