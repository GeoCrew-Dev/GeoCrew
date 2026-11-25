package org.geocrew.core.formats

import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.FieldType
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

class CsvTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")
    private val fields = listOf(
        FieldTemplate("sample_no", "样品编号"),
        FieldTemplate("thickness", "厚度", FieldType.NUMBER, unit = "m"),
    )

    private fun point(id: String, vararg props: Pair<String, Any>) = GeoFeature(
        id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(116.5, 39.25, 52.0)),
        buildProperties {
            props.forEach { (k, v) -> if (v is Number) this[k] = v else this[k] = v.toString() }
        },
        now, SyncInfo(updatedAt = now),
    )

    @Test
    fun exportsFormColumnsFirstWithLabels() {
        val out = ByteArrayOutputStream()
        CsvFormat.write(
            listOf(point("a", Props.TITLE to "D001, 北坡", "sample_no" to "007", "thickness" to 2.5, "zeta" to "z", Props.DIP to 30)),
            out, ExportContext(fields = fields),
        )
        val text = out.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("﻿"))
        val rows = CsvFormat.parse(text.removePrefix("﻿"))
        val header = rows[0]
        assertEquals(listOf("样品编号", "厚度", "zeta"), header.takeLast(3))
        val row = header.zip(rows[1]).toMap()
        assertEquals("D001, 北坡", row[Props.TITLE])
        assertEquals("007", row["样品编号"])
        assertEquals("2.5", row["厚度"])
        assertEquals("30", row[Props.DIP])
        assertEquals("116.5", row["lng"])
    }

    @Test
    fun roundTripsThroughTheFormAndKeepsCodes() {
        val out = ByteArrayOutputStream()
        CsvFormat.write(listOf(point("a", "sample_no" to "007", "thickness" to 3, Props.STRIKE to 40)), out, ExportContext(fields = fields))
        var n = 0
        val r = CsvFormat.read(out.toByteArray().inputStream(), ImportContext("q", now, { "n${n++}" }, fields))
        val f = r.features.single()
        assertEquals("a", f.id)
        assertEquals("q", f.projectId)
        assertEquals(FeatureKind.GEO_POINT, f.kind)
        assertEquals(JsonPrimitive("007"), f.properties["sample_no"])
        assertEquals(JsonPrimitive(3.0), f.properties["thickness"])
        assertEquals(JsonPrimitive(40.0), f.properties[Props.STRIKE])
        assertEquals(52.0, (f.geometry as Geometry.Point).position.alt!!, 1e-9)
    }

    @Test
    fun readsChineseHeadersAndSemicolons() {
        val csv = "点号;经度;纬度;岩性;品位\nP1;116,4;39,9;花岗岩;0.35\nP2;abc;39.9;;\n\nP3;200;10;;\n"
        val r = CsvFormat.read(csv.byteInputStream(), ImportContext("p"))
        assertEquals(1, r.features.size)
        val f = r.features.single()
        assertEquals("P1", f.title)
        assertEquals(FeatureKind.POINT, f.kind)
        assertEquals(116.4, (f.geometry as Geometry.Point).position.lng, 1e-9)
        assertEquals(JsonPrimitive("花岗岩"), f.properties["岩性"])
        assertEquals(JsonPrimitive(0.35), f.properties["品位"])
        assertEquals(2, r.warnings.size)
    }

    @Test
    fun parsesQuotedCells() {
        val rows = CsvFormat.parse("a,b\r\n\"x, \"\"y\"\"\",\"line1\nline2\"\r\n")
        assertEquals(listOf("x, \"y\"", "line1\nline2"), rows[1])
    }

    @Test(expected = FormatException::class)
    fun requiresCoordinates() {
        CsvFormat.read("name,value\na,1\n".byteInputStream(), ImportContext("p"))
    }
}
