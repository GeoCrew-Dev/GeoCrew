package org.geocrew.core.formats

import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.FieldType
import org.geocrew.core.model.GaussKruger
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/** SQLite through JDBC, standing in for Android's SQLite. */
private class JdbcDatabase(private val c: Connection) : SqlDatabase {
    private fun java.sql.PreparedStatement.bind(args: List<Any?>) = args.forEachIndexed { i, v -> setObject(i + 1, v) }
    override fun execute(sql: String, args: List<Any?>) { c.prepareStatement(sql).use { it.bind(args); it.execute() } }
    override fun query(sql: String, args: List<Any?>): List<List<Any?>> = c.prepareStatement(sql).use { st ->
        st.bind(args)
        st.executeQuery().use { rs ->
            val n = rs.metaData.columnCount
            buildList {
                while (rs.next()) add(List(n) { i ->
                    when (val v = rs.getObject(i + 1)) {
                        is Int -> v.toLong()
                        is Float -> v.toDouble()
                        else -> v
                    }
                })
            }
        }
    }
    override fun <T> transaction(block: () -> T): T {
        c.autoCommit = false
        try {
            return block().also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }
    override fun close() = c.close()
}

class GeoPackageFormatTest {
    @get:Rule val tmp = TemporaryFolder()

    private val engine = SqlEngine { f -> JdbcDatabase(DriverManager.getConnection("jdbc:sqlite:" + f.path)) }
    private val format by lazy { GeoPackageFormat(engine) { tmp.root } }
    private val t0 = Instant.parse("2026-11-24T00:00:00Z")

    private fun feature(id: String, kind: FeatureKind, g: Geometry?, edit: org.geocrew.core.model.PropertiesBuilder.() -> Unit = {}) =
        GeoFeature(id, "p", kind, g, buildProperties(edit), t0, SyncInfo(updatedAt = t0))

    @Test
    fun roundTripsRecordsThroughAFileOtherGisCanRead() {
        val list = listOf(
            feature("a", FeatureKind.GEO_POINT, Geometry.Point(LngLat(117.3, 31.8, 52.5))) {
                this[Props.TITLE] = "D001"; this[Props.LITHOLOGY] = "灰岩"; this[Props.DIP_DIRECTION] = 135.0; this[Props.DIP] = 40.0; this["formation"] = "栖霞组"
            },
            feature("b", FeatureKind.LINE, Geometry.MultiLineString(listOf(listOf(LngLat(117.0, 31.0), LngLat(117.1, 31.1)), listOf(LngLat(117.2, 31.0), LngLat(117.3, 31.2))))) {
                this[Props.LINE_STYLE] = "inferred_fault"
            },
            feature("c", FeatureKind.POLYGON, Geometry.Polygon.of(listOf(LngLat(117.0, 31.0), LngLat(117.1, 31.0), LngLat(117.1, 31.1)))) { this["fill"] = "#22a884" },
            feature("d", FeatureKind.POINT, null),
            feature("e", FeatureKind.POINT, Geometry.GeometryCollection(listOf(Geometry.Point(LngLat(1.0, 1.0))))),
        )
        val ctx = ExportContext(title = "北山", fields = listOf(FieldTemplate("formation", "地层"), FieldTemplate("thick", "厚度", FieldType.NUMBER)))
        val out = ByteArrayOutputStream()
        val r = format.write(list, out, ctx)
        assertEquals(3, r.written)
        assertEquals(2, r.skipped)
        val bytes = out.toByteArray()
        assertTrue(String(bytes, 0, 15) == "SQLite format 3")

        // What a desktop GIS sees: three layers in EPSG:4326 and readable columns.
        val file = tmp.newFile("check.gpkg").also { it.writeBytes(bytes) }
        engine.open(file).use { db ->
            assertEquals(listOf(listOf<Any?>(1196444487L)), db.query("PRAGMA application_id"))
            assertEquals(listOf("lines", "points", "polygons"), db.query("SELECT table_name FROM gpkg_contents ORDER BY 1").map { it[0] })
            val row = db.query("SELECT title, lithology, dip, \"地层\", \"厚度\" FROM points").single()
            assertEquals(listOf<Any?>("D001", "灰岩", 40.0, "栖霞组", null), row)
            assertEquals("#22A884", db.query("SELECT color FROM polygons").single()[0])
            assertEquals("inferred_fault", db.query("SELECT line_style FROM lines").single()[0])
        }

        // And back: the same records, properties and all.
        val back = format.read(ByteArrayInputStream(bytes), ImportContext("q")).features.associateBy { it.id }
        assertEquals(setOf("a", "b", "c"), back.keys)
        assertEquals(list[0].geometry, back.getValue("a").geometry)
        assertEquals(FeatureKind.GEO_POINT, back.getValue("a").kind)
        assertEquals("栖霞组", back.getValue("a").properties["formation"]!!.jsonPrimitive.content)
        assertEquals(list[1].geometry, back.getValue("b").geometry)
        assertEquals(list[2].geometry, back.getValue("c").geometry)
        assertTrue(tmp.root.listFiles()!!.all { it.name == "check.gpkg" })
    }

