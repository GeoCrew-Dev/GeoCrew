package org.geocrew.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.nativelib.GeoCrewNative
import org.geocrew.nativelib.VectorDataset
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Photos and other files kept with features (ADR 0004): stored by their hash, listed with the Related Tables Extension.
@RunWith(AndroidJUnit4::class)
class AttachmentsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-attachments")
    private lateinit var registry: ActionRegistry

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        val workspace = Workspace(Projects(root, File(context.noBackupFilesDir, "journals-attachments").apply { deleteRecursively() }))
        registry = ActionRegistry().apply {
            workspace.actions.forEach(::register)
            AttachmentActions(workspace, root).actions.forEach(::register)
        }
    }

    private fun run(action: String, params: String = "{}", caller: Caller = Caller.USER): Outcome =
        runBlocking { registry.invoke(caller, action, Json.parseToJsonElement(params).jsonObject) }

    private fun output(o: Outcome): JsonObject = (o as Outcome.Done).output.jsonObject

    private fun rows(sql: String): List<JsonObject> = VectorDataset.open(File(root, "工程/照片/照片.gpkg").path).use { ds ->
        Json.parseToJsonElement(ds.sql(sql)).jsonArray.map { it.jsonObject.getValue("properties").jsonObject }
    }

    @Test
    fun aPhotoIsKeptWithItsFeature() {
        run(Workspace.CREATE, """{"name": "照片", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        run(Editing.START, """{"layer": "野外点"}""")
        val photo = File(context.cacheDir, "露头.jpg")
        photo.outputStream().use { Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(photo.readBytes()).joinToString("") { "%02x".format(it) }

        val imported = output(run(AttachmentActions.IMPORT, """{"path": "${photo.path}"}"""))
        assertEquals(hash, imported.getValue("file").jsonPrimitive.content, "named by its hash")
        assertEquals("image/jpeg", imported.getValue("type").jsonPrimitive.content)
        assertEquals("露头.jpg", imported.getValue("name").jsonPrimitive.content)
        val folder = File(root, "工程/照片/附件")
        assertTrue(File(folder, "$hash.jpg").isFile)
        val thumb = File(folder, imported.getValue("thumb").jsonPrimitive.content + ".jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(thumb.path, it) }
        assertEquals(640 to 320, bounds.outWidth to bounds.outHeight, "a thumbnail 640 pixels on its long side")
        run(AttachmentActions.IMPORT, """{"path": "${photo.path}"}""")
        assertEquals(2, folder.listFiles()!!.size, "the same file is kept once")
        assertEquals("outside", (run(AttachmentActions.IMPORT, """{"path": "${photo.path}"}""", Caller.AI) as Outcome.Failed).code)

        // Added with its feature, in one step; saved with it.
        val added = output(run(Editing.ADD, """{"layer": "野外点", "properties": {"点名": "D001"}, "attachments": [$imported]}"""))
        val fid = added.getValue("fid").jsonPrimitive.long
        fun listed(fid: Long) = output(run(AttachmentActions.LIST, """{"layer": "野外点", "fid": $fid}""")).getValue("attachments").jsonArray.map { it.jsonObject }
        val unsaved = listed(fid).single()
        assertEquals(File(folder, "$hash.jpg").path, unsaved.getValue("path").jsonPrimitive.content)
        assertEquals(thumb.path, unsaved.getValue("thumbPath").jsonPrimitive.content)
        val saved = output(run(Editing.SAVE, """{"layer": "野外点"}""")).getValue("fids").jsonObject.getValue("$fid").jsonPrimitive.long
        assertEquals(unsaved.getValue("id"), listed(saved).single().getValue("id"), "read back from the file")
        val layerId = output(run(Workspace.CURRENT)).getValue("layers").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
        val mapping = AttachmentTables.mapping(layerId)
        val relation = rows("SELECT base_table_name, base_primary_column, related_table_name, relation_name FROM gpkgext_relations").single()
        assertEquals(listOf("野外点", "fid", "geocrew_attachments", "attributes"), relation.values.map { it.jsonPrimitive.content })
        // GDAL reads it as a relationship, which is how QGIS finds it.
        val info = GeoCrewNative.run(arrayOf("vector", "info", "--format", "json", File(root, "工程/照片/照片.gpkg").path))
        val relationships = Json.parseToJsonElement(info).jsonObject["relationships"]
        assertNotNull(relationships, info)
        assertTrue(relationships.toString().contains(mapping), relationships.toString())

        // The layer keeps them when it is renamed; KML leaves the attachment table out.
        run(Workspace.CLOSE)
        run(Workspace.OPEN, """{"name": "照片"}""")
        assertIs<Outcome.Done>(run(Workspace.LAYER_RENAME, """{"layer": "野外点", "to": "露头点"}"""))
        assertEquals(1, output(run(AttachmentActions.LIST, """{"layer": "露头点", "fid": $saved}""")).getValue("attachments").jsonArray.size)
        val kml = File(output(run(Workspace.EXPORT, """{"format": "kml"}""")).getValue("path").jsonPrimitive.content).readText()
        assertTrue("露头点" in kml)
        assertFalse("geocrew_attachments" in kml)

        // Taken off and saved, the row goes; the layer's deletion takes the rest.
        run(Editing.START, """{"layer": "露头点"}""")
        assertIs<Outcome.Done>(run(Editing.ATTACHMENTS, """{"layer": "露头点", "fid": $saved, "attachments": []}"""))
        run(Editing.SAVE, """{"layer": "露头点"}""")
        assertTrue(rows("SELECT id FROM geocrew_attachments").isEmpty())
        run(Editing.STOP, """{"layer": "露头点"}""")
        assertIs<Outcome.Done>(run(Workspace.LAYER_DELETE, """{"layer": "露头点"}"""))
        assertTrue(rows("SELECT name FROM sqlite_master WHERE name = '$mapping'").isEmpty())
        assertTrue(rows("SELECT id FROM gpkgext_relations").isEmpty())
    }
}
