package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.core.sync.Reply
import org.geocrew.core.sync.SyncClient
import org.geocrew.core.sync.Transport
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Joining a team's workspace, and the workspace's changes coming into the project (protocol v1 3.4, 8.2, 8.4).
@RunWith(AndroidJUnit4::class)
class SyncPullTest {
    // A server whose pulls give the pages set for each since; pushes it takes, keeping them.
    private class FakeServer : Transport {
        val pages = mutableMapOf<Long, String>()
        val pushes = mutableListOf<JsonObject>()

        override fun send(method: String, path: String, token: String?, body: String?): Reply = when {
            path == "/api/v1/info" -> Reply(200, """{"protocol": 1, "capabilities": ["sync"], "limits": {"max_push_batch": 100, "max_pull_batch": 100, "max_file_size": 1}}""")
            path == "/api/v1/join" -> Reply(200, """{"workspace": {"id": "w2", "name": "野外组", "leader": "m1"}, "member": {"id": "m2"}, "token": "t2"}""")
            path.contains("/changes?since=") -> {
                val since = path.substringAfter("since=").substringBefore("&").toLong()
                Reply(200, pages[since] ?: """{"layers": [], "rows": [], "conflicts": [], "seq": $since, "more": false}""")
            }
            path.endsWith("/rows") -> {
                val changes = Json.parseToJsonElement(body!!).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
                pushes += changes
                val results = buildJsonArray {
                    for (c in changes) add(buildJsonObject { put("id", c.getValue("id")); put("status", "applied"); put("seq", 100); put("conflicts", JsonArray(emptyList())) })
                }
                Reply(200, buildJsonObject { put("results", results); put("seq", 100) }.toString())
            }
            else -> Reply(404, """{"error": "not_found", "message": "$path"}""")
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-pull")
    private val server = FakeServer()
    private lateinit var registry: ActionRegistry
    private lateinit var workspace: Workspace

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        workspace = Workspace(Projects(root, File(context.noBackupFilesDir, "journals-pull").apply { deleteRecursively() }))
        val accounts = File(context.noBackupFilesDir, "sync-pull").apply { deleteRecursively() }
        registry = ActionRegistry().apply {
            workspace.actions.forEach(::register)
            SyncActions(workspace, accounts) { _, _ -> server }.actions.forEach(::register)
        }
    }

    private fun run(action: String, params: String = "{}"): Outcome =
        runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }

    private fun output(outcome: Outcome): JsonObject {
        assertTrue(outcome is Outcome.Done, outcome.toString())
        return outcome.output.jsonObject
    }

    private fun field(value: String, seq: Long) = """{"value": "$value", "by": "m1", "at": "2026-11-28T10:00:00Z", "seq": $seq}"""

    private fun point(x: Double, seq: Long) = """{"value": {"type": "Point", "coordinates": [$x, 30.6]}, "by": "m1", "at": "2026-11-28T10:00:00Z", "seq": $seq}"""

    // The feature that row [row] is, by its 点名 and 描述 and longitude.
    private fun feature(row: String): Triple<String?, String?, Double>? {
        val project = workspace.current!!
        val layer = project.layers.single()
        val fid = project.data { Outbox.fidOf(it, layer.id, row) } ?: return null
        val f = project.target(layer.name).store.feature(fid) ?: return null
        val x = f.geometry!!.getValue("coordinates").jsonArray[0].jsonPrimitive.double
        return Triple(f.properties["点名"]?.jsonPrimitive?.contentOrNull, f.properties["描述"]?.jsonPrimitive?.contentOrNull, x)
    }

