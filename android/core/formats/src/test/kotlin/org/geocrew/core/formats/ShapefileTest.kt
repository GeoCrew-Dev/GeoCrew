package org.geocrew.core.formats

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.zip.ZipInputStream

class ShapefileTest {
    private val t0 = Instant.parse("2026-11-24T08:00:00Z")

    private fun f(id: String, g: Geometry?, grade: Double? = null) = GeoFeature(
        id = id, projectId = "p", kind = FeatureKind.POINT, geometry = g,
        properties = buildProperties {
            this[Props.TITLE] = "采样点$id"
            grade?.let { this["grade"] = it }
            this["岩性代码"] = "Q4"
        },
        createdAt = t0, sync = SyncInfo(updatedAt = t0),
    )

    private val square = Geometry.Polygon.of(listOf(LngLat(0.0, 0.0), LngLat(1.0, 0.0), LngLat(1.0, 1.0), LngLat(0.0, 1.0)))

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    @Test fun writesOneShapefilePerGeometryType() {
        val buf = ByteArrayOutputStream()
        val r = ShapefileFormat.write(
            listOf(
                f("1", Geometry.Point(LngLat(116.0, 40.0)), 2.5),
                f("2", Geometry.Point(LngLat(116.1, 40.1)), 3.0),
                f("3", Geometry.LineString(listOf(LngLat(116.0, 40.0), LngLat(116.2, 40.2)))),
                f("4", square),
                f("5", null),
            ),
            buf, ExportContext(title = "野外 test"),
        )
        assertEquals(4, r.written)
        assertEquals(1, r.skipped)
        val files = unzip(buf.toByteArray())
        for (layer in listOf("points", "lines", "polygons")) for (ext in listOf("shp", "shx", "dbf", "prj", "cpg")) {
            assertTrue("test_$layer.$ext missing: ${files.keys}", "test_$layer.$ext" in files)
        }
        val shp = files.getValue("test_points.shp")
        val h = ByteBuffer.wrap(shp)
        assertEquals(9994, h.order(ByteOrder.BIG_ENDIAN).getInt(0))
        assertEquals(shp.size / 2, h.getInt(24))
        h.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1000, h.getInt(28))
        assertEquals(1, h.getInt(32))
        assertEquals(116.0, h.getDouble(36), 0.0)
        assertEquals(40.1, h.getDouble(60), 1e-12)
        // Two point records of 8 + 20 bytes each.
        assertEquals(100 + 2 * 28, shp.size)
        assertEquals(100 + 2 * 8, files.getValue("test_points.shx").size)
    }

    @Test fun polygonOuterRingIsClockwise() {
        val (shp, _) = ShapefileFormat.shpAndShx(
            ShapefileFormat.Layer.POLYGONS,
            ShapefileFormat.shapesOf(square).map { it.second },
        )
        val b = ByteBuffer.wrap(shp).order(ByteOrder.LITTLE_ENDIAN)
        val start = 100 + 8 + 44 + 4 // after record header, box, counts and one part index
        val pts = (0 until 5).map { LngLat(b.getDouble(start + it * 16), b.getDouble(start + it * 16 + 8)) }
        val signed = pts.zipWithNext { p, q -> p.lng * q.lat - q.lng * p.lat }.sum()
        assertTrue("ring should be clockwise", signed < 0)
    }

    @Test fun dbfHasFieldsAndRecords() {
        val features = listOf(f("1", Geometry.Point(LngLat(1.0, 1.0)), 2.5), f("2", Geometry.Point(LngLat(2.0, 2.0))))
        val dbf = ShapefileFormat.dbf(features)
        val b = ByteBuffer.wrap(dbf).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x03, dbf[0].toInt())
        assertEquals(2, b.getInt(4))
        val fields = ShapefileFormat.fields(features)
        assertEquals(listOf("ID", "KIND", "TITLE", "NOTE", "CREATED", "GRADE", "FIELD1"), fields.map { it.name })
        assertEquals('N', fields.first { it.name == "GRADE" }.type)
        val headerSize = b.getShort(8).toInt()
        val recordSize = b.getShort(10).toInt()
        assertEquals(dbf.size, headerSize + 2 * recordSize + 1)
        assertEquals(0x1A, dbf.last().toInt())
    }

    @Test fun utf8IsCutOnCharacterBoundaries() {
        assertArrayEquals("地质".toByteArray(), ShapefileFormat.truncateUtf8("地质调查", 7))
    }
}
