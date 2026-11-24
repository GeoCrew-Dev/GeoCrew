package org.geocrew.core.formats

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GaussKruger
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShapefileReaderTest {
    private val t0 = Instant.parse("2026-11-24T00:00:00Z")
    private var next = 0
    private val ctx = ImportContext("p", t0, newId = { "n${next++}" })

    private fun feature(id: String, kind: FeatureKind, g: Geometry, title: String) = GeoFeature(
        id = id, projectId = "p", kind = kind, geometry = g,
        properties = buildProperties { this[Props.TITLE] = title; this["厚度"] = 12.5 },
        createdAt = t0, sync = SyncInfo(updatedAt = t0),
    )

    @Test
    fun readsWhatTheExporterWrites() {
        val hole = listOf(LngLat(110.02, 30.02), LngLat(110.02, 30.04), LngLat(110.04, 30.04), LngLat(110.04, 30.02), LngLat(110.02, 30.02))
        val outer = listOf(LngLat(110.0, 30.0), LngLat(110.1, 30.0), LngLat(110.1, 30.1), LngLat(110.0, 30.1), LngLat(110.0, 30.0))
        val list = listOf(
            feature("a", FeatureKind.POINT, Geometry.Point(LngLat(110.0, 30.0)), "D001"),
            feature("b", FeatureKind.LINE, Geometry.LineString(listOf(LngLat(110.0, 30.0), LngLat(110.1, 30.1))), "断层F1"),
            feature("c", FeatureKind.POLYGON, Geometry.Polygon(listOf(outer, hole)), "花岗岩体"),
        )
        val out = ByteArrayOutputStream()
        ShapefileFormat.write(list, out, ExportContext(title = "test"))
        val r = ShapefileFormat.read(ByteArrayInputStream(out.toByteArray()), ctx)
        assertEquals(3, r.features.size)
        val byTitle = r.features.associateBy { it.title }
        assertEquals(FeatureKind.POINT, byTitle.getValue("D001").kind)
        assertEquals(FeatureKind.LINE, byTitle.getValue("断层F1").kind)
        val poly = byTitle.getValue("花岗岩体").geometry as Geometry.Polygon
        assertEquals(2, poly.rings.size)
        assertEquals(outer.toSet(), poly.rings[0].toSet())
        assertEquals(hole.toSet(), poly.rings[1].toSet())
        // The exporter writes ASCII field names, so look for the value rather than the name.
        assertTrue(byTitle.getValue("D001").properties.values.any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() == 12.5 })
    }

    @Test
    fun readsGaussKrugerPointsWithGbkAttributes() {
        val p = LngLat(117.3, 31.8)
        val plane = GaussKruger.project(p, 3)
        val prj = """PROJCS["CGCS2000_3_Degree_GK_Zone_39",GEOGCS["GCS_China_Geodetic_Coordinate_System_2000",DATUM["D_China_2000",SPHEROID["CGCS2000",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Gauss_Kruger"],PARAMETER["False_Easting",39500000.0],PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",117.0],PARAMETER["Scale_Factor",1.0],PARAMETER["Latitude_Of_Origin",0.0],UNIT["Meter",1.0]]"""
        val zip = zipOf(
            "data/采样点.shp" to pointShp(plane.yWithZone, plane.x),
            "data/采样点.dbf" to dbf("名称", "样品一号".toByteArray(charset("GBK"))),
            "data/采样点.prj" to prj.toByteArray(),
        )
        val r = ShapefileFormat.read(ByteArrayInputStream(zip), ctx)
        val f = r.features.single()
        assertEquals("样品一号", f.title)
        assertEquals(0.0, Geodesy.distance(p, (f.geometry as Geometry.Point).position), 0.01)

        // Without a .prj the zone number in front of the easting tells the zone.
        val bare = ShapefileFormat.read(ByteArrayInputStream(zipOf("a.shp" to pointShp(plane.yWithZone, plane.x))), ctx)
        assertEquals(0.0, Geodesy.distance(p, (bare.features.single().geometry as Geometry.Point).position), 0.01)
        assertTrue(bare.warnings.any { "39" in it })
    }

    @Test
    fun explainsZipsWithoutShapefiles() {
        try {
            ShapefileFormat.read(ByteArrayInputStream(zipOf("project.json" to "{}".toByteArray())), ctx)
            fail("expected FormatException")
        } catch (e: FormatException) {
            assertTrue(e.message!!.contains("工程包"))
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun pointShp(x: Double, y: Double): ByteArray {
        val b = ByteBuffer.allocate(100 + 8 + 20)
        b.order(ByteOrder.BIG_ENDIAN).putInt(0, 9994).putInt(24, 64).putInt(100, 1).putInt(104, 10)
        b.order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1000).putInt(32, 1).putInt(108, 1).putDouble(112, x).putDouble(120, y)
        return b.array()
    }

    /** One character field holding one GBK value, with no .cpg. */
    private fun dbf(name: String, value: ByteArray): ByteArray {
        val len = 40
        val header = 32 + 32 + 1
        val b = ByteBuffer.allocate(header + 1 + len + 1).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 3).putInt(4, 1).putShort(8, header.toShort()).putShort(10, (1 + len).toShort())
        val n = name.toByteArray(charset("GBK"))
        for (i in n.indices) b.put(32 + i, n[i])
        b.put(32 + 11, 'C'.code.toByte()).put(32 + 16, len.toByte())
        b.put(64, 0x0D)
        b.put(header, ' '.code.toByte())
        for (i in 0 until len) b.put(header + 1 + i, if (i < value.size) value[i] else ' '.code.toByte())
        b.put(header + 1 + len, 0x1A)
        return b.array()
    }
}
