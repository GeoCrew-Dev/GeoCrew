package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

// A project keeps a snapshot of each day it is opened (ADR 0004): a GeoPackage of its own, the newest seven.
@RunWith(AndroidJUnit4::class)
class SnapshotsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-snapshots")
    private lateinit var registry: ActionRegistry

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        val workspace = Workspace(Projects(root, File(context.noBackupFilesDir, "journals-snapshots").apply { deleteRecursively() }))
        registry = ActionRegistry().apply { workspace.actions.forEach(::register) }
    }

    private fun run(action: String, params: String = "{}"): Outcome =
        runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }

    private fun reopen(): JsonObject {
        run(Workspace.CLOSE)
        return (run(Workspace.OPEN, """{"name": "测区"}""") as Outcome.Done).output.jsonObject
    }

    @Test
    fun theFirstOpeningOfADayKeepsASnapshot() {
        run(Workspace.CREATE, """{"name": "测区", "language": "zh"}""")
        val folder = File(root, "工程/测区/快照")
        assertFalse(folder.exists(), "a new project has nothing to keep yet")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.ADD, """{"layer": "野外点", "properties": {"点名": "D001"}}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")

        assertNull(reopen()["snapshotFailure"])
        val today = File(folder, "${LocalDate.now()}.gpkg")
        assertTrue(today.isFile, "the day's snapshot")
        VectorDataset.open(today.path).use { ds ->
            val rows = Json.parseToJsonElement(ds.sql("SELECT \"点名\" AS name FROM \"野外点\"")).jsonArray
            assertEquals("D001", rows.single().jsonObject.getValue("properties").jsonObject.getValue("name").jsonPrimitive.content)
            val mode = Json.parseToJsonElement(ds.sql("PRAGMA journal_mode")).jsonArray.single().jsonObject.getValue("properties").jsonObject.values.single()
            assertEquals("delete", mode.jsonPrimitive.content, "a file of its own")
        }
        val made = today.lastModified()
        reopen()
        assertEquals(made, today.lastModified(), "one a day")
        assertEquals(listOf(today.name), folder.list()!!.toList(), "nothing half-written stays")

        // The newest seven stay.
        for (day in 1..8) today.copyTo(File(folder, "2020-01-0$day.gpkg"))
        today.delete()
        reopen()
        assertEquals(
            listOf("2020-01-04", "2020-01-05", "2020-01-06", "2020-01-07", "2020-01-08", LocalDate.now().toString()).map { "$it.gpkg" }.let { listOf("2020-01-03.gpkg") + it },
            folder.list()!!.sorted(),
        )

        // A snapshot that cannot be made does not stop the project opening.
        folder.deleteRecursively()
        folder.writeText("not a folder")
        val opened = reopen()
        assertNotNull(opened["snapshotFailure"])
        assertIs<Outcome.Done>(run(Workspace.CURRENT))
        folder.delete()
    }
}
