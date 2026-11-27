package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.nativelib.GeoCrewNative
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Rasters under a project's layers: probed in their own process first, then kept by path in the project.
@RunWith(AndroidJUnit4::class)
class RasterActionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-rasters")
    private val journals = File(context.noBackupFilesDir, "journals-rasters")
    private val outside = File(context.filesDir, "rasters-outside")
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
            RasterActions(workspace, root) { Probe.inspect(context, it.path) }.actions.forEach(::register)
        }
    }

    private fun run(action: String, params: String = "{}", caller: Caller = Caller.USER): Outcome =
        runBlocking { registry.invoke(caller, action, Json.parseToJsonElement(params).jsonObject) }

    private fun rasters(o: Outcome): List<JsonObject> = (o as Outcome.Done).output.jsonObject.getValue("rasters").jsonArray.map { it.jsonObject }

    private fun json(file: File) = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(file.path))

    @Test
    fun rastersAreAddedShownAndTakenOut() {
        val tif = File(outside, "影像.tif")
        GeoCrewNative.makeTestRaster(tif.path)
        assertEquals(RasterActions.NO_PROJECT, (run(RasterActions.ADD, """{"path": ${json(tif)}}""") as Outcome.Failed).code)
        run(Workspace.CREATE, """{"name": "栅格", "language": "zh"}""")

        val added = rasters(run(RasterActions.ADD, """{"path": ${json(tif)}}""")).single()
        assertEquals("影像", added.getValue("name").jsonPrimitive.content, "named after the file")
        assertEquals(tif.canonicalPath, added.getValue("path").jsonPrimitive.content)
        assertEquals("raster", added.getValue("info").jsonObject.getValue("kind").jsonPrimitive.content)
        val again = rasters(run(RasterActions.ADD, """{"path": ${json(tif)}}"""))
        assertEquals(listOf("影像 2", "影像"), again.map { it.getValue("name").jsonPrimitive.content }, "numbered, newest on top")

        val shown = rasters(run(RasterActions.DISPLAY, """{"name": "影像", "visible": false, "opacity": 0.5}""")).single { it.getValue("name").jsonPrimitive.content == "影像" }
        assertEquals(listOf("false", "0.5"), listOf("visible", "opacity").map { shown.getValue(it).jsonPrimitive.content })
        assertEquals("default", shown.getValue("render").jsonPrimitive.content, "drawn as it is until told otherwise")
        val shaded = rasters(run(RasterActions.DISPLAY, """{"name": "影像", "render": "hillshade"}""")).single { it.getValue("name").jsonPrimitive.content == "影像" }
        assertEquals("hillshade", shaded.getValue("render").jsonPrimitive.content)
        assertEquals("0.5", shaded.getValue("opacity").jsonPrimitive.content, "the rest of the display stays")
        assertIs<Outcome.Invalid>(run(RasterActions.DISPLAY, """{"name": "影像", "render": "contours"}"""))

        // Kept in the project: open it again and they are there; taken out, the file stays.
        run(Workspace.CLOSE)
        assertEquals(2, rasters(run(Workspace.OPEN, """{"name": "栅格"}""")).size)
        assertEquals(listOf("影像"), rasters(run(RasterActions.REMOVE, """{"name": "影像 2"}""")).map { it.getValue("name").jsonPrimitive.content })
        assertTrue(tif.exists())
    }

    @Test
    fun onlyRastersThatKnowWhereTheyAreGoIn() {
        run(Workspace.CREATE, """{"name": "栅格", "language": "zh"}""")
        val points = File(outside, "点.geojson").apply {
            writeText("""{"type": "FeatureCollection", "features": [{"type": "Feature", "geometry": {"type": "Point", "coordinates": [114, 30]}, "properties": {}}]}""")
        }
        assertEquals(RasterActions.NOT_RASTER, (run(RasterActions.ADD, """{"path": ${json(points)}}""") as Outcome.Failed).code)
        val broken = File(outside, "坏的.tif").apply { writeText("not a raster") }
        assertEquals(RasterActions.UNREADABLE, (run(RasterActions.ADD, """{"path": ${json(broken)}}""") as Outcome.Failed).code)
        assertEquals(RasterActions.NOT_FOUND, (run(RasterActions.ADD, """{"path": ${json(File(outside, "无.tif"))}}""") as Outcome.Failed).code)

        // The AI adds only files in the GeoCrew folder.
        val tif = File(outside, "影像.tif")
        GeoCrewNative.makeTestRaster(tif.path)
        assertEquals(RasterActions.OUTSIDE, (run(RasterActions.ADD, """{"path": ${json(tif)}}""", Caller.AI) as Outcome.Failed).code)
        val inside = tif.copyTo(File(root, "影像.tif"))
        assertIs<Outcome.Done>(run(RasterActions.ADD, """{"path": ${json(inside)}}""", Caller.AI))
    }
}
