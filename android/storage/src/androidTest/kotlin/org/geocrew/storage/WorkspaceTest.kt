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

    @Test
    fun theOpenProjectCanBeAskedFor() {
        assertEquals(JsonObject(emptyMap()), output(run(Workspace.CURRENT)))
        run(Workspace.CREATE, """{"name": "乙", "language": "zh"}""")
        assertEquals("乙", output(run(Workspace.CURRENT)).getValue("name").jsonPrimitive.content)
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
