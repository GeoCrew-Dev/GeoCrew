package org.geocrew.storage

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.core.sync.HttpTransport
import org.geocrew.core.sync.SyncClient
import org.junit.Test
import org.junit.runner.RunWith

// A photo going to a real GeoCrew server and coming off it again, given by the instrumentation argument geocrewServer
// (for example http://10.0.2.2:8080, the computer running the emulator, with `go run ./cmd/geocrew-server -tls=off`); skipped without it.
@RunWith(AndroidJUnit4::class)
class LiveSyncTest {
    private val server: String? = InstrumentationRegistry.getArguments().getString("geocrewServer")?.takeIf { it.isNotBlank() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aPhotoReachesTheServerAndIsTakenOffAgain() {
        val base = server ?: return
        NativeData.setup(context)
        val root = File(context.filesDir, "GeoCrew-live").apply { deleteRecursively() }
        val accounts = File(context.noBackupFilesDir, "sync-live").apply { deleteRecursively() }
        val workspace = Workspace(Projects(root, File(context.noBackupFilesDir, "journals-live").apply { deleteRecursively() }))
        val registry = ActionRegistry().apply {
            workspace.actions.forEach(::register)
            AttachmentActions(workspace, context.filesDir).actions.forEach(::register)
            SyncActions(workspace, accounts).actions.forEach(::register)
        }
        fun run(action: String, params: String = "{}"): kotlinx.serialization.json.JsonObject {
            val done = runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }
            assertTrue(done is Outcome.Done, "$action: $done")
            return done.output.jsonObject
        }

        run(Workspace.CREATE, """{"name": "联调照片", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Editing.START, """{"layer": "野外点"}""")
        val jpeg = File(context.cacheDir, "live.jpg")
        // Noise: a photo of a few MB, several chunks.
        val (w, h) = 2000 to 1500
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(IntArray(w * h) { Random.nextInt() or Color.BLACK }, 0, w, 0, 0, w, h) }
        jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        assertTrue(jpeg.length() > 2 * 1024 * 1024, "${jpeg.length()} bytes")
        val photo = run(AttachmentActions.IMPORT, """{"path": "${jpeg.path}"}""")
        val file = photo.getValue("file").jsonPrimitive.content
        val attachment = buildJsonObject { for (k in listOf("file", "thumb", "type", "size", "name")) put(k, photo.getValue(k)) }
        run(
            Editing.ADD,
            buildJsonObject {
                put("layer", "野外点")
                put("geometry", Json.parseToJsonElement("""{"type": "Point", "coordinates": [114.3, 30.6]}"""))
                put("properties", buildJsonObject { put("点名", "D001") })
                put("attachments", buildJsonArray { add(attachment) })
            }.toString(),
        )
        run(Editing.SAVE, """{"layer": "野外点"}""")

        run(SyncActions.SHARE, """{"server": "$base", "nickname": "组长", "device": "emulator"}""")
        val status = run(SyncActions.STATUS)
        assertEquals(0 to 0, status.getValue("files").jsonPrimitive.int to status.getValue("rejected").jsonPrimitive.int, status.toString())

        // The server has the photo whole, and the row that names it.
        val account = checkNotNull(SyncAccounts(accounts).of(workspace.current!!.id))
        val c = URI("$base/api/v1/workspaces/${account.workspace}/files/$file").toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer ${account.token}")
        assertEquals(200, c.responseCode)
        assertContentEquals(jpeg.readBytes(), c.inputStream.use { it.readBytes() })
        val client = SyncClient(HttpTransport(base))
        val row = client.pull(account.workspace, account.token, 0).rows.single { it.layer == "attachments" }
        assertEquals(JsonPrimitive(file), row.fields.getValue("file").value)

        // Taken off: the deletion goes from the seq the pull saw, so the server takes it, with no conflict.
        val fid = workspace.current!!.target("野外点").store.features().single().fid
        run(Editing.ATTACHMENTS, """{"layer": "野外点", "fid": $fid, "attachments": []}""")
        run(Editing.SAVE, """{"layer": "野外点"}""")
        val synced = run(SyncActions.NOW)
        assertEquals(0 to 0, synced.getValue("rejected").jsonPrimitive.int to synced.getValue("open_conflicts").jsonPrimitive.int, synced.toString())
        val after = client.pull(account.workspace, account.token, 0)
        assertTrue(after.rows.single { it.layer == "attachments" }.deleted, "deleted on the server")
        assertTrue(after.conflicts.isEmpty(), after.conflicts.toString())
    }
}
