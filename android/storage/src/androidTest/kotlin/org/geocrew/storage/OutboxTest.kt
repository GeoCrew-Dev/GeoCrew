package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// A linked project keeps what it has to send its workspace, field by field, with each edit's time (ADR 0008, protocol v1 8.4).
@RunWith(AndroidJUnit4::class)
class OutboxTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-outbox")
    private lateinit var registry: ActionRegistry
    private lateinit var workspace: Workspace

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        workspace = Workspace(Projects(root, File(context.noBackupFilesDir, "journals-outbox").apply { deleteRecursively() }))
        registry = ActionRegistry().apply { workspace.actions.forEach(::register) }
    }

    private fun run(action: String, params: String = "{}"): Outcome =
        runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }

    private fun output(outcome: Outcome): JsonObject = (outcome as Outcome.Done).output.jsonObject

    // Adds point [name] and saves it; its fid in the file.
    private fun addSaved(name: String): Long {
        val added = output(run(Editing.ADD, """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"点名": "$name"}}"""))
        val saved = output(run(Editing.SAVE, """{"layer": "野外点"}"""))
        return saved.getValue("fids").jsonObject.getValue(added.getValue("fid").jsonPrimitive.content).jsonPrimitive.long
    }

    private fun change(fid: Long, values: String) {
        run(Editing.VALUES, """{"layer": "野外点", "fid": $fid, "values": $values}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")
    }

    @Test
    fun aLinkedProjectKeepsWhatItHasToSend() {
        run(Workspace.CREATE, """{"name": "协同", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Editing.START, """{"layer": "野外点"}""")
        val project = workspace.current!!
        val layerId = project.layers.single().id
        val names = project.fieldIds("野外点").entries.associate { (name, id) -> id to name } + mapOf("geom" to "geom", "deleted" to "deleted")
        // What is to be sent of row [row], by field name.
        fun sent(row: String) = project.outbox.filter { it["row"] == row }.associate { names.getValue(it.getValue("field")!!) to it.getValue("value") }

        // Not linked: nothing is kept.
        val early = addSaved("D001")
        val unsent = addSaved("D000")
        assertTrue(project.outbox.isEmpty())
        assertNull(project.rowId(layerId, early))

        project.link("ws-1")
        val before = Instant.now()
        val fid = addSaved("D002")
        val row = assertNotNull(project.rowId(layerId, fid), "a new feature gets a row id")
        // A new feature goes whole: every field, given or not, and its geometry.
        assertEquals(mapOf("点名" to "\"D002\"", "描述" to "null", "geom" to """{"type":"Point","coordinates":[114.3,30.6]}"""), sent(row))
        val edited = Instant.parse(project.outbox.first { it["row"] == row }["edited_at"])
        assertTrue(!edited.isBefore(before.minusSeconds(1)) && !edited.isAfter(Instant.now()), "when it was edited: $edited")

        // A change sends what changed, its last value.
        change(fid, """{"描述": "花岗岩"}""")
        change(fid, """{"描述": "片麻岩"}""")
        assertEquals(mapOf("点名" to "\"D002\"", "描述" to "\"片麻岩\"", "geom" to """{"type":"Point","coordinates":[114.3,30.6]}"""), sent(row))

        // A feature saved before the project was linked goes whole when it changes.
        change(early, """{"描述": "砂岩"}""")
        val earlyRow = assertNotNull(project.rowId(layerId, early))
        assertEquals(setOf("点名", "描述", "geom"), sent(earlyRow).keys)
        assertEquals("\"D001\"", sent(earlyRow)["点名"])

        // Deleting one the workspace never had tells it nothing; one it has, a deletion.
        val count = project.outbox.size
        run(Editing.DELETE, """{"layer": "野外点", "fid": $unsent}""")
        run(Editing.DELETE, """{"layer": "野外点", "fid": $fid}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")
        assertNull(project.rowId(layerId, unsent))
        assertEquals(count + 1, project.outbox.size)
        assertEquals("true", sent(row)["deleted"])
    }
}
