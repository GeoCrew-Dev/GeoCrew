package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.edit.EditBuffer
import org.geocrew.core.edit.FieldType
import org.geocrew.core.project.LayerTemplate
import org.geocrew.core.project.TemplateField
import org.geocrew.core.project.Templates
import org.geocrew.nativelib.VectorDataset
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Projects in a GeoCrew folder, the way ADR 0004 lays them out.
@RunWith(AndroidJUnit4::class)
class ProjectsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew")
    private val journals = File(context.noBackupFilesDir, "journals-test")
    private val projects = Projects(root, journals)

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        journals.deleteRecursively()
    }

    private fun sql(project: String, statement: String): List<JsonObject> {
        val path = File(root, "工程/$project/$project.gpkg").path
        return VectorDataset.open(path, update = true).use { ds ->
            Json.parseToJsonElement(ds.sql(statement)).jsonArray.map { it.jsonObject.getValue("properties").jsonObject }
        }
    }

    @Test
    fun aNewProjectHasTheLayoutAndTheTemplates() {
        projects.create("测区一号").use { p ->
            assertEquals(listOf("地质点"), p.layers.map { it.name })
            assertEquals("geo-point", p.layers.single().template)
            UUID.fromString(p.id)
            assertTrue(p.attachments.isDirectory)
            val info = Json.parseToJsonElement(VectorDataset.open(File(p.folder, "测区一号.gpkg").path).use { it.layerInfo("地质点") }).jsonObject
            assertEquals("EPSG:4490", info.getValue("srs").jsonPrimitive.content)
            assertEquals(Templates.GEO_POINT.fields.map { it.name }, info.getValue("fields").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        }
        assertTrue(File(root, ".nomedia").exists())
        assertEquals(listOf("测区一号"), projects.list())
        val ids = sql("测区一号", "SELECT column_name, id FROM geocrew_fields WHERE table_name = '地质点'")
        assertEquals(Templates.GEO_POINT.fields.size, ids.map { (it["id"] as JsonPrimitive).content }.toSet().size, "every field has its own id")
        val version = sql("测区一号", "SELECT value FROM geocrew_project WHERE key = 'format_version'").single()["value"]
        assertEquals(JsonPrimitive("1"), version)
    }

    @Test
    fun aProjectLayerIsEditedTheQgisWay() {
        projects.create("编辑").use { p ->
            val target = p.target("地质点")
            val buffer = EditBuffer(target.store, target.journal)
            buffer.add(
                Json.parseToJsonElement("""{"type": "Point", "coordinates": [114.305, 30.5928]}""").jsonObject,
                Json.parseToJsonElement("""{"点号": "D001", "岩性": "花岗岩", "倾向": 135, "倾角": 42}""").jsonObject,
            )
            assertTrue(journals.walkTopDown().any { it.name == "${p.layers.single().id}.jsonl" }, "the journal is in the app's own files")
            buffer.save()
        }
        projects.open("编辑").use { p ->
            val saved = p.target("地质点").store.features().single()
            assertEquals(JsonPrimitive("D001"), saved.properties["点号"])
            assertEquals(JsonPrimitive(42), saved.properties["倾角"])
        }
        assertFailsWith<IllegalArgumentException> { projects.open("编辑").use { it.target("路线") } }
    }

    @Test
    fun aCopiedProjectHasItsOwnJournal() {
        projects.create("原件").use { p ->
            val target = p.target("地质点")
            EditBuffer(target.store, target.journal).add(null) // unsaved when the app is killed
        }
        File(root, "工程/原件").copyRecursively(File(root, "工程/副本"))
        File(root, "工程/副本/原件.gpkg").renameTo(File(root, "工程/副本/副本.gpkg"))
        projects.open("副本").use { copy ->
            val target = copy.target("地质点")
            assertEquals(org.geocrew.core.edit.Recovery.NOTHING, EditBuffer(target.store, target.journal).recover())
        }
        projects.open("原件").use { p ->
            val target = p.target("地质点")
            assertEquals(org.geocrew.core.edit.Recovery.RESTORED, EditBuffer(target.store, target.journal).recover())
        }
    }

    @Test
    fun badNamesAndDuplicatesAreRefused() {
        assertFailsWith<IllegalArgumentException> { projects.create("a/b") }
        projects.create("测区").close()
        assertFailsWith<IllegalArgumentException> { projects.create("测区") }
        assertFailsWith<IllegalArgumentException> { projects.open("没有") }
    }

    @Test
    fun aFailedCreationLeavesNothing() {
        val broken = LayerTemplate("x", "坏", "POINT", "EPSG:4490", listOf(TemplateField("附件", FieldType.OTHER, "cannot be made")))
        assertFailsWith<IllegalArgumentException> { projects.create("半成品", listOf(broken)) }
        assertFalse(File(root, "工程/半成品").exists())
        assertEquals(emptyList(), projects.list())
    }

    @Test
    fun aNewerOrForeignFileIsNotOpened() {
        projects.create("新版").close()
        sql("新版", "UPDATE geocrew_project SET value = '99' WHERE key = 'format_version'")
        val newer = assertFailsWith<IllegalStateException> { projects.open("新版") }
        assertTrue(newer.message!!.contains("newer"), newer.message)

        val foreign = File(root, "工程/别人的/别人的.gpkg").apply { parentFile!!.mkdirs() }
        VectorDataset.create(foreign.path).close()
        assertFailsWith<RuntimeException> { projects.open("别人的") }

        val damaged = File(root, "工程/坏的/坏的.gpkg").apply { parentFile!!.mkdirs(); writeText("not a database") }
        assertFailsWith<RuntimeException> { projects.open("坏的") }
        assertTrue(damaged.exists(), "a file that does not open is left as it is")
    }
}
