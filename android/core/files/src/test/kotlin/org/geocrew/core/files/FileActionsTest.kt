package org.geocrew.core.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.Caller

class FileActionsTest {
    private val storage: File = Files.createTempDirectory("storage").toFile().canonicalFile
    private val geocrew = File(storage, "GeoCrew").apply { mkdirs() }
    private var open: File? = null
    private val files = FileActions(geocrew, top = { storage }, busy = { open })

    @AfterTest
    fun cleanUp() {
        storage.deleteRecursively()
    }

    private fun run(action: String, params: String, caller: Caller = Caller.USER): ActionResult = runBlocking {
        files.actions.single { it.spec.id == action }.run(caller, Json.parseToJsonElement(params).jsonObject)
    }

    private fun done(action: String, params: String, caller: Caller = Caller.USER): JsonObject =
        assertIs<ActionResult.Done>(run(action, params, caller), action).output.jsonObject

    private fun failed(action: String, params: String, caller: Caller = Caller.USER): String =
        assertIs<ActionResult.Failed>(run(action, params, caller), action).code

    private fun names(listing: JsonObject) = listing.getValue("entries").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

    private fun json(path: File) = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(path.path))

    @Test
    fun listsFoldersFirstInNaturalOrder() {
        File(geocrew, "D10.kml").writeText("x")
        File(geocrew, "d2.kml").writeText("x")
        File(geocrew, "影像.tif").writeText("xy")
        File(geocrew, "导出").mkdir()
        File(geocrew, ".nomedia").writeText("")
        val listing = done(FileActions.LIST, "{}")
        assertEquals(geocrew.path, listing.getValue("path").jsonPrimitive.content, "the GeoCrew folder by default")
        assertEquals(listOf("导出", "d2.kml", "D10.kml", "影像.tif"), names(listing), "hidden files left out")
        val tif = listing.getValue("entries").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "影像.tif" }
        assertEquals("raster", tif.getValue("kind").jsonPrimitive.content)
        assertEquals(2, tif.getValue("size").jsonPrimitive.content.toInt())
        assertEquals(".nomedia", names(done(FileActions.LIST, """{"hidden": true}""")).first { it.startsWith(".") })
        assertEquals(storage.path, listing.getValue("parent").jsonPrimitive.content, "the user may go up")
    }

    @Test
    fun theAiStaysInTheGeoCrewFolder() {
        File(storage, "Download").mkdir()
        assertIs<ActionResult.Done>(run(FileActions.LIST, """{"path": ${json(File(storage, "Download"))}}"""))
        assertEquals(FileActions.OUTSIDE, failed(FileActions.LIST, """{"path": ${json(File(storage, "Download"))}}""", Caller.AI))
        assertEquals(FileActions.OUTSIDE, failed(FileActions.LIST, """{"path": "../Download"}""", Caller.AI), "no way out by ..")
        assertEquals("null", done(FileActions.LIST, "{}", Caller.AI).getValue("parent").toString(), "nothing above the GeoCrew folder")
        assertEquals(FileActions.OUTSIDE, failed(FileActions.LIST, """{"path": ${json(storage.parentFile)}}"""), "nor above the storage")
    }

    @Test
    fun makesRenamesCopiesMovesAndDeletes() {
        val made = File(done(FileActions.MAKE_FOLDER, """{"path": ${json(geocrew)}, "name": "影像"}""").getValue("path").jsonPrimitive.content)
        assertTrue(made.isDirectory)
        assertEquals(FileActions.EXISTS, failed(FileActions.MAKE_FOLDER, """{"path": ${json(geocrew)}, "name": "影像"}"""))
        assertEquals(FileActions.BAD_NAME, failed(FileActions.MAKE_FOLDER, """{"path": ${json(geocrew)}, "name": "a/b"}"""))

        val tif = File(storage, "a.tif").apply { writeText("raster") }
        val copied = File(done(FileActions.COPY, """{"path": ${json(tif)}, "to": ${json(made)}}""").getValue("path").jsonPrimitive.content)
        assertEquals("raster", copied.readText())
        val again = File(done(FileActions.COPY, """{"path": ${json(tif)}, "to": ${json(made)}}""").getValue("path").jsonPrimitive.content)
        assertEquals("a (2).tif", again.name, "a name already there gets a number")

        val renamed = File(done(FileActions.RENAME, """{"path": ${json(again)}, "name": "b.tif"}""").getValue("path").jsonPrimitive.content)
        assertEquals("b.tif", renamed.name)
        assertEquals(FileActions.EXISTS, failed(FileActions.RENAME, """{"path": ${json(renamed)}, "name": "a.tif"}"""))
        val cased = File(done(FileActions.RENAME, """{"path": ${json(renamed)}, "name": "B.tif"}""").getValue("path").jsonPrimitive.content)
        assertEquals(listOf("a.tif", "B.tif"), made.list()!!.sortedBy { it.lowercase() }, "a change of case alone is a rename")

        val moved = File(done(FileActions.MOVE, """{"path": ${json(tif)}, "to": ${json(geocrew)}}""").getValue("path").jsonPrimitive.content)
        assertFalse(tif.exists())
        assertEquals(File(geocrew, "a.tif"), moved)
        assertEquals(FileActions.INSIDE, failed(FileActions.MOVE, """{"path": ${json(made)}, "to": ${json(made)}}"""), "not into itself")

        done(FileActions.DELETE, """{"path": ${json(made)}}""")
        assertFalse(made.exists(), "a folder goes with what is in it")
        assertFalse(cased.exists())
    }

    @Test
    fun theOpenProjectAndTheTopFoldersStay() {
        val project = File(geocrew, "测区一号").apply { mkdir() }
        val gpkg = File(project, "测区一号.gpkg").apply { writeText("gpkg") }
        open = project
        assertEquals(FileActions.BUSY, failed(FileActions.DELETE, """{"path": ${json(gpkg)}}"""))
        assertEquals(FileActions.BUSY, failed(FileActions.RENAME, """{"path": ${json(project)}, "name": "二号"}"""))
        assertEquals(FileActions.PROTECTED, failed(FileActions.DELETE, """{"path": ${json(geocrew)}}"""))
        assertEquals(FileActions.PROTECTED, failed(FileActions.DELETE, """{"path": ${json(storage)}}"""))
        assertEquals(FileActions.BUSY, failed(FileActions.COPY, """{"path": ${json(gpkg)}, "to": ${json(geocrew)}}"""), "its saved edits may be in the -wal file")
        open = null
        assertIs<ActionResult.Done>(run(FileActions.DELETE, """{"path": ${json(project)}}"""))
    }

    @Test
    fun kindsAndOrder() {
        assertEquals(FileKind.VECTOR, FileKind.of(File("a.GPKG")))
        assertEquals(FileKind.OTHER, FileKind.of(File("a.dbf")), "a Shapefile's parts are not layers")
        assertEquals(FileKind.RASTER, FileKind.of(File("dem.tif")))
        assertTrue(NaturalOrder.compare("D2", "D10") < 0)
        assertTrue(NaturalOrder.compare("D010", "D9") > 0)
        assertTrue(NaturalOrder.compare("a", "B") < 0)
    }
}
