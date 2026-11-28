package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
        val resolved = mutableListOf<Pair<String, String>>()
        val files = mutableMapOf<String, ByteArray>()
        val fetched = mutableListOf<String>()

        override fun fetch(path: String, token: String?, from: Long, open: (whole: Boolean) -> OutputStream): Reply {
            val sha = path.substringAfterLast("/")
            fetched += sha
            val bytes = files[sha] ?: return Reply(404, """{"error": "not_uploaded", "message": "not up yet"}""")
            open(true).use { it.write(bytes) }
            return Reply(200, "")
        }

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
            path.endsWith("/members") -> Reply(200, """[{"id": "m1", "nickname": "张三"}, {"id": "m2", "nickname": "李四"}]""")
            path.endsWith("/resolve") -> {
                val id = path.substringAfter("/conflicts/").substringBefore("/")
                resolved += id to Json.parseToJsonElement(body!!).jsonObject.getValue("choose").jsonPrimitive.content
                Reply(200, """{"conflict": {"id": "$id", "layer": "L1", "row": "r1", "seq": 7, "resolved": true, "kind": "value", "field": "f1"}}""")
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

    private fun value(json: String, seq: Long) = """{"value": $json, "by": "m1", "at": "2026-11-28T10:00:00Z", "seq": $seq}"""

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

        // Its change goes from the seq the row was at when it was edited: the server sees 远端 came alongside and keeps the later.
        run(SyncActions.NOW)
        val change = server.pushes.single()
        assertEquals("r1" to 9L, change.getValue("row").jsonPrimitive.content to change.getValue("base_seq").jsonPrimitive.long)
        assertEquals(setOf("f2"), change.getValue("set").jsonObject.keys)
    }

    @Test
    fun aConflictIsListedAndSettled() {
        val layer = """{"id": "L1", "geometry_type": "Point", "crs": "EPSG:4490", "schema_version": 1, "seq": 1,
            "name": ${field("野外点", 1)}, "deleted": null, "fields": [{"id": "f1", "name": "点名", "type": "string"}]}"""
        val mine = """{"value": "D009", "by": "m2", "at": "2026-11-28T09:00:00Z", "seq": 7}"""
        server.pages[0] = """{"layers": [$layer], "rows": [{"layer": "L1", "id": "r1", "conflicted": true, "deleted": null,
            "fields": {"f1": ${field("D001", 2)}, "geom": ${point(114.3, 2)}}}],
            "conflicts": [{"id": "k1", "layer": "L1", "row": "r1", "seq": 7, "resolved": false, "kind": "value", "field": "f1",
              "kept": ${field("D001", 2)}, "lost": $mine}], "seq": 8, "more": false}"""
        output(run(SyncActions.JOIN, """{"server": "http://fake", "invite_code": "K7QX2MPA", "nickname": "李四", "device": "test", "language": "zh"}"""))
        assertEquals(1, output(run(SyncActions.STATUS)).getValue("conflicts").jsonPrimitive.int)

        // Where it is and the two values, each with whose it is: the nicknames as the server lists them.
        val c = output(run(SyncActions.CONFLICTS)).getValue("conflicts").jsonArray.single().jsonObject
        assertEquals("k1", c.getValue("id").jsonPrimitive.content)
        assertEquals("value" to "点名", c.getValue("kind").jsonPrimitive.content to c.getValue("field").jsonPrimitive.content)
        assertEquals("野外点" to "D001", c.getValue("layer").jsonPrimitive.content to c.getValue("name").jsonPrimitive.content)
        val kept = c.getValue("kept").jsonObject
        val lost = c.getValue("lost").jsonObject
        assertEquals(Triple("D001", "张三", false), Triple(kept.getValue("value").jsonPrimitive.content, kept.getValue("by").jsonPrimitive.content, kept.getValue("mine").jsonPrimitive.content.toBoolean()))
        assertEquals(Triple("D009", "李四", true), Triple(lost.getValue("value").jsonPrimitive.content, lost.getValue("by").jsonPrimitive.content, lost.getValue("mine").jsonPrimitive.content.toBoolean()))

        // The lost value back: the server makes the change, and it comes in at once.
        server.pages[8] = """{"layers": [], "rows": [{"layer": "L1", "id": "r1", "conflicted": false, "deleted": null,
            "fields": {"f1": {"value": "D009", "by": "m2", "at": "2026-11-28T11:00:00Z", "seq": 9}}}], "conflicts": [], "seq": 9, "more": false}"""
        val settled = output(run(SyncActions.RESOLVE, """{"conflict": "k1", "choose": "lost"}"""))
        assertEquals(listOf("k1" to "lost"), server.resolved)
        assertEquals(0 to 1, settled.getValue("open_conflicts").jsonPrimitive.int to settled.getValue("pulled").jsonPrimitive.int)
        assertEquals("D009", feature("r1")!!.first)
        assertTrue(output(run(SyncActions.CONFLICTS)).getValue("conflicts").jsonArray.isEmpty())

        // Keeping a value changes nothing here: no pull, the record only settled.
        workspace.current!!.data { it.sql("UPDATE geocrew_conflicts SET resolved = 0 WHERE id = 'k1'") }
        val keeping = output(run(SyncActions.RESOLVE, """{"conflict": "k1", "choose": "kept"}"""))
        assertEquals("k1" to "kept", server.resolved.last())
        assertTrue(keeping["pulled"] is JsonNull, keeping.toString())
        assertEquals(0, keeping.getValue("open_conflicts").jsonPrimitive.int)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun aTeammatesPhotoComesOnItsFeatureTheThumbnailAtOnceTheOriginalWhenOpened() {
        val original = "the original photo".toByteArray()
        val thumb = "its thumbnail".toByteArray()
        val late = "a photo whose feature comes later".toByteArray()
        server.files[sha(original)] = original
        server.files[sha(thumb)] = thumb
        val layer = """{"id": "L1", "geometry_type": "Point", "crs": "EPSG:4490", "schema_version": 1, "seq": 1,
            "name": ${field("野外点", 1)}, "deleted": null, "fields": [{"id": "f1", "name": "点名", "type": "string"}]}"""
        fun attachment(id: String, row: String, file: String, thumbOf: String?, seq: Long) = """{"layer": "attachments", "id": "$id", "conflicted": false, "deleted": null,
            "fields": {"target_layer": ${value("\"L1\"", seq)}, "target_row": ${value("\"$row\"", seq)}, "file": ${value("\"$file\"", seq)},
            "thumb": ${value(thumbOf?.let { "\"$it\"" } ?: "null", seq)}, "type": ${value("\"image/jpeg\"", seq)}, "size": ${value("18", seq)},
            "name": ${value("\"D001.jpg\"", seq)}, "meta": ${value("null", seq)}}}"""
        // The second photo's feature was changed after it: it comes in a later page.
        server.pages[0] = """{"layers": [$layer], "rows": [
            {"layer": "L1", "id": "r1", "conflicted": false, "deleted": null, "fields": {"f1": ${field("D001", 2)}, "geom": ${point(114.3, 2)}}},
            ${attachment("a1", "r1", sha(original), sha(thumb), 3)},
            ${attachment("a2", "r2", sha(late), null, 5)}], "conflicts": [], "seq": 5, "more": true}"""
        server.pages[5] = """{"layers": [], "rows": [
            {"layer": "L1", "id": "r2", "conflicted": false, "deleted": null, "fields": {"f1": ${field("D002", 6)}, "geom": ${point(114.4, 6)}}}],
            "conflicts": [], "seq": 6, "more": false}"""
        output(run(SyncActions.JOIN, """{"server": "http://fake", "invite_code": "K7QX2MPA", "nickname": "李四", "device": "test", "language": "zh"}"""))

        val project = workspace.current!!
        val store = project.target("野外点").store
        fun on(row: String) = store.attachments(project.data { Outbox.fidOf(it, "L1", row) }!!)
        assertEquals(listOf("a1" to sha(original)), on("r1").map { it.id to it.file })
        assertEquals(listOf("a2"), on("r2").map { it.id }, "the photo waited for its feature")
        // The thumbnail came with the pull; the originals did not.
        assertTrue(File(project.attachments, sha(thumb) + ".jpg").readBytes().contentEquals(thumb))
        assertFalse(File(project.attachments, sha(original) + ".jpg").exists())
        assertEquals(listOf(sha(thumb)), server.fetched)

        // Opened: the original comes.
        val fetched = output(run(SyncActions.FILE, """{"file": "${sha(original)}", "type": "image/jpeg"}"""))
        assertTrue(File(fetched.getValue("path").jsonPrimitive.content).readBytes().contentEquals(original))
        // One not up yet says so.
        assertEquals(SyncActions.NOT_UPLOADED, (run(SyncActions.FILE, """{"file": "${sha(late)}", "type": "image/jpeg"}""") as Outcome.Failed).code)
        assertEquals(SyncActions.BAD_REQUEST, (run(SyncActions.FILE, """{"file": "../../x", "type": "image/jpeg"}""") as Outcome.Failed).code)

        // Taken off by the teammate: it goes here too. A deleted row keeps its fields (protocol v1 6.1).
        val gone = attachment("a1", "r1", sha(original), sha(thumb), 3)
            .replace(""""deleted": null""", """"deleted": {"value": true, "by": "m1", "at": "2026-11-28T10:00:00Z", "seq": 7}""")
        server.pages[6] = """{"layers": [], "rows": [$gone], "conflicts": [], "seq": 7, "more": false}"""
        output(run(SyncActions.NOW))
        assertTrue(on("r1").isEmpty())

        // A photo for a feature deleted here waits: it is not put on the fid, which a new feature may get.
        run(Editing.START, """{"layer": "野外点"}""")
        run(Editing.DELETE, """{"layer": "野外点", "fid": ${project.data { Outbox.fidOf(it, "L1", "r2") }}}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")
        server.pages[7] = """{"layers": [], "rows": [${attachment("a3", "r2", sha(original), null, 8)}], "conflicts": [], "seq": 8, "more": false}"""
        output(run(SyncActions.NOW))
        assertTrue(project.data { rows(it, "SELECT uuid FROM geocrew_attachments WHERE uuid = 'a3'") }.isEmpty(), "not on the deleted feature")
        assertEquals(1, project.data { rows(it, "SELECT uuid FROM geocrew_pending_attachments WHERE uuid = 'a3'") }.size, "waiting for it")
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
