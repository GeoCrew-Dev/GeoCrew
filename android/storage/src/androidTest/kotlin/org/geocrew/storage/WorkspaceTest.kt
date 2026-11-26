package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        val created = output(run(Workspace.CREATE, """{"name": "测区"}"""))
        assertEquals("地质点", created.getValue("layers").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        run(Editing.START, """{"layer": "地质点"}""")
        run(Editing.ADD, """{"layer": "地质点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点号": "D001"}}""")
        assertEquals(Outcome.Failed("editing", "stop editing 地质点 first"), run(Workspace.CLOSE))
        assertIs<Outcome.Failed>(run(Workspace.OPEN, """{"name": "测区"}"""), "nor can another project open")
        run(Editing.SAVE, """{"layer": "地质点"}""")
        run(Editing.STOP, """{"layer": "地质点"}""")
        assertIs<Outcome.Done>(run(Workspace.CLOSE))
        assertNull(workspace.current)

        val listed = output(run(Workspace.LIST)).getValue("projects").jsonArray.single().jsonObject
        assertEquals("测区", listed.getValue("name").jsonPrimitive.content)
        assertTrue(listed.getValue("modified").jsonPrimitive.content.toLong() > 0)
        assertEquals(1, listed.getValue("layers").jsonPrimitive.content.toInt())
        assertEquals(1L, listed.getValue("features").jsonPrimitive.content.toLong(), "the saved point")
        assertTrue(listed.getValue("bytes").jsonPrimitive.content.toLong() > 0)
        assertTrue(listed.containsKey("created"))
        assertIs<Outcome.Done>(run(Workspace.OPEN, """{"name": "测区"}"""))
        val target = workspace.current!!.target("地质点")
        assertEquals(1, target.store.features().size)
    }

    @Test
    fun layersReadAsGeoJson() {
        run(Workspace.CREATE, """{"name": "图"}""")
        val empty = output(run(Workspace.FEATURES, """{"layer": "地质点"}"""))
        assertEquals("FeatureCollection", empty.getValue("type").jsonPrimitive.content)
        assertEquals(false, empty.getValue("editing").jsonPrimitive.content.toBoolean())
        val dip = empty.getValue("fields").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "倾角" }
        assertEquals("real", dip.getValue("type").jsonPrimitive.content)
        run(Editing.START, """{"layer": "地质点"}""")
        run(Editing.ADD, """{"layer": "地质点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点号": "D001"}}""")
        val edited = output(run(Workspace.FEATURES, """{"layer": "地质点"}"""))
        assertEquals(true, edited.getValue("modified").jsonPrimitive.content.toBoolean())
        val feature = edited.getValue("features").jsonArray.single().jsonObject
        assertEquals(-1, feature.getValue("id").jsonPrimitive.content.toInt(), "an unsaved feature shows with its negative fid")
        assertEquals("D001", feature.getValue("properties").jsonObject.getValue("点号").jsonPrimitive.content)
    }

    @Test
    fun exportsHoldWhatIsSaved() {
        run(Workspace.CREATE, """{"name": "出图"}""")
        run(Editing.START, """{"layer": "地质点"}""")
        run(Editing.ADD, """{"layer": "地质点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点号": "D001", "岩性": "花岗岩"}}""")
        assertEquals("unsaved", (run(Workspace.EXPORT, """{"format": "gpkg"}""") as Outcome.Failed).code)
        run(Editing.SAVE, """{"layer": "地质点"}""")

        val gpkg = File(output(run(Workspace.EXPORT, """{"format": "gpkg"}""")).getValue("path").jsonPrimitive.content)
        assertEquals(File(root, "导出"), gpkg.parentFile)
        VectorDataset.open(gpkg.path).use { ds ->
            assertEquals(1, Json.parseToJsonElement(ds.features("地质点")).jsonArray.size, "the saved point is in the copy")
            assertEquals("delete", Json.parseToJsonElement(ds.sql("PRAGMA journal_mode")).jsonArray.single().jsonObject
                .getValue("properties").jsonObject.getValue("journal_mode").jsonPrimitive.content)
        }

        val kml = File(output(run(Workspace.EXPORT, """{"format": "kml"}""")).getValue("path").jsonPrimitive.content)
        val text = kml.readText()
        assertTrue(text.contains("花岗岩") && text.contains("114.3"), text)
        assertTrue(!text.contains("geocrew_"), "GeoCrew's own tables are not exported")
        assertIs<Outcome.Invalid>(run(Workspace.EXPORT, """{"format": "shp"}"""))
    }

    @Test
    fun theOpenProjectCanBeAskedFor() {
        assertEquals(JsonObject(emptyMap()), output(run(Workspace.CURRENT)))
        run(Workspace.CREATE, """{"name": "乙"}""")
        assertEquals("乙", output(run(Workspace.CURRENT)).getValue("name").jsonPrimitive.content)
    }

    @Test
    fun aFailedOpenKeepsTheOpenProject() {
        run(Workspace.CREATE, """{"name": "甲"}""")
        assertIs<Outcome.Failed>(run(Workspace.OPEN, """{"name": "没有这个"}"""))
        assertEquals("甲", workspace.current?.name)
        assertIs<Outcome.Failed>(run(Workspace.CREATE, """{"name": "坏/名"}"""))
        assertEquals("甲", workspace.current?.name)
        assertEquals(Outcome.Failed("error", "no project is open"), run(Workspace.CLOSE).let { run(Editing.START, """{"layer": "地质点"}""") })
    }

    @Test
    fun examplesFitTheirParameters() {
        for (action in workspace.actions) {
            for (example in action.spec.examples) assertNull(org.geocrew.core.action.Schema.check(action.spec.params, example), action.spec.id)
        }
    }
}
