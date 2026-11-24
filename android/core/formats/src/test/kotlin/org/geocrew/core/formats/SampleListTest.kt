package org.geocrew.core.formats

import org.geocrew.core.model.AssayValue
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.Sample
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.samples
import org.geocrew.core.model.withResults
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

class SampleListTest {
    private fun f(id: String, title: String, g: Geometry, vararg s: Sample, deleted: Boolean = false) = GeoFeature(
        id = id, projectId = "p", kind = FeatureKind.GEO_POINT, geometry = g,
        properties = buildProperties {
            this[Props.TITLE] = title
            this[Props.LITHOLOGY] = "花岗岩"
            samples(s.toList())
        },
        createdAt = Instant.EPOCH, sync = SyncInfo(updatedAt = Instant.EPOCH, deleted = deleted),
    )

    @Test
    fun oneRowPerSampleInNaturalOrder() {
        val out = ByteArrayOutputStream()
        val r = SampleListFormat.write(
            listOf(
                f("a", "D10", Geometry.Point(LngLat(116.5, 39.9, 52.0)), Sample("D10-1", "薄片", "新鲜面, 无风化")),
                f("b", "D2", Geometry.LineString(listOf(LngLat(110.0, 30.0), LngLat(111.0, 31.0))), Sample("D2-2"), Sample("D2-1", "岩石化学")),
                f("c", "D3", Geometry.Point(LngLat(1.0, 1.0)), Sample("D3-1"), deleted = true),
            ),
            out, ExportContext(),
        )
        assertEquals(3, r.written)
        val lines = out.toString(Charsets.UTF_8).removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(SampleListFormat.HEADER.joinToString(","), lines[0])
        assertEquals(listOf("D2-1", "D2-2", "D10-1"), lines.drop(1).map { it.substringBefore(',') })
        assertEquals("D2-1,岩石化学,D2,110,30,,花岗岩,,,b", lines[1])
        assertEquals("D10-1,薄片,D10,116.5,39.9,52,花岗岩,,\"新鲜面, 无风化\",a", lines[3])
    }

    @Test
    fun labResultsBecomeColumns() {
        val a = Sample("D1-1").withResults(mapOf("Cu(ppm)" to AssayValue.Measured(35.2), "Au(ppb)" to AssayValue.BelowLimit(0.5)))
        val out = ByteArrayOutputStream()
        SampleListFormat.write(listOf(f("a", "D1", Geometry.Point(LngLat(1.0, 2.0)), a, Sample("D1-2"))), out, ExportContext())
        val lines = out.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals((SampleListFormat.HEADER + listOf("Au(ppb)", "Cu(ppm)")).joinToString(","), lines[0])
        assertEquals(",<0.5,35.2", lines[1].substringAfter(",a"))
        assertEquals(",,", lines[2].substringAfter(",a"))
    }

    @Test
    fun formulasAreDefused() {
        assertEquals("'=1+1", defuse("=1+1"))
        assertEquals("-0.5", defuse("-0.5"))
        assertEquals("'-x", defuse("-x"))
        assertEquals("D-1", defuse("D-1"))
    }
}
