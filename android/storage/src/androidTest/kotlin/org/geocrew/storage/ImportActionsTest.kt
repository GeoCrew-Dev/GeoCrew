package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
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
import org.geocrew.nativelib.GeoCrewNative
import org.geocrew.nativelib.VectorDataset
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Vector files brought into a project: probed in their own process, then copied in as project layers.
@RunWith(AndroidJUnit4::class)
class ImportActionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-import")
    private val journals = File(context.noBackupFilesDir, "journals-import")
    private val outside = File(context.filesDir, "import-outside")
    private lateinit var registry: ActionRegistry

    @Before
    fun setUp() {
        NativeData.setup(context)
        for (dir in listOf(root, journals, outside)) dir.deleteRecursively()
        root.mkdirs()
        outside.mkdirs()
        val workspace = Workspace(Projects(root, journals))
        registry = ActionRegistry().apply {
            workspace.actions.forEach(::register)
            ImportActions(workspace, root) { Probe.inspect(context, it.path) }.actions.forEach(::register)
        }
        run(Workspace.CREATE, """{"name": "导入", "language": "zh"}""")
    }

    private fun run(action: String, params: String = "{}"): Outcome =
        runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }

    private fun json(file: File) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(file.path))

    private fun layers(o: Outcome): Map<String, JsonObject> =
        (o as Outcome.Done).output.jsonObject.getValue("layers").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject }

    private fun count(layer: String) =
        (run(Workspace.FEATURES, """{"layer": "$layer"}""") as Outcome.Done).output.jsonObject.getValue("features").jsonArray.size

    private fun failure(o: Outcome) = (o as Outcome.Failed).code

    @Test
    fun aGeoJsonBecomesALayerNamedAfterIt() {
        val file = File(outside, "点位.geojson").apply {
            writeText(
                """{"type": "FeatureCollection", "features": [
                    {"type": "Feature", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"名称": "A1", "高程": 23.5}},
                    {"type": "Feature", "geometry": {"type": "Point", "coordinates": [114.31, 30.61]}, "properties": {"名称": "A2", "高程": 31}}]}""",
            )
        }
        val made = layers(run(ImportActions.IMPORT, """{"path": ${json(file)}}""")).getValue("点位")
        assertEquals("POINT", made.getValue("geometry").jsonPrimitive.content)
        assertEquals(2, count("点位"))
        val first = (run(Workspace.FEATURES, """{"layer": "点位"}""") as Outcome.Done).output.jsonObject.getValue("features").jsonArray[0].jsonObject
        assertEquals("A1", first.getValue("properties").jsonObject.getValue("名称").jsonPrimitive.content, "fields and values come along")
    }

    @Test
    fun theLayersAskedForAreImportedAndNamesTakenGetANumber() {
        val gpkg = File(outside, "资料.gpkg")
        VectorDataset.create(gpkg.path, "GPKG").use { ds ->
            ds.createLayer("钻孔", "POINT", "EPSG:4490", """[{"name": "孔号", "type": "string"}]""")
            ds.add("钻孔", """{"geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"孔号": "ZK1"}}""")
            ds.createLayer("剖面", "LINESTRING", "EPSG:4547", """[{"name": "名称", "type": "string"}]""")
            ds.add("剖面", """{"geometry": {"type": "LineString", "coordinates": [[528000, 3386000], [529000, 3387000]]}, "properties": {"名称": "P1"}}""")
        }
        val onlyLines = layers(run(ImportActions.IMPORT, """{"path": ${json(gpkg)}, "layers": ["剖面"]}"""))
        assertEquals(setOf("剖面"), onlyLines.keys)
        assertEquals("EPSG:4547", onlyLines.getValue("剖面").getValue("srs").jsonPrimitive.content, "the CRS comes along")
        val all = layers(run(ImportActions.IMPORT, """{"path": ${json(gpkg)}}"""))
        assertEquals(setOf("剖面", "钻孔", "剖面 2"), all.keys)
        assertEquals(1, count("钻孔"))
        assertEquals(ImportActions.NOT_FOUND, failure(run(ImportActions.IMPORT, """{"path": ${json(gpkg)}, "layers": ["无"]}""")))
    }

    @Test
    fun aZippedShapefileIsReadFromInside() {
        val folder = File(outside, "shp").apply { mkdirs() }
        VectorDataset.create(File(folder, "界线.shp").path, "ESRI Shapefile").use { ds ->
            ds.createLayer("界线", "LINESTRING", "EPSG:4326", """[{"name": "name", "type": "string"}]""")
            ds.add("界线", """{"geometry": {"type": "LineString", "coordinates": [[114.3, 30.6], [114.4, 30.7]]}, "properties": {"name": "F1"}}""")
            // A Shapefile line layer holds multi-part lines too.
            ds.add("界线", """{"geometry": {"type": "MultiLineString", "coordinates": [[[114.3, 30.5], [114.4, 30.5]], [[114.5, 30.5], [114.6, 30.6]]]}, "properties": {"name": "F2"}}""")
        }
        val zip = File(outside, "地质界线.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (part in folder.listFiles().orEmpty()) {
                out.putNextEntry(ZipEntry(part.name))
                part.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val made = layers(run(ImportActions.IMPORT, """{"path": ${json(zip)}}"""))
        assertEquals("MULTILINESTRING", made.getValue("地质界线").getValue("geometry").jsonPrimitive.content, "multi-part, as some lines are")
        assertEquals(2, count("地质界线"))
    }

    @Test
    fun whatIsNotVectorDataOrHasNoCrsIsRefused() {
        val tif = File(outside, "影像.tif")
        GeoCrewNative.makeTestRaster(tif.path)
        assertEquals(ImportActions.NOT_VECTOR, failure(run(ImportActions.IMPORT, """{"path": ${json(tif)}}""")))
        assertEquals(ImportActions.UNREADABLE, failure(run(ImportActions.IMPORT, """{"path": ${json(File(outside, "x.txt").apply { writeText("x") })}}""")))
        val folder = File(outside, "noprj").apply { mkdirs() }
        VectorDataset.create(File(folder, "无坐标.shp").path, "ESRI Shapefile").use { ds ->
            ds.createLayer("无坐标", "POINT", null, "[]")
            ds.add("无坐标", """{"geometry": {"type": "Point", "coordinates": [1, 2]}, "properties": {}}""")
        }
        assertEquals(ImportActions.NO_CRS, failure(run(ImportActions.IMPORT, """{"path": ${json(File(folder, "无坐标.shp"))}}""")))
        assertEquals(emptySet(), layers(run(Workspace.CURRENT)).keys, "nothing left behind")
    }
}