    @Test
    fun aMemberJoinsAndTheWorkspaceComesIn() {
        val layer = """{"id": "L1", "geometry_type": "Point", "crs": "EPSG:4490", "schema_version": 1, "seq": 1,
            "name": ${field("野外点", 1)}, "deleted": null,
            "fields": [{"id": "f1", "name": "点名", "type": "string"}, {"id": "f2", "name": "描述", "type": "string"}]}"""
        server.pages[0] = """{"layers": [$layer], "rows": [{"layer": "L1", "id": "r1", "conflicted": false, "deleted": null,
            "fields": {"f1": ${field("D001", 2)}, "geom": ${point(114.3, 2)}}}], "conflicts": [], "seq": 5, "more": true}"""
        server.pages[5] = """{"layers": [], "rows": [{"layer": "L1", "id": "r2", "conflicted": false, "deleted": null,
            "fields": {"f1": ${field("D002", 6)}, "f2": ${field("片麻岩", 6)}, "geom": ${point(114.4, 6)}}}],
            "conflicts": [{"id": "k1", "layer": "L1", "row": "r1", "seq": 7, "resolved": false, "kind": "value", "field": "f1",
              "kept": ${field("D001", 2)}, "lost": ${field("D009", 7)}}], "seq": 8, "more": false}"""

        val joined = output(run(SyncActions.JOIN, """{"server": "http://fake", "invite_code": "K7QX2MPA", "nickname": "李四", "device": "test", "language": "zh"}"""))
        assertEquals(2, joined.getValue("pulled").jsonPrimitive.int)
        val project = workspace.current!!
        assertEquals("野外组", project.name, "a project named after the workspace")
        val made = project.layers.single()
        assertEquals("L1" to "野外点", made.id to made.name)
        assertEquals(mapOf("点名" to "f1", "描述" to "f2"), project.fieldIds("野外点"))
        assertEquals(Triple("D001", null, 114.3), feature("r1"))
        assertEquals(Triple("D002", "片麻岩", 114.4), feature("r2"))
        val conflict = project.data { rows(it, "SELECT row, kind, lost FROM geocrew_conflicts WHERE id = 'k1'") }.single()
        assertEquals("r1" to "value", conflict["row"] to conflict["kind"])
        assertTrue(conflict["lost"]!!.contains("D009"))
        assertEquals(true, output(run(SyncActions.STATUS)).getValue("linked").jsonPrimitive.content.toBoolean())

        // Others' changes come in: a value and a move, and a deletion.
        server.pages[8] = """{"layers": [], "rows": [
            {"layer": "L1", "id": "r1", "conflicted": false, "deleted": null, "fields": {"f1": ${field("D001-改", 9)}, "geom": ${point(114.31, 9)}}},
            {"layer": "L1", "id": "r2", "conflicted": false, "deleted": {"value": true, "by": "m1", "at": "2026-11-28T10:00:00Z", "seq": 9}, "fields": {"f1": ${field("D002", 6)}}}],
            "conflicts": [], "seq": 9, "more": false}"""
        val synced = output(run(SyncActions.NOW))
        assertEquals(2, synced.getValue("pulled").jsonPrimitive.int)
        assertEquals(Triple("D001-改", null, 114.31), feature("r1"))
        assertNull(feature("r2"), "deleted there, deleted here")
        assertTrue(server.pushes.isEmpty(), "what came in is not sent back")

        // A field this phone has yet to send keeps its value; the others come in.
        run(Editing.START, """{"layer": "野外点"}""")
        val fid = project.data { Outbox.fidOf(it, "L1", "r1") }!!
        run(Editing.VALUES, """{"layer": "野外点", "fid": $fid, "values": {"描述": "本机"}}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")
        server.pages[9] = """{"layers": [], "rows": [
            {"layer": "L1", "id": "r1", "conflicted": false, "deleted": null, "fields": {"f1": ${field("D001-再改", 10)}, "f2": ${field("远端", 10)}}}],
            "conflicts": [], "seq": 10, "more": false}"""
        val account = SyncAccount("http://fake", "w2", "m2", "t2")
        ProjectSync(project, SyncClient(server), account).pull(100)
        assertEquals(Triple("D001-再改", "本机", 114.31), feature("r1"))

        // Its change goes from the seq it last saw the row at.
        run(SyncActions.NOW)
        val change = server.pushes.single()
        assertEquals("r1" to 10L, change.getValue("row").jsonPrimitive.content to change.getValue("base_seq").jsonPrimitive.long)
        assertEquals(setOf("f2"), change.getValue("set").jsonObject.keys)
    }

    @Test
    fun joiningWaitsForUnsavedEdits() {
        run(Workspace.CREATE, """{"name": "本地", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.ADD, """{"layer": "野外点", "properties": {"点名": "D001"}}""")
        val refused = run(SyncActions.JOIN, """{"server": "http://fake", "invite_code": "K7QX2MPA", "nickname": "李四", "device": "test"}""")
        assertEquals(SyncActions.UNSAVED, (refused as Outcome.Failed).code)
        assertEquals("本地", workspace.current!!.name, "the open project stays")
    }
}