    @Test
    fun readsGaussKrugerLayersFromOtherSoftware() {
        // A layer as a Chinese GIS might write it: CGCS2000 3° zone 39 with the zone
        // number in the easting, big-endian 3D WKB, no envelope, columns of its own.
        val p = LngLat(117.3, 31.8)
        val gk = GaussKruger.project(p, 3, zone = 39)
        val file = tmp.newFile("foreign.gpkg")
        engine.open(file).use { db ->
            db.execute("CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT, srs_id INTEGER PRIMARY KEY, organization TEXT, organization_coordsys_id INTEGER, definition TEXT, description TEXT)")
            db.execute("CREATE TABLE gpkg_contents (table_name TEXT PRIMARY KEY, data_type TEXT, identifier TEXT, srs_id INTEGER)")
            db.execute("CREATE TABLE gpkg_geometry_columns (table_name TEXT, column_name TEXT, geometry_type_name TEXT, srs_id INTEGER, z INTEGER, m INTEGER)")
            db.execute(
                "INSERT INTO gpkg_spatial_ref_sys VALUES ('CGCS2000 / 3-degree GK zone 39', 4527, 'EPSG', 4527, ?, '')",
                listOf("""PROJCS["CGCS2000 / 3-degree Gauss-Kruger zone 39",GEOGCS["China Geodetic Coordinate System 2000",DATUM["China_2000",SPHEROID["CGCS2000",6378137,298.257222101]]],PROJECTION["Transverse_Mercator"],PARAMETER["latitude_of_origin",0],PARAMETER["central_meridian",117],PARAMETER["scale_factor",1],PARAMETER["false_easting",39500000],PARAMETER["false_northing",0],UNIT["metre",1]]"""),
            )
            db.execute("INSERT INTO gpkg_contents VALUES ('dzd', 'features', '地质点', 4527)")
            db.execute("INSERT INTO gpkg_geometry_columns VALUES ('dzd', 'shape', 'POINT', 4527, 1, 0)")
            db.execute("CREATE TABLE dzd (OBJECTID INTEGER PRIMARY KEY, shape BLOB, 点号 TEXT, 岩性 TEXT, 高程 REAL, 序号 INTEGER)")
            val wkb = ByteBuffer.allocate(8 + 1 + 4 + 24).order(ByteOrder.BIG_ENDIAN)
            wkb.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(0) // big endian header, no envelope
            wkb.putInt(4527)
            wkb.put(0).putInt(1001).putDouble(gk.yWithZone).putDouble(gk.x).putDouble(88.0)
            db.execute("INSERT INTO dzd VALUES (7, ?, 'D12', '花岗岩', 88.0, 3)", listOf(wkb.array()))
        }
        val r = format.read(file.inputStream(), ImportContext("q", fields = listOf(FieldTemplate(Props.LITHOLOGY, "岩性"))))
        val f = r.features.single()
        val at = (f.geometry as Geometry.Point).position
        assertEquals(p.lng, at.lng, 1e-7)
        assertEquals(p.lat, at.lat, 1e-7)
        assertEquals(88.0, at.alt!!, 0.0)
        assertEquals("D12", f.title)
        assertEquals("花岗岩", f.properties[Props.LITHOLOGY]!!.jsonPrimitive.content)
        assertEquals(3L, f.properties["序号"]!!.jsonPrimitive.content.toLong())
        assertEquals("地质点", f.properties["layer"]!!.jsonPrimitive.content)
        assertEquals(FeatureKind.POINT, f.kind)
    }

    @Test
    fun refusesFilesThatAreNotGeoPackages() {
        val e = runCatching { format.read(ByteArrayInputStream("hello".toByteArray()), ImportContext("q")) }.exceptionOrNull()
        assertTrue(e is FormatException)
        val plain = tmp.newFile("plain.db")
        engine.open(plain).use { it.execute("CREATE TABLE t (a)") }
        assertTrue(runCatching { format.read(plain.inputStream(), ImportContext("q")) }.exceptionOrNull() is FormatException)
    }

    @Test
    fun registeredFormatsAreFoundByExtensionAndId() {
        FeatureFormats.register(format)
        FeatureFormats.register(GeoPackageFormat(engine) { tmp.root }) // same id replaces, not duplicates
        assertEquals(1, FeatureFormats.all.count { it.id == "gpkg" })
        assertEquals("gpkg", FeatureFormats.detect("路线.GPKG", null)?.id)
        assertEquals("gpkg", FeatureFormats.byId("gpkg")?.id)
        assertEquals(GeoJsonFormat, FeatureFormats.all.first())
    }
}
