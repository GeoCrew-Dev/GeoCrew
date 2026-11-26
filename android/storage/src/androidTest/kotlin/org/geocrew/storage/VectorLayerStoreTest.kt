package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.edit.EditBuffer
import org.geocrew.core.edit.FieldType
import org.geocrew.core.edit.FileJournal
import org.geocrew.core.edit.JournalEntry
import org.geocrew.core.edit.Recovery
import org.geocrew.nativelib.VectorDataset
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

private fun point(x: Double, y: Double) = json("""{"type": "Point", "coordinates": [$x, $y]}""")

// The edit buffer on a real GeoPackage, the way a project layer is edited (ADR 0004, 0005).
@RunWith(AndroidJUnit4::class)
class VectorLayerStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gpkg = File(context.filesDir, "store-test.gpkg")
    private val journalFile = File(context.filesDir, "store-test.jsonl")
    private val layer = "地质点"
    private lateinit var ds: VectorDataset

    @Before
    fun setUp() {
        NativeData.setup(context)
        for (suffix in listOf("", "-wal", "-shm")) File(gpkg.path + suffix).delete()
        journalFile.delete()
        ds = VectorDataset.create(gpkg.path)
        ds.createLayer(layer, "POINT", "EPSG:4547", """[{"name": "岩性", "type": "string"}, {"name": "倾角", "type": "real"}]""")
    }

    @After
    fun tearDown() = ds.close()

    private fun stored(d: VectorDataset = ds) = Json.parseToJsonElement(d.features(layer)).jsonArray.map { it.jsonObject }

    private fun reopen(): VectorDataset {
        ds.close()
        return VectorDataset.open(gpkg.path, update = true, wal = true).also { ds = it }
    }

    @Test
    fun editsReachTheFileOnlyWhenSaved() {
        val store = VectorLayerStore(ds, layer)
        assertEquals(listOf(FieldType.STRING, FieldType.REAL), store.fields.map { it.type })
        val buffer = EditBuffer(store, FileJournal(journalFile))
        buffer.add(point(500123.4567891234, 3320113.0123456789), json("""{"岩性": "花岗岩", "倾角": 35.5}"""))
        buffer.add(point(500200.0, 3320200.0), json("""{"岩性": "片麻岩"}"""))
        assertEquals(0, stored().size, "nothing is written before saving")
        assertEquals(listOf(1L, 2L), buffer.save().values.toList())
        assertEquals(2, store.lastFid(), "the table's highest fid")

        buffer.changeValues(1, json("""{"倾角": 40.25}"""))
        buffer.delete(2)
        buffer.save()
        val rows = stored(reopen())
        assertEquals(1, rows.size)
        val properties = rows.single().getValue("properties").jsonObject
        assertEquals(JsonPrimitive("花岗岩"), properties["岩性"])
        assertEquals(JsonPrimitive(40.25), properties["倾角"])
        val xy = rows.single().getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(500123.4567891234, xy[0].jsonPrimitive.double, "coordinates come back exactly")
        assertFalse(journalFile.exists(), "a save leaves no journal")
    }

    @Test
    fun unsavedEditsOutliveACrash() {
        val buffer = EditBuffer(VectorLayerStore(ds, layer), FileJournal(journalFile))
        buffer.add(point(1.0, 2.0), json("""{"岩性": "灰岩"}"""))
        buffer.add(point(3.0, 4.0), json("""{"岩性": "砂岩"}"""))
        // The app is killed: the buffer is gone, and the file is opened again.
        val again = EditBuffer(VectorLayerStore(reopen(), layer), FileJournal(journalFile))
        assertEquals(Recovery.RESTORED, again.recover())
        assertEquals(2, again.features().size)
        again.save()
        assertEquals(listOf("灰岩", "砂岩"), stored().map { (it.getValue("properties").jsonObject["岩性"] as JsonPrimitive).content })
    }

    @Test
    fun aSaveThatWentThroughIsNotRepeated() {
        val store = VectorLayerStore(ds, layer)
        val journal = FileJournal(journalFile)
        val buffer = EditBuffer(store, journal)
        buffer.add(point(1.0, 2.0), json("""{"岩性": "灰岩"}"""))
        val unsaved = journal.entries()
        val mark = JournalEntry.Saving(store.lastFid())
        buffer.save()
        // The app is killed after the commit, before the journal is cleared.
        journal.rewrite(unsaved + mark)
        val again = EditBuffer(VectorLayerStore(reopen(), layer), FileJournal(journalFile))
        assertEquals(Recovery.SAVED, again.recover())
        assertEquals(1, stored().size, "the feature is not added twice")
    }

    @Test
    fun aReadOnlyLayerTakesNoEdits() {
        ds.close()
        ds = VectorDataset.open(gpkg.path)
        val store = VectorLayerStore(ds, layer)
        assertFalse(store.capabilities.add || store.capabilities.update || store.capabilities.delete)
        assertFailsWith<IllegalArgumentException> { EditBuffer(store, FileJournal(journalFile)).add(point(0.0, 0.0)) }
    }
}
