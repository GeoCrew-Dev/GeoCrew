package org.geocrew.core.edit

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class FileJournalTest {
    private val dir: File = Files.createTempDirectory("journal").toFile().apply { deleteOnExit() }
    private val file = File(dir, "edits.jsonl")
    private val journal = FileJournal(file)

    private val a = JournalEntry.Set(-1, Slot(added = Feature(-1, properties = JsonObject(emptyMap()))))
    private val b = JournalEntry.Set(3, Slot(deleted = true))

    @Test
    fun keepsWhatWasAppended() {
        assertEquals(emptyList(), journal.entries())
        journal.append(a)
        journal.append(JournalEntry.Saving(7))
        journal.append(JournalEntry.Failed)
        assertEquals(listOf(a, JournalEntry.Saving(7), JournalEntry.Failed), FileJournal(file).entries())
    }

    @Test
    fun aLineCutShortIsIgnored() {
        journal.append(a)
        file.appendText("""{"type":"set","fid":3,"slo""")
        assertEquals(listOf(a), journal.entries())
        FileJournal(file).append(b) // after the crash: the cut line goes first
        assertEquals(listOf(a, b), journal.entries())
    }

    @Test
    fun rewriteReplacesTheWholeFile() {
        journal.append(a)
        journal.append(b)
        journal.rewrite(listOf(b))
        assertEquals(listOf(b), journal.entries())
        assertFalse(File(file.path + ".new").exists())
        journal.clear()
        assertFalse(file.exists())
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun anEditBufferRecoversFromTheFile() {
        val store = object : LayerStore {
            override val fields = listOf(Field("rock", FieldType.STRING))
            override val capabilities = Capabilities(true, true, true, true)
            override fun feature(fid: Long): Feature? = null
            override fun lastFid() = -1L
            override fun features() = emptyList<Feature>()
            override fun add(geometry: JsonObject?, properties: JsonObject) = 1L
            override fun update(fid: Long, properties: JsonObject, setGeometry: Boolean, geometry: JsonObject?) {}
            override fun delete(fid: Long) {}
            override fun begin() {}
            override fun commit() {}
            override fun rollback() {}
        }
        EditBuffer(store, journal).add(null, Json.parseToJsonElement("""{"rock": "花岗岩"}""").jsonObject)
        val again = EditBuffer(store, FileJournal(file))
        assertEquals(Recovery.RESTORED, again.recover())
        assertEquals(JsonPrimitive("花岗岩"), again.features().single().properties["rock"])
    }
}
