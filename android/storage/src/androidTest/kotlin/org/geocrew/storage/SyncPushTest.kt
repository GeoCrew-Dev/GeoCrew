package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
import org.geocrew.core.sync.Transport
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// A project shared with a team's workspace, and its saved edits sent there, change by change (protocol v1 5.6, 8.1, 8.4).
@RunWith(AndroidJUnit4::class)
class SyncPushTest {
    // A server as far as sharing and pushing go: it keeps what it was sent, and can fail, or refuse a row.
    private class FakeServer : Transport {
        val layers = mutableListOf<JsonObject>()
        val pushes = mutableListOf<List<JsonObject>>()
        val applied = mutableSetOf<String>()
        var seq = 0L
        var loseNextAnswer = false
        var refuse: String? = null

        override fun send(method: String, path: String, token: String?, body: String?): Reply {
            val o = body?.let { Json.parseToJsonElement(it).jsonObject }
            return when {
                path == "/api/v1/info" -> Reply(200, """{"protocol": 1, "capabilities": ["sync", "create"], "limits": {"max_push_batch": 2, "max_pull_batch": 1000, "max_file_size": 1}}""")
                path == "/api/v1/workspaces" -> Reply(
                    201,
                    """{"workspace": {"id": "w1", "name": "${o!!.getValue("name").jsonPrimitive.content}", "leader": "m1", "invite_code": "K7QX2MPA"}, "member": {"id": "m1"}, "token": "t1"}""",
                )
                path.endsWith("/layers") -> {
                    assertEquals("t1", token)
                    layers += o!!
                    Reply(200, buildJsonObject { put("id", o.getValue("id")); put("geometry_type", o.getValue("geometry_type")); put("crs", o.getValue("crs")); put("fields", o.getValue("fields")); put("schema_version", 1); put("seq", ++seq) }.toString())
                }
                path.endsWith("/rows") -> {
                    val changes = o!!.getValue("changes").jsonArray.map { it.jsonObject }
                    pushes += changes
                    val results = buildJsonArray {
                        for (c in changes) {
                            val id = c.getValue("id").jsonPrimitive.content
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    when {
                                        c.getValue("row").jsonPrimitive.content == refuse -> {
                                            put("status", "rejected")
                                            put("error", "forbidden")
                                        }
                                        !applied.add(id) -> put("status", "duplicate")
                                        else -> {
                                            put("status", "applied")
                                            put("seq", ++seq)
                                            put("conflicts", JsonArray(emptyList()))
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (loseNextAnswer) {
                        loseNextAnswer = false
                        throw IOException("the answer was lost") // applied, but the phone never heard
                    }
                    Reply(200, buildJsonObject { put("results", results); put("seq", seq) }.toString())
                }
                else -> Reply(404, """{"error": "not_found", "message": "$path"}""")
            }
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-sync")
    private val server = FakeServer()
    private lateinit var registry: ActionRegistry
    private lateinit var workspace: Workspace

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        workspace = Workspace(Projects(root, File(context.noBackupFilesDir, "journals-sync").apply { deleteRecursively() }))
        val accounts = File(context.noBackupFilesDir, "sync-test").apply { deleteRecursively() }
        registry = ActionRegistry().apply {
            workspace.actions.forEach(::register)
            SyncActions(workspace, accounts) { server }.actions.forEach(::register)
        }
    }

    private fun run(action: String, params: String = "{}"): Outcome =
        runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }

    private fun output(outcome: Outcome): JsonObject {
        assertTrue(outcome is Outcome.Done, outcome.toString())
        return outcome.output.jsonObject
    }

    private fun add(name: String, x: Double) {
        run(Editing.ADD, """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [$x, 30.6]}, "properties": {"点名": "$name"}}""")
    }

    @Test
    fun aProjectIsSharedAndItsEditsSent() {
        run(Workspace.CREATE, """{"name": "协同", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Editing.START, """{"layer": "野外点"}""")
        add("D001", 114.3)
        add("D002", 114.4)
        add("D003", 114.5)
        run(Editing.SAVE, """{"layer": "野外点"}""")
        assertEquals(false, output(run(SyncActions.STATUS)).getValue("linked").jsonPrimitive.content.toBoolean())

        val shared = output(run(SyncActions.SHARE, """{"server": "http://fake", "nickname": "组长", "device": "test"}"""))
        assertEquals("K7QX2MPA", shared.getValue("invite_code").jsonPrimitive.content)
        assertEquals(3, shared.getValue("sent").jsonPrimitive.int)
        // The layer goes with its own id, its kind and fields by the ids the project keeps.
        val project = workspace.current!!
        val layer = server.layers.single()
        assertEquals(project.layers.single().id, layer.getValue("id").jsonPrimitive.content)
        assertEquals("Point", layer.getValue("geometry_type").jsonPrimitive.content)
        val ids = project.fieldIds("野外点")
        assertEquals(ids.values.toSet(), layer.getValue("fields").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        // Every feature as a new row, two a request as the server allows.
        assertEquals(listOf(2, 1), server.pushes.map { it.size })
        val first = server.pushes.first().first()
        assertEquals(0L, first.getValue("base_seq").jsonPrimitive.long)
        val set = first.getValue("set").jsonObject
        assertTrue("geom" in set && ids.getValue("点名") in set, set.toString())
        assertEquals(0, output(run(SyncActions.STATUS)).getValue("waiting").jsonPrimitive.int)

        // A saved change goes as only what changed.
        run(Editing.VALUES, """{"layer": "野外点", "fid": 1, "values": {"描述": "花岗岩"}}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")
        assertEquals(1, output(run(SyncActions.NOW)).getValue("sent").jsonPrimitive.int)
        val change = server.pushes.last().single().getValue("set").jsonObject
        assertEquals(setOf(ids.getValue("描述")), change.keys)

        // An answer lost on the way: the same change goes again, and the server knows it.
        run(Editing.VALUES, """{"layer": "野外点", "fid": 2, "values": {"描述": "片麻岩"}}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")
        server.loseNextAnswer = true
        assertEquals(SyncActions.OFFLINE, (run(SyncActions.NOW) as Outcome.Failed).code)
        assertEquals(1, output(run(SyncActions.STATUS)).getValue("waiting").jsonPrimitive.int)
        val lost = server.pushes.last().single().getValue("id").jsonPrimitive.content
        run(SyncActions.NOW)
        assertEquals(lost, server.pushes.last().single().getValue("id").jsonPrimitive.content, "the same change id")
        assertEquals(0, output(run(SyncActions.STATUS)).getValue("waiting").jsonPrimitive.int)

        // A refused change stays on the phone and is not sent again.
        server.refuse = project.rowId(project.layers.single().id, 3)
        run(Editing.DELETE, """{"layer": "野外点", "fid": 3}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")
        assertEquals(1, output(run(SyncActions.NOW)).getValue("rejected").jsonPrimitive.int)
        val pushesSoFar = server.pushes.size
        run(SyncActions.NOW)
        assertEquals(pushesSoFar, server.pushes.size, "nothing more to send")
        assertEquals(1, output(run(SyncActions.STATUS)).getValue("rejected").jsonPrimitive.int)
        assertEquals(SyncActions.LINKED, (run(SyncActions.SHARE, """{"server": "http://fake", "nickname": "组长", "device": "test"}""") as Outcome.Failed).code)
    }
}
