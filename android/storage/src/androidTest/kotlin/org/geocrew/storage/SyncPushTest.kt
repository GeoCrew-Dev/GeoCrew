package org.geocrew.storage

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
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
import org.geocrew.core.sync.KeyChanged
import org.geocrew.core.sync.Reply
import org.geocrew.core.sync.ServerKey
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
        val seqs = mutableMapOf<String, Long>() // the seq each applied change was given
        var seq = 0L
        var loseNextAnswer = false
        var refuse: String? = null

        // Files as protocol v1 9.2 has them; what happened, in order: a file stored by its hash, or "rows" for a push.
        val files = mutableMapOf<String, ByteArray>()
        private val uploads = mutableMapOf<String, Triple<String, Long, ByteArrayOutputStream>>()
        val events = mutableListOf<String>()

        override fun sendBytes(method: String, path: String, token: String?, body: ByteArray): Reply {
            val id = path.substringAfter("/uploads/").substringBefore("?")
            val (sha, size, got) = uploads[id] ?: return Reply(404, """{"error": "not_found", "message": "no such upload"}""")
            if (path.substringAfter("offset=").toLong() != got.size().toLong()) return Reply(409, """{"error": "offset_mismatch", "message": "", "offset": ${got.size()}}""")
            got.write(body)
            if (got.size() < size) return Reply(200, """{"offset": ${got.size()}}""")
            uploads.remove(id)
            val hash = MessageDigest.getInstance("SHA-256").digest(got.toByteArray()).joinToString("") { "%02x".format(it) }
            if (hash != sha) return Reply(400, """{"error": "hash_mismatch", "message": ""}""")
            files[sha] = got.toByteArray()
            events += sha
            return Reply(201, """{"sha256": "$sha"}""")
        }

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
                path.endsWith("/uploads") -> {
                    val sha = o!!.getValue("sha256").jsonPrimitive.content
                    if (sha in files) return Reply(200, """{"exists": true}""")
                    val id = "u_${uploads.size + files.size + 1}"
                    uploads[id] = Triple(sha, o.getValue("size").jsonPrimitive.long, ByteArrayOutputStream())
                    Reply(200, """{"upload": "$id", "offset": 0, "chunk_size": 4096}""")
                }
                path.endsWith("/rows") -> {
                    val changes = o!!.getValue("changes").jsonArray.map { it.jsonObject }
                    pushes += changes
                    events += "rows"
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
                                            seqs[id] = seq
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
                // Pulls bring nothing here: what this test sends is all there is.
                path.contains("/changes?") -> Reply(200, """{"layers": [], "rows": [], "conflicts": [], "seq": $seq, "more": false}""")
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
            AttachmentActions(workspace, context.filesDir).actions.forEach(::register)
            SyncActions(workspace, accounts) { _, _ -> server }.actions.forEach(::register)
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
    fun theServerKeyCheckedIsPinnedAndAChangedKeyRefused() {
        val pins = mutableListOf<String?>()
        var changed = false
        registry = ActionRegistry().apply {
            workspace.actions.forEach(::register)
            SyncActions(workspace, File(context.noBackupFilesDir, "sync-test-pin").apply { deleteRecursively() }, { ServerKey("the-key", trusted = false) }) { _, pin ->
                pins += pin
                if (changed) Transport { _, _, _, _ -> throw KeyChanged("another-key") } else server
            }.actions.forEach(::register)
        }
        assertEquals("the-key", output(run(SyncActions.KEY, """{"server": "https://fake"}""")).getValue("fingerprint").jsonPrimitive.content)
        run(Workspace.CREATE, """{"name": "固定", "language": "zh"}""")
        output(run(SyncActions.SHARE, """{"server": "https://fake", "pin": "the-key", "nickname": "组长", "device": "test"}"""))
        assertEquals("the-key", output(run(SyncActions.STATUS)).getValue("fingerprint").jsonPrimitive.content)
        output(run(SyncActions.NOW))
        assertEquals(listOf<String?>("the-key"), pins.distinct(), "every call takes only the pinned key")
        changed = true
        assertEquals(SyncActions.KEY_CHANGED, (run(SyncActions.NOW) as Outcome.Failed).code)
    }

    @Test
    fun aPhotoGoesWithItsFeatureItsThumbnailBeforeAndTheOriginalAfter() {
        run(Workspace.CREATE, """{"name": "照片", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Editing.START, """{"layer": "野外点"}""")
        val jpeg = File(context.cacheDir, "露头.jpg")
        jpeg.outputStream().use { out ->
            Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(160, 120, 80)) }.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        val photo = output(run(AttachmentActions.IMPORT, """{"path": "${jpeg.path}"}"""))
        val file = photo.getValue("file").jsonPrimitive.content
        val thumb = photo.getValue("thumb").jsonPrimitive.content
        val attachment = buildJsonObject { for (k in listOf("file", "thumb", "type", "size", "name")) put(k, photo.getValue(k)) }
        run(Editing.ADD, buildJsonObject {
            put("layer", "野外点")
            put("geometry", Json.parseToJsonElement("""{"type": "Point", "coordinates": [114.3, 30.6]}"""))
            put("properties", buildJsonObject { put("点名", "D001") })
            put("attachments", buildJsonArray { add(attachment) })
        }.toString())
        run(Editing.SAVE, """{"layer": "野外点"}""")

        output(run(SyncActions.SHARE, """{"server": "http://fake", "nickname": "组长", "device": "test"}"""))
        // The thumbnail before the rows, the original after them (protocol v1 9.3); each as it is.
        assertEquals(listOf(thumb, "rows", file), server.events)
        assertTrue(server.files.getValue(file).contentEquals(jpeg.readBytes()), "the original, whole")
        // The attachment row after the feature row it hangs on, with what the protocol has for it.
        val changes = server.pushes.flatten()
        val row = changes.indexOfFirst { it.getValue("layer").jsonPrimitive.content != "attachments" }
        val att = changes.indexOfFirst { it.getValue("layer").jsonPrimitive.content == "attachments" }
        assertTrue(row in 0 until att, changes.toString())
        val set = changes[att].getValue("set").jsonObject
        assertEquals(changes[row].getValue("row").jsonPrimitive.content, set.getValue("target_row").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(workspace.current!!.layers.single().id, set.getValue("target_layer").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(file to thumb, set.getValue("file").jsonObject.getValue("value").jsonPrimitive.content to set.getValue("thumb").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("image/jpeg", set.getValue("type").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(0, output(run(SyncActions.STATUS)).getValue("files").jsonPrimitive.int, "every file is up")

        // The photo taken off: its row goes deleted; nothing is uploaded again.
        val fid = workspace.current!!.target("野外点").store.features().single().fid
        run(Editing.ATTACHMENTS, """{"layer": "野外点", "fid": $fid, "attachments": []}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")
        output(run(SyncActions.NOW))
        val deleted = server.pushes.last().single()
        assertEquals("attachments" to changes[att].getValue("row").jsonPrimitive.content, deleted.getValue("layer").jsonPrimitive.content to deleted.getValue("row").jsonPrimitive.content)
        assertEquals("true", deleted.getValue("delete").jsonObject.getValue("value").toString())
        assertTrue(deleted.getValue("base_seq").jsonPrimitive.long > 0, "from the seq its creation was taken at, so the server takes the deletion")
        assertEquals(listOf(thumb, "rows", file, "rows"), server.events)
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
        // A row this phone made: its creation's seq is its base, before any pull has seen it (protocol v1 8.2).
        val last = server.pushes.last().single()
        val creation = server.pushes.flatten().first { it.getValue("row") == last.getValue("row") }
        assertEquals(server.seqs.getValue(creation.getValue("id").jsonPrimitive.content), last.getValue("base_seq").jsonPrimitive.long)

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
