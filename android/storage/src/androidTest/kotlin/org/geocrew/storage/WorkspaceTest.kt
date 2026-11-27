package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.nativelib.VectorDataset
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Projects and their layers through the action registry, as the interface and the AI use them.
@RunWith(AndroidJUnit4::class)
class WorkspaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-workspace")
    private val journals = File(context.noBackupFilesDir, "journals-workspace")
    private lateinit var workspace: Workspace
    private lateinit var registry: ActionRegistry

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        journals.deleteRecursively()
        workspace = Workspace(Projects(root, journals))
        registry = ActionRegistry().apply { workspace.actions.forEach(::register) }
    }

    private fun run(action: String, params: String = "{}"): Outcome =
        runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }

    private fun output(o: Outcome): JsonObject = (o as Outcome.Done).output.jsonObject

    @Test
    fun aFieldDayThroughActions() {
        val created = output(run(Workspace.CREATE, """{"name": "测区", "language": "zh"}"""))
        assertTrue(created.getValue("layers").jsonArray.isEmpty(), "a new project starts empty")
        val made = output(run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")).getValue("layers").jsonArray.single().jsonObject
        assertEquals(listOf("野外点", "field-point", "POINT", "EPSG:4490"), listOf("name", "template", "geometry", "srs").map { made.getValue(it).jsonPrimitive.content })
        assertIs<Outcome.Failed>(run(Workspace.LAYER_CREATE, """{"name": "野外点"}"""), "the name is taken")
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.ADD, """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点名": "D001"}}""")
        assertEquals(Outcome.Failed("unsaved", "save or discard the edits of 野外点 first"), run(Workspace.CLOSE))
        assertIs<Outcome.Failed>(run(Workspace.OPEN, """{"name": "测区"}"""), "nor can another project open")
        run(Editing.SAVE_ALL)
        // An undone point leaves nothing unsaved, and something to redo, until the project closes.
        run(Editing.ADD, """{"layer": "野外点", "properties": {"点名": "D002"}}""")
        run(ActionRegistry.UNDO, """{"scope": "project"}""")
        assertTrue(registry.canRedo(Workspace.PROJECT_SCOPE))
        assertIs<Outcome.Done>(run(Workspace.CLOSE), "the layer stops being edited, with nothing unsaved")
        assertNull(workspace.current)
        assertFalse(registry.canRedo(Workspace.PROJECT_SCOPE), "closing ends the project's history")

        val listed = output(run(Workspace.LIST)).getValue("projects").jsonArray.single().jsonObject
        assertEquals("测区", listed.getValue("name").jsonPrimitive.content)
        assertTrue(listed.getValue("modified").jsonPrimitive.content.toLong() > 0)
        assertEquals(1, listed.getValue("layers").jsonPrimitive.content.toInt())
        assertEquals(1L, listed.getValue("features").jsonPrimitive.content.toLong(), "the saved point")
        assertTrue(listed.getValue("bytes").jsonPrimitive.content.toLong() > 0)
        assertTrue(listed.containsKey("created"))
        assertIs<Outcome.Done>(run(Workspace.OPEN, """{"name": "测区"}"""))
        val target = workspace.current!!.target("野外点")
        assertEquals(1, target.store.features().size)
    }

    @Test
    fun layersReadAsGeoJson() {
        run(Workspace.CREATE, """{"name": "图", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        val empty = output(run(Workspace.FEATURES, """{"layer": "野外点"}"""))
        assertEquals("FeatureCollection", empty.getValue("type").jsonPrimitive.content)
        assertEquals(false, empty.getValue("editing").jsonPrimitive.content.toBoolean())
        val description = empty.getValue("fields").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "描述" }
        assertEquals("string", description.getValue("type").jsonPrimitive.content)
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.ADD, """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点名": "D001"}}""")
        val edited = output(run(Workspace.FEATURES, """{"layer": "野外点"}"""))
        assertEquals(true, edited.getValue("modified").jsonPrimitive.content.toBoolean())
        val feature = edited.getValue("features").jsonArray.single().jsonObject
        assertEquals(-1, feature.getValue("id").jsonPrimitive.content.toInt(), "an unsaved feature shows with its negative fid")
        assertEquals("D001", feature.getValue("properties").jsonObject.getValue("点名").jsonPrimitive.content)
    }

    @Test
    fun exportsHoldWhatIsSaved() {
        run(Workspace.CREATE, """{"name": "出图", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.ADD, """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点名": "D001", "描述": "花岗岩露头"}}""")
        assertEquals("unsaved", (run(Workspace.EXPORT, """{"format": "gpkg"}""") as Outcome.Failed).code)
        run(Editing.SAVE, """{"layer": "野外点"}""")

        val gpkg = File(output(run(Workspace.EXPORT, """{"format": "gpkg"}""")).getValue("path").jsonPrimitive.content)
        assertEquals(File(root, "导出"), gpkg.parentFile)
        VectorDataset.open(gpkg.path).use { ds ->
            assertEquals(1, Json.parseToJsonElement(ds.features("野外点")).jsonArray.size, "the saved point is in the copy")
            assertEquals("delete", Json.parseToJsonElement(ds.sql("PRAGMA journal_mode")).jsonArray.single().jsonObject
                .getValue("properties").jsonObject.getValue("journal_mode").jsonPrimitive.content)
        }

        val kml = File(output(run(Workspace.EXPORT, """{"format": "kml"}""")).getValue("path").jsonPrimitive.content)
        val text = kml.readText()
        assertTrue(text.contains("花岗岩露头") && text.contains("114.3"), text)
        assertTrue(!text.contains("geocrew_"), "GeoCrew's own tables are not exported")
        assertIs<Outcome.Invalid>(run(Workspace.EXPORT, """{"format": "shp"}"""))
    }

    // Users add the fields they need: a hydrogeologist the depth to water, say.
    @Test
    fun usersAddTheirOwnFields() {
        run(Workspace.CREATE, """{"name": "水文", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.ADD, """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点名": "S001"}}""")
        val field = """{"layer": "野外点", "name": "水位埋深", "type": "real"}"""
        assertEquals("unsaved", (run(Workspace.FIELD_ADD, field) as Outcome.Failed).code, "not with unsaved edits")
        run(Editing.SAVE, """{"layer": "野外点"}""")

        assertIs<Outcome.Done>(run(Workspace.FIELD_ADD, field), "a layer being edited with nothing unsaved stops editing first")
        val layer = output(run(Workspace.FEATURES, """{"layer": "野外点"}"""))
        val types = layer.getValue("fields").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("type").jsonPrimitive.content }
        assertEquals("real", types["水位埋深"])
        assertEquals(false, layer.getValue("editing").jsonPrimitive.content.toBoolean())

        run(Editing.START, """{"layer": "野外点"}""")
        val fid = layer.getValue("features").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
        assertIs<Outcome.Done>(run(Editing.VALUES, """{"layer": "野外点", "fid": $fid, "values": {"水位埋深": 3.25}}"""))
        run(Editing.SAVE, """{"layer": "野外点"}""")
        val saved = output(run(Workspace.FEATURES, """{"layer": "野外点"}""")).getValue("features").jsonArray.single().jsonObject
        assertEquals(3.25, saved.getValue("properties").jsonObject.getValue("水位埋深").jsonPrimitive.content.toDouble())

        assertIs<Outcome.Failed>(run(Workspace.FIELD_ADD, """{"layer": "野外点", "name": "点名", "type": "string"}"""), "names are unique")
        assertIs<Outcome.Failed>(run(Workspace.FIELD_ADD, """{"layer": "野外点", "name": "GEOM", "type": "string"}"""), "the geometry column")
        assertIs<Outcome.Invalid>(run(Workspace.FIELD_ADD, """{"layer": "野外点", "name": "x", "type": "blob"}"""))
    }

    @Test
    fun aLayerSaysItsFieldsAndCount() {
        run(Workspace.CREATE, """{"name": "信息", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        val empty = output(run(Workspace.LAYER_INFO, """{"layer": "野外点"}"""))
        assertEquals(listOf("点名", "描述"), empty.getValue("fields").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(0L, empty.getValue("count").jsonPrimitive.content.toLong())
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.ADD, """{"layer": "野外点", "properties": {"点名": "D001"}}""")
        assertEquals(1L, output(run(Workspace.LAYER_INFO, """{"layer": "野外点"}""")).getValue("count").jsonPrimitive.content.toLong(), "as edited")
        assertEquals(true, output(run(Workspace.FEATURES, """{"layer": "野外点"}""")).getValue("projectModified").jsonPrimitive.content.toBoolean())
        assertIs<Outcome.Done>(run(Editing.SAVE_ALL, "{}"))
        assertEquals(false, output(run(Workspace.FEATURES, """{"layer": "野外点"}""")).getValue("projectModified").jsonPrimitive.content.toBoolean())
    }

    // A point put in the wrong layer goes to the right one, every value with it.
    @Test
    fun aPointMovesToALayerMadeLikeItsOwn() {
        run(Workspace.CREATE, """{"name": "转移", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Workspace.FIELD_ADD, """{"layer": "野外点", "name": "流量", "type": "real"}""")
        run(Workspace.LAYER_CREATE, """{"name": "泉点", "like": "野外点"}""")
        val springs = output(run(Workspace.LAYER_INFO, """{"layer": "泉点"}"""))
        assertEquals(listOf("点名", "描述", "流量"), springs.getValue("fields").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.START, """{"layer": "泉点"}""")
        run(Editing.ADD, """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点名": "S1", "流量": 2.5}}""")
        val moved = output(run(Editing.MOVE, """{"layer": "野外点", "fid": -1, "to": "泉点"}"""))
        assertTrue(moved.getValue("dropped").jsonArray.isEmpty(), "nothing lost")
        assertIs<Outcome.Done>(run(Editing.SAVE_ALL))
        val target = workspace.current!!.target("泉点").store.features().single()
        assertEquals(JsonPrimitive(2.5), target.properties["流量"])
        assertEquals(0L, workspace.current!!.count("野外点"))
    }

    // Gauss-Kruger is stored as such; the map gets longitude and latitude.
    @Test
    fun aLayerInAnotherCrsShowsInLongitudeAndLatitude() {
        run(Workspace.CREATE, """{"name": "高斯", "language": "zh"}""")
        val made = output(run(Workspace.LAYER_CREATE, """{"name": "测点", "language": "zh", "srs": "EPSG:4547"}"""))
        assertEquals("EPSG:4547", made.getValue("layers").jsonArray.single().jsonObject.getValue("srs").jsonPrimitive.content)
        run(Editing.START, """{"layer": "测点"}""")
        run(Editing.ADD, """{"layer": "测点", "geometry": {"type": "Point", "coordinates": [500000, 3320000]}, "properties": {"点名": "G1"}}""")
        val shown = output(run(Workspace.FEATURES, """{"layer": "测点", "srs": "EPSG:4490"}"""))
        assertEquals("EPSG:4490", shown.getValue("srs").jsonPrimitive.content)
        val xy = shown.getValue("features").jsonArray.single().jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(114.0, xy[0].jsonPrimitive.content.toDouble(), 1e-9, "the central meridian")
        assertTrue(xy[1].jsonPrimitive.content.toDouble() in 29.9..30.1)
        val stored = output(run(Workspace.FEATURES, """{"layer": "测点"}"""))
        assertEquals("EPSG:4547", stored.getValue("srs").jsonPrimitive.content)

        val back = output(run(Workspace.CRS_TRANSFORM, """{"from": "EPSG:4490", "to": "EPSG:4547", "xy": [114.0, 30.0]}"""))
        assertEquals(500000.0, back.getValue("xy").jsonArray[0].jsonPrimitive.content.toDouble(), 1e-6)
        assertEquals(true, output(run(Workspace.CRS_DESCRIBE, """{"srs": "EPSG:4547"}""")).getValue("projected").jsonPrimitive.content.toBoolean())
        assertIs<Outcome.Failed>(run(Workspace.LAYER_CREATE, """{"name": "坏", "srs": "EPSG:999999"}"""), "an unknown CRS")
        assertIs<Outcome.Failed>(run(Workspace.CRS_DESCRIBE, """{"srs": "nonsense"}"""))
        // The CGCS2000 Gauss-Kruger codes the new-layer dialog picks from the map's longitude.
        fun name(code: Int) = output(run(Workspace.CRS_DESCRIBE, """{"srs": "EPSG:$code"}""")).getValue("name").jsonPrimitive.content
        assertEquals("CGCS2000 / 3-degree Gauss-Kruger zone 38", name(4526))
        assertEquals("CGCS2000 / 3-degree Gauss-Kruger CM 114E", name(4547))
        assertEquals("CGCS2000 / Gauss-Kruger zone 19", name(4497))
        assertEquals("CGCS2000 / Gauss-Kruger CM 111E", name(4508))
    }

    // As in QGIS's layer panel: shown or not, how opaque, and which is on top; kept in the project.
    @Test
    fun layersShowAsSetAndKeepIt() {
        run(Workspace.CREATE, """{"name": "显示", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "泉点", "language": "zh"}""")
        run(Workspace.LAYER_DISPLAY, """{"layer": "野外点", "visible": false, "opacity": 0.5}""")
        run(Workspace.BASEMAP_DISPLAY, """{"basemap": "cia", "visible": false}""")
        val ordered = output(run(Workspace.LAYER_ORDER, """{"layers": ["泉点", "野外点"]}"""))
        assertEquals(listOf("泉点", "野外点"), ordered.getValue("layers").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertIs<Outcome.Failed>(run(Workspace.LAYER_ORDER, """{"layers": ["泉点"]}"""), "every layer once")
        val withNew = output(run(Workspace.LAYER_CREATE, """{"name": "路线点", "language": "zh"}"""))
        assertEquals("路线点", withNew.getValue("layers").jsonArray.first().jsonObject.getValue("name").jsonPrimitive.content, "a new layer goes on top")
        run(Workspace.LAYER_ORDER, """{"layers": ["泉点", "野外点", "路线点"]}""")
        assertFalse(run(Workspace.LAYER_DISPLAY, """{"layer": "泉点", "opacity": 2}""") is Outcome.Done, "opacity goes from 0 to 1")
        run(Workspace.CLOSE)

        val again = output(run(Workspace.OPEN, """{"name": "显示"}"""))
        val layers = again.getValue("layers").jsonArray.map { it.jsonObject }
        assertEquals(listOf("泉点", "野外点", "路线点"), layers.map { it.getValue("name").jsonPrimitive.content })
        assertEquals("false", layers[1].getValue("visible").jsonPrimitive.content)
        assertEquals(0.5, layers[1].getValue("opacity").jsonPrimitive.content.toDouble())
        val basemaps = again.getValue("basemaps").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals("true", basemaps.getValue("img").getValue("visible").jsonPrimitive.content)
        assertEquals("false", basemaps.getValue("cia").getValue("visible").jsonPrimitive.content)
    }

    // GeoPackage's own tables must follow, or QGIS sees a broken file.
    @Test
    fun layersAreRenamedAndDeletedCleanly() {
        run(Workspace.CREATE, """{"name": "改名", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "临时", "language": "zh"}""")
        run(Workspace.LAYER_DISPLAY, """{"layer": "野外点", "opacity": 0.4}""")
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.ADD, """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点名": "D001"}}""")
        assertEquals("unsaved", (run(Workspace.LAYER_RENAME, """{"layer": "野外点", "to": "泉点"}""") as Outcome.Failed).code)
        run(Editing.SAVE_ALL)

        val renamed = output(run(Workspace.LAYER_RENAME, """{"layer": "野外点", "to": "泉点"}"""))
        val springs = renamed.getValue("layers").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "泉点" }
        assertEquals(0.4, springs.getValue("opacity").jsonPrimitive.content.toDouble(), "the display stays with the layer")
        assertEquals("D001", workspace.current!!.target("泉点").store.features().single().properties.getValue("点名").jsonPrimitive.content)
        assertIs<Outcome.Failed>(run(Workspace.LAYER_RENAME, """{"layer": "泉点", "to": "临时"}"""), "the name is taken")
        assertIs<Outcome.Done>(run(Workspace.LAYER_RENAME, """{"layer": "临时", "to": "临时a"}"""))
        assertIs<Outcome.Done>(run(Workspace.LAYER_RENAME, """{"layer": "临时a", "to": "临时A"}"""), "only the case changes")

        val deleted = output(run(Workspace.LAYER_DELETE, """{"layer": "临时A"}"""))
        assertEquals(listOf("泉点"), deleted.getValue("layers").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        run(Workspace.CLOSE)
        val path = File(root, "工程/改名/改名.gpkg").path
        val contents = VectorDataset.open(path).use { ds ->
            Json.parseToJsonElement(ds.sql("SELECT table_name FROM gpkg_contents ORDER BY table_name")).jsonArray
                .map { it.jsonObject.getValue("properties").jsonObject.getValue("table_name").jsonPrimitive.content }
        }
        assertEquals(listOf("泉点"), contents.filter { !it.startsWith("geocrew_") }, "GeoPackage's register follows: $contents")
        assertIs<Outcome.Done>(run(Workspace.OPEN, """{"name": "改名"}"""))
        assertIs<Outcome.Done>(run(Workspace.EXPORT, """{"format": "kml"}"""))
    }

    @Test
    fun bookmarksAreSavedInTheProject() {
        run(Workspace.CREATE, """{"name": "书签", "language": "zh"}""")
        assertEquals(0, output(run(Workspace.BOOKMARKS)).getValue("bookmarks").jsonArray.size, "none yet")
        run(Workspace.BOOKMARK_ADD, """{"name": "一号剖面", "longitude": 114.3, "latitude": 30.6, "zoom": 15}""")
        val added = output(run(Workspace.BOOKMARK_ADD, """{"name": " 二号 ", "longitude": 114.4, "latitude": 30.7, "zoom": 12.5, "bearing": 30}"""))
        val second = added.getValue("bookmarks").jsonArray[1].jsonObject
        assertEquals("二号", second.getValue("name").jsonPrimitive.content, "the name is trimmed")
        assertEquals(30.0, second.getValue("bearing").jsonPrimitive.content.toDouble())
        assertIs<Outcome.Failed>(run(Workspace.BOOKMARK_ADD, """{"name": " ", "longitude": 114.4, "latitude": 30.7, "zoom": 12}"""), "a name is needed")

        run(Workspace.CLOSE)
        assertIs<Outcome.Done>(run(Workspace.OPEN, """{"name": "书签"}"""))
        val listed = run(Workspace.BOOKMARKS)
        assertIs<Outcome.Done>(listed, listed.toString())
        val kept = output(listed).getValue("bookmarks").jsonArray.map { it.jsonObject }
        assertEquals(listOf("一号剖面", "二号"), kept.map { it.getValue("name").jsonPrimitive.content }, "kept in the file")
        val first = kept[0].getValue("id").jsonPrimitive.content
        val left = output(run(Workspace.BOOKMARK_DELETE, "{\"id\": $first}")).getValue("bookmarks").jsonArray
        assertEquals(listOf("二号"), left.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertIs<Outcome.Failed>(run(Workspace.BOOKMARK_DELETE, "{\"id\": $first}"), "already gone")
    }

    // The CRS picker searches all of PROJ's CRSs, in Chinese words too.
    @Test
    fun crsSearchFindsByCodeNameChineseAndPlace() {
        fun found(params: String): List<JsonObject> {
            val outcome = run(Workspace.CRS_SEARCH, params)
            assertIs<Outcome.Done>(outcome, "$params: $outcome")
            return outcome.output.jsonObject.getValue("crs").jsonArray.map { it.jsonObject }
        }
        fun srs(params: String) = found(params).map { it.getValue("srs").jsonPrimitive.content }

        assertEquals("EPSG:4547", srs("""{"text": "4547"}""").first(), "a code comes first")
        assertEquals("EPSG:4547", srs("""{"text": "epsg:4547"}""").first())
        val year = srs("""{"text": "2000"}""")
        assertTrue("EPSG:2000" !in year && "EPSG:4490" in year, "2000 is CGCS2000's year, not EPSG:2000")
        val wgs = found("""{"text": "WGS84"}""").first { it.getValue("srs").jsonPrimitive.content == "EPSG:4326" }
        assertEquals("WGS 84", wgs.getValue("name").jsonPrimitive.content, "spaces don't matter")
        assertEquals("geographic 2D", wgs.getValue("type").jsonPrimitive.content)
        assertTrue("EPSG:4610" in srs("""{"text": "西安80"}"""), "Xian 1980 from Chinese")
        assertTrue("EPSG:4214" in srs("""{"text": "北京54"}"""), "Beijing 1954 from Chinese")
        assertEquals(listOf("EPSG:4526"), srs("""{"text": "国家2000 高斯 38带"}"""), "zone 38 of the 3-degree zones")
        assertEquals(listOf("EPSG:4547"), srs("""{"text": "2000 三度带 中央经线114"}"""))

        // Where the map is: only the zones that apply there, and an area to show.
        val here = found("""{"text": "cgcs2000 3度带", "near": [114.3, 30.6]}""")
        assertEquals(setOf("EPSG:4526", "EPSG:4547"), here.map { it.getValue("srs").jsonPrimitive.content }.toSet())
        assertTrue(here.all { it.getValue("area").jsonPrimitive.content.startsWith("China") })
        assertTrue(srs("""{"near": [114.3, 30.6]}""").size > 20, "every CRS for the place, with no words")
        assertTrue(srs("""{"text": "2000", "kinds": ["geographic"]}""").all { it != "EPSG:4547" }, "no projected CRS")

        assertEquals(listOf("EPSG:4490", "EPSG:4547"), srs("""{"srs": ["EPSG:4490", "EPSG:999999", "EPSG:4547"]}"""), "by code, unknown ones left out")
        assertEquals(emptyList(), srs("""{"text": ""}"""))
        assertIs<Outcome.Failed>(run(Workspace.CRS_SEARCH, """{"near": [114.3]}"""))
    }

    // Lines and areas, not only points: drawn, saved, read back and exported.
    @Test
    fun lineAndPolygonLayersKeepTheirShapes() {
        run(Workspace.CREATE, """{"name": "线面", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "断层", "template": "field-line", "language": "zh"}""")
        val made = output(run(Workspace.LAYER_CREATE, """{"name": "露头", "template": "field-polygon", "language": "zh"}"""))
        val kinds = made.getValue("layers").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("geometry").jsonPrimitive.content }
        assertEquals(mapOf("断层" to "LINESTRING", "露头" to "POLYGON"), kinds)
        assertEquals(listOf("名称", "描述"), workspace.current!!.target("断层").store.fields.map { it.name })

        run(Editing.START, """{"layer": "断层"}""")
        run(Editing.START, """{"layer": "露头"}""")
        val line = """{"type": "LineString", "coordinates": [[114.30, 30.60], [114.31, 30.61], [114.32, 30.60]]}"""
        val area = """{"type": "Polygon", "coordinates": [[[114.30, 30.60], [114.31, 30.60], [114.31, 30.61], [114.30, 30.60]]]}"""
        assertIs<Outcome.Done>(run(Editing.ADD, "{\"layer\": \"断层\", \"geometry\": $line, \"properties\": {\"名称\": \"F1\"}}"))
        assertIs<Outcome.Done>(run(Editing.ADD, "{\"layer\": \"露头\", \"geometry\": $area, \"properties\": {\"名称\": \"O1\"}}"))
        run(Editing.SAVE_ALL)
        run(Workspace.CLOSE)
        run(Workspace.OPEN, """{"name": "线面"}""")

        fun geometry(layer: String) = output(run(Workspace.FEATURES, "{\"layer\": \"$layer\"}")).getValue("features").jsonArray.single()
            .jsonObject.getValue("geometry").jsonObject
        val savedLine = geometry("断层")
        assertEquals("LineString", savedLine.getValue("type").jsonPrimitive.content)
        assertEquals(3, savedLine.getValue("coordinates").jsonArray.size)
        val savedArea = geometry("露头")
        assertEquals("Polygon", savedArea.getValue("type").jsonPrimitive.content)
        assertEquals(4, savedArea.getValue("coordinates").jsonArray.single().jsonArray.size, "the ring is closed")
        assertIs<Outcome.Done>(run(Workspace.EXPORT, """{"format": "kml"}"""))
        val card = output(run(Workspace.LIST)).getValue("projects").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "线面" }
        assertEquals(listOf(0L, 1L, 1L), listOf("points", "lines", "areas").map { card.getValue(it).jsonPrimitive.content.toLong() }, "counted by kind")
    }

    // A recorded track goes into a track layer with when it started and ended, and its length.
    @Test
    fun trackLayersKeepTimesAndLength() {
        run(Workspace.CREATE, """{"name": "轨迹", "language": "zh"}""")
        val made = output(run(Workspace.LAYER_CREATE, """{"name": "轨迹", "template": "track", "language": "zh"}"""))
        val layer = made.getValue("layers").jsonArray.single().jsonObject
        assertEquals("track", layer.getValue("template").jsonPrimitive.content)
        assertEquals("LINESTRING", layer.getValue("geometry").jsonPrimitive.content)
        assertEquals(listOf("名称", "描述", "开始时间", "结束时间", "长度（米）"), workspace.current!!.target("轨迹").store.fields.map { it.name })

        run(Editing.START, """{"layer": "轨迹"}""")
        val track = """{"type": "LineString", "coordinates": [[114.30, 30.60], [114.301, 30.601], [114.302, 30.6015]]}"""
        val values = """{"名称": "2026-11-27 08:30", "开始时间": "2026-11-27T00:30:00Z", "结束时间": "2026-11-27T01:05:12Z", "长度（米）": 268.4}"""
        assertIs<Outcome.Done>(run(Editing.ADD, "{\"layer\": \"轨迹\", \"geometry\": $track, \"properties\": $values}"))
        run(Editing.SAVE_ALL)
        run(Workspace.CLOSE)
        run(Workspace.OPEN, """{"name": "轨迹"}""")

        val saved = output(run(Workspace.FEATURES, """{"layer": "轨迹"}""")).getValue("features").jsonArray.single().jsonObject
        val properties = saved.getValue("properties").jsonObject
        assertEquals(3, saved.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.size)
        assertTrue(properties.getValue("开始时间").jsonPrimitive.content.startsWith("2026-11-27T00:30:00"), "kept: ${properties["开始时间"]}")
        assertTrue(properties.getValue("结束时间").jsonPrimitive.content.startsWith("2026-11-27T01:05:12"), "kept: ${properties["结束时间"]}")
        assertEquals(268.4, properties.getValue("长度（米）").jsonPrimitive.content.toDouble())
    }

    // A layer's own colour and sizes, kept in the project (ADR 0006).
    @Test
    fun layerStylesAreKeptAndCleared() {
        run(Workspace.CREATE, """{"name": "样式", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        fun style(o: JsonObject) = o.getValue("layers").jsonArray.single().jsonObject["style"]?.jsonObject
        assertNull(style(output(run(Workspace.CURRENT))), "no style until one is set")

        val set = output(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"color": "#1E88E5", "size": 8, "fill": 0.4}}"""))
        assertEquals("#1e88e5", style(set)!!.getValue("color").jsonPrimitive.content)
        assertEquals("single", style(set)!!.getValue("kind").jsonPrimitive.content)
        assertIs<Outcome.Failed>(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"color": "blue"}}"""), "not #rrggbb")
        assertEquals(
            "#1e88e580",
            style(output(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"color": "#1E88E580"}}""")))!!.getValue("color").jsonPrimitive.content,
            "with its opacity",
        )
        assertIs<Outcome.Failed>(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"color": "#1E88E5800"}}"""), "nine digits")
        assertIs<Outcome.Failed>(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"size": 99}}"""), "too big")

        val byValue = style(
            output(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"color": "#757575", "field": "点名", "classes": [{"value": "D001", "color": "#E53935"}]}}""")),
        )!!
        assertEquals("categorized", byValue.getValue("kind").jsonPrimitive.content)
        assertEquals("#e53935", byValue.getValue("classes").jsonArray.single().jsonObject.getValue("color").jsonPrimitive.content)
        assertIs<Outcome.Failed>(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"field": "岩性", "classes": []}}"""), "no such field")
        assertIs<Outcome.Failed>(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"field": "点名"}}"""), "a field without classes")
        assertIs<Outcome.Failed>(
            run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"field": "点名", "classes": [{"value": "a", "color": "#000000"}, {"value": "a", "color": "#ffffff"}]}}"""),
            "one class a value",
        )
        assertEquals("描述", style(output(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"label": "描述"}}""")))!!.getValue("label").jsonPrimitive.content)
        assertEquals("", style(output(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"label": ""}}""")))!!.getValue("label").jsonPrimitive.content, "no labels")
        assertIs<Outcome.Failed>(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"label": "岩性"}}"""), "no such field to label")
        run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {"color": "#1E88E5", "size": 8, "fill": 0.4}}""")

        run(Workspace.CLOSE)
        run(Workspace.OPEN, """{"name": "样式"}""")
        assertEquals(8.0, style(output(run(Workspace.CURRENT)))!!.getValue("size").jsonPrimitive.content.toDouble(), "kept in the file")
        val like = output(run(Workspace.LAYER_CREATE, """{"name": "泉点", "like": "野外点"}""")).getValue("layers").jsonArray
            .map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "泉点" }
        assertEquals("#1e88e5", like["style"]?.jsonObject?.getValue("color")?.jsonPrimitive?.content, "a layer made like another takes its style")
        assertNull(output(run(Workspace.LAYER_STYLE, """{"layer": "野外点", "style": {}}""")).getValue("layers").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "野外点" }["style"], "an empty style is the default again")
    }

    @Test
    fun theOpenProjectCanBeAskedFor() {
        assertEquals(JsonObject(emptyMap()), output(run(Workspace.CURRENT)))
        run(Workspace.CREATE, """{"name": "乙", "language": "zh"}""")
        assertEquals("乙", output(run(Workspace.CURRENT)).getValue("name").jsonPrimitive.content)
        assertEquals("empty", (run(Workspace.EXPORT, """{"format": "kml"}""") as Outcome.Failed).code, "no KML of a project without layers")
    }

    @Test
    fun aFailedOpenKeepsTheOpenProject() {
        run(Workspace.CREATE, """{"name": "甲", "language": "zh"}""")
        assertIs<Outcome.Failed>(run(Workspace.OPEN, """{"name": "没有这个"}"""))
        assertEquals("甲", workspace.current?.name)
        assertIs<Outcome.Failed>(run(Workspace.CREATE, """{"name": "坏/名", "language": "zh"}"""))
        assertEquals("甲", workspace.current?.name)
        assertEquals(Outcome.Failed("error", "no project is open"), run(Workspace.CLOSE).let { run(Editing.START, """{"layer": "野外点"}""") })
    }

    @Test
    fun examplesFitTheirParameters() {
        for (action in workspace.actions) {
            for (example in action.spec.examples) assertNull(org.geocrew.core.action.Schema.check(action.spec.params, example), action.spec.id)
        }
    }
}
