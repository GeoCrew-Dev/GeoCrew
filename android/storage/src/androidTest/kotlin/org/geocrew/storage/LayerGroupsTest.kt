package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Groups in the layer list (ADR 0017): layers drawn together where the group is, hidden with it, kept in the project.
@RunWith(AndroidJUnit4::class)
class LayerGroupsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-groups")
    private lateinit var registry: ActionRegistry

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        val workspace = Workspace(Projects(root, File(context.noBackupFilesDir, "journals-groups").apply { deleteRecursively() }))
        registry = ActionRegistry().apply { workspace.actions.forEach(::register) }
    }

    private fun run(action: String, params: String = "{}"): Outcome =
        runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }

    private fun output(outcome: Outcome): JsonObject = (outcome as Outcome.Done).output.jsonObject

    private fun names(about: JsonObject) = about.getValue("layers").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

    private fun tree(about: JsonObject) = about.getValue("tree").jsonArray.map { node ->
        node.jsonObject["layer"]?.jsonPrimitive?.content ?: node.jsonObject.getValue("layers").jsonArray.joinToString("+", "[", "]") { it.jsonPrimitive.content }
    }

    @Test
    fun aGroupKeepsItsLayersTogether() {
        run(Workspace.CREATE, """{"name": "分组", "language": "zh"}""")
        for (name in listOf("甲", "乙", "丙")) run(Workspace.LAYER_CREATE, """{"name": "$name", "language": "zh"}""")
        assertEquals(listOf("甲", "乙", "丙"), names(output(run(Workspace.CURRENT))), "made order")

        var about = output(run(Workspace.GROUP_CREATE, """{"name": "水文", "layers": ["丙", "甲"]}"""))
        val group = about.getValue("groups").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(listOf("乙", "[丙+甲]"), tree(about), "where its first layer was")
        assertEquals(listOf("乙", "丙", "甲"), names(about), "drawn together")
        val layer = { about: JsonObject, name: String -> about.getValue("layers").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == name } }
        assertEquals(group, layer(about, "丙").getValue("group").jsonPrimitive.content)

        // Hidden with its group; each layer keeps its own switch.
        about = output(run(Workspace.GROUP_DISPLAY, """{"group": "$group", "visible": false}"""))
        val first = layer(about, "丙")
        assertEquals("false", first.getValue("visible").jsonPrimitive.content)
        assertEquals("true", first.getValue("ownVisible").jsonPrimitive.content)

        // Out of the group to the bottom, the group below the other layer.
        run(Workspace.LAYER_MOVE, """{"layer": "甲", "index": 9}""")
        about = output(run(Workspace.LAYER_MOVE, """{"group": "$group", "index": 1}"""))
        assertEquals(listOf("乙", "[丙]", "甲"), tree(about))

        // Kept in the project.
        run(Workspace.CLOSE)
        about = output(run(Workspace.OPEN, """{"name": "分组"}"""))
        assertEquals(listOf("乙", "[丙]", "甲"), tree(about))
        assertEquals("水文", about.getValue("groups").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)

        // A new layer on top; the order of all layers keeps each in its group.
        run(Workspace.LAYER_CREATE, """{"name": "丁", "language": "zh"}""")
        assertEquals(listOf("丁", "乙", "[丙]", "甲"), tree(output(run(Workspace.CURRENT))))
        about = output(run(Workspace.LAYER_ORDER, """{"layers": ["甲", "丙", "乙", "丁"]}"""))
        assertEquals(listOf("甲", "[丙]", "乙", "丁"), tree(about))

        assertIs<Outcome.Failed>(run(Workspace.GROUP_CREATE, """{"name": "水文"}"""), "names are not taken twice")
        assertIs<Outcome.Failed>(run(Workspace.LAYER_MOVE, """{"layer": "甲", "into": "nowhere", "index": 0}"""))

        // Taken away: its layer stays where it was.
        run(Workspace.GROUP_RENAME, """{"group": "$group", "to": "水文地质"}""")
        about = output(run(Workspace.GROUP_REMOVE, """{"group": "$group"}"""))
        assertEquals(listOf("甲", "丙", "乙", "丁"), tree(about))
        assertEquals(0, about.getValue("groups").jsonArray.size)
    }

    // Any vector layer takes points, lines and areas (ADR 0017): each kind a table of its own, made when first used.
    @Test
    fun aVectorLayerGetsItsPartsWhenUsed() {
        run(Workspace.CREATE, """{"name": "部分", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "泉点", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "断层", "language": "zh", "template": "field-line"}""")
        run(Workspace.LAYER_ORDER, """{"layers": ["泉点", "断层"]}""")

        var made = output(run(Workspace.LAYER_PART, """{"layer": "断层", "geometry": "POINT", "language": "zh", "color": "#e53935"}"""))
        assertEquals("断层 点", made.getValue("part").jsonPrimitive.content)
        val group = made.getValue("groups").jsonArray.single().jsonObject
        assertEquals("断层", group.getValue("name").jsonPrimitive.content)
        assertEquals("true", group.getValue("vector").jsonPrimitive.content)
        assertEquals(listOf("泉点", "[断层+断层 点]"), tree(made), "the group where the layer was")
        val part = made.getValue("layers").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "断层 点" }
        assertEquals("POINT", part.getValue("geometry").jsonPrimitive.content)
        assertEquals("#e53935", part.getValue("style").jsonObject.getValue("color").jsonPrimitive.content)
        val fields = { layer: String ->
            output(run(Workspace.LAYER_INFO, """{"layer": "$layer"}""")).getValue("fields").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        }
        assertEquals(fields("断层"), fields("断层 点"), "the same fields")

        // From any part, into the same vector layer; a kind it has, or its own, is refused.
        assertIs<Outcome.Failed>(run(Workspace.LAYER_PART, """{"layer": "断层", "geometry": "POINT", "language": "zh"}"""))
        assertIs<Outcome.Failed>(run(Workspace.LAYER_PART, """{"layer": "泉点", "geometry": "POINT", "language": "zh"}"""))
        made = output(run(Workspace.LAYER_PART, """{"layer": "断层 点", "geometry": "POLYGON", "language": "zh"}"""))
        assertEquals("断层 面", made.getValue("part").jsonPrimitive.content)
        assertEquals(listOf("泉点", "[断层+断层 点+断层 面]"), tree(made))
    }
}
