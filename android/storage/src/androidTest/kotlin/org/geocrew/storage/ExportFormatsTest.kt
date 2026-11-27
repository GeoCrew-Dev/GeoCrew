package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// A layer, or a whole project, in each format the export offers (ADR 0004), with what other programs need from each.
@RunWith(AndroidJUnit4::class)
class ExportFormatsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-formats")
    private lateinit var registry: ActionRegistry

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        val workspace = Workspace(Projects(root, File(context.noBackupFilesDir, "journals-formats").apply { deleteRecursively() }))
        registry = ActionRegistry().apply { workspace.actions.forEach(::register) }
        run(Workspace.CREATE, """{"name": "格式", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "断层", "language": "zh", "template": "field-line"}""")
        run(Workspace.LAYER_CREATE, """{"name": "地块", "language": "zh", "template": "field-polygon"}""")
        for (layer in listOf("野外点", "断层", "地块")) run(Editing.START, """{"layer": "$layer"}""")
        run(Editing.ADD, """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点名": "D001", "描述": "花岗岩, \"露头\""}}""")
        run(Editing.ADD, """{"layer": "断层", "geometry": {"type": "LineString", "coordinates": [[114.3, 30.6], [114.31, 30.61]]}, "properties": {"名称": "F1"}}""")
        run(Editing.ADD, """{"layer": "地块", "geometry": {"type": "Polygon", "coordinates": [[[114.3, 30.6], [114.31, 30.6], [114.31, 30.61], [114.3, 30.6]]]}, "properties": {"名称": "A1"}}""")
        run(Editing.SAVE_ALL)
    }

    private fun run(action: String, params: String = "{}"): Outcome =
        runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }

    private fun export(params: String): File {
        val done = run(Workspace.EXPORT, params)
        assertTrue(done is Outcome.Done, "$params: $done")
        return File((done.output as JsonObject).getValue("path").jsonPrimitive.content)
    }

    private fun entries(zip: File): List<String> = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }

    @Test
    fun aLayerGoesOutInEveryFormat() {
        val shp = export("""{"format": "shp", "layer": "野外点"}""")
        assertTrue(shp.name.endsWith(".zip"))
        assertTrue(listOf("shp", "shx", "dbf", "prj", "cpg").all { ext -> entries(shp).any { it == "野外点.$ext" } }, entries(shp).toString())
        ZipFile(shp).use { z -> assertEquals("UTF-8", z.getInputStream(z.getEntry("野外点.cpg")).reader().readText().trim()) }

        val geojson = Json.parseToJsonElement(export("""{"format": "geojson", "layer": "野外点"}""").readText()).jsonObject
        val point = geojson.getValue("features").jsonArray.single().jsonObject
        assertEquals("D001", point.getValue("properties").jsonObject.getValue("点名").jsonPrimitive.content)
        assertEquals(114.3, point.getValue("geometry").jsonObject.getValue("coordinates").jsonArray[0].jsonPrimitive.double, 1e-6, "in WGS84 longitude")

        // CSV: Excel reads Chinese with a byte order mark; a point's coordinates are named, a line's are WKT.
        val csv = export("""{"format": "csv", "layer": "野外点", "language": "zh"}""").readText()
        assertTrue(csv.startsWith("\uFEFF经度,纬度,"), csv)
        assertTrue(csv.contains("114.3") && csv.contains("花岗岩, \"\"露头\"\""), csv)
        assertTrue(export("""{"format": "csv", "layer": "断层"}""").readText().contains("LINESTRING"))
        assertTrue(export("""{"format": "csv", "layer": "野外点"}""").readText().startsWith("\uFEFFLongitude,Latitude,"))

        val xlsx = export("""{"format": "xlsx", "layer": "野外点", "language": "zh"}""")
        assertTrue(entries(xlsx).contains("xl/workbook.xml"), entries(xlsx).toString())
        ZipFile(xlsx).use { z ->
            assertTrue(z.getInputStream(z.getEntry("xl/workbook.xml")).reader().readText().contains("野外点"), "the sheet is named for the layer")
            val strings = z.getEntry("xl/sharedStrings.xml")?.let { z.getInputStream(it).reader().readText() }.orEmpty()
            assertTrue(strings.contains("经度") && strings.contains("D001"), strings)
        }

        val gpx = export("""{"format": "gpx", "layer": "野外点"}""").readText()
        assertTrue(gpx.contains("""<wpt lat="30.6" lon="114.3"><name>D001</name><desc>花岗岩, "露头"</desc></wpt>""") || gpx.contains("<name>D001</name>"), gpx)
        assertTrue(export("""{"format": "gpx", "layer": "断层"}""").readText().contains("<trk><name>F1</name><trkseg>"))
        assertEquals("unsupported", (run(Workspace.EXPORT, """{"format": "gpx", "layer": "地块"}""") as Outcome.Failed).code, "GPX has no areas")

        val dxf = export("""{"format": "dxf", "layer": "断层"}""").readText()
        assertTrue(dxf.contains("ENTITIES") && dxf.contains("LWPOLYLINE") || dxf.contains("POLYLINE"), dxf.take(200))
        assertTrue(File(root, "导出").list()!!.none { it.startsWith(".") }, "nothing half-written stays")
    }

    @Test
    fun aProjectGoesOutInEveryFormat() {
        // A format of one layer: a zip, a file each.
        val geojson = export("""{"format": "geojson"}""")
        assertTrue(geojson.name.endsWith(".geojson.zip"), geojson.name)
        assertEquals(listOf("地块.geojson", "断层.geojson", "野外点.geojson").sorted(), entries(geojson).sorted())
        assertEquals(3, entries(export("""{"format": "csv"}""")).size)
        assertEquals(3, entries(export("""{"format": "dxf"}""")).size)
        assertEquals(3, entries(export("""{"format": "shp"}""")).count { it.endsWith(".shp") })
        ZipFile(export("""{"format": "xlsx"}""")).use { z ->
            val book = z.getInputStream(z.getEntry("xl/workbook.xml")).reader().readText()
            assertTrue(listOf("野外点", "断层", "地块").all { book.contains(it) }, "a sheet a layer: $book")
        }
        // GPX takes the points and lines and leaves the areas.
        val gpx = export("""{"format": "gpx"}""").readText()
        assertTrue(gpx.indexOf("<wpt") in 0 until gpx.indexOf("<trk"), "waypoints before tracks")
        assertTrue(!gpx.contains("A1"))
    }
}
