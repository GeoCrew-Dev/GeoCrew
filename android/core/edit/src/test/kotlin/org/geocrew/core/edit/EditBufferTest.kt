package org.geocrew.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class EditBufferTest {
    private val store = FakeStore()
    private val journal = MemoryJournal()
    private val buffer = EditBuffer(store, journal)

    private fun rocks(b: EditBuffer = buffer) = b.features().map { (it.properties["rock"] as JsonPrimitive).content }

    @Test
    fun editsWaitForSave() {
        val a = store.put("a")
        val b = store.put("b")
        val new = buffer.add(point(5, 5), json("""{"rock": "c"}""")).fid
        buffer.changeValues(a, json("""{"rock": "a2", "dip": 30}"""))
        buffer.changeGeometry(a, point(1, 1))
        buffer.delete(b)
        assertEquals(-1, new)
        assertEquals(listOf("a2", "c"), rocks())
        assertNull(buffer.feature(b))
        assertEquals(point(1, 1), buffer.feature(a)!!.geometry)
        assertEquals(JsonNull, buffer.feature(new)!!.properties["dip"], "a new feature has every field")
        assertEquals("a", (store.rows.getValue(a).properties["rock"] as JsonPrimitive).content, "nothing is written yet")
        assertTrue(buffer.isModified)

        val ids = buffer.save()
        assertEquals(listOf("update", "delete", "add", "commit"), store.writes, "the QGIS order, in one transaction")
        assertEquals(mapOf(-1L to 3L), ids)
        assertEquals(listOf("a2", "c"), store.rows.values.map { (it.properties["rock"] as JsonPrimitive).content })
        assertEquals(JsonPrimitive(30), store.rows.getValue(a).properties["dip"])
        assertFalse(buffer.isModified)
        assertTrue(journal.entries().isEmpty())
        assertEquals(listOf("a2", "c"), rocks())
    }

    @Test
    fun onlyChangedValuesAreWritten() {
        val a = store.put("a")
        buffer.changeValues(a, json("""{"dip": 12.5}"""))
        store.rows[a] = store.rows.getValue(a).copy(properties = JsonObject(store.rows.getValue(a).properties + ("rock" to JsonPrimitive("someone else"))))
        buffer.save()
        assertEquals(JsonPrimitive("someone else"), store.rows.getValue(a).properties["rock"])
        assertEquals(point(0, 0), store.rows.getValue(a).geometry, "the geometry was not touched")
        buffer.changeGeometry(a, null)
        buffer.save()
        assertNull(store.rows.getValue(a).geometry)
    }

    @Test
    fun revertAndReapplyWalkTheEdits() {
        val a = store.put("a")
        val start = buffer.features()
        val edits = listOf(
            buffer.changeValues(a, json("""{"rock": "x"}""")),
            buffer.add(point(1, 2), json("""{"rock": "new"}""")),
            buffer.changeValues(-1, json("""{"dip": 5}""")),
            buffer.delete(a),
        )
        val end = buffer.features()
        edits.reversed().forEach(buffer::revert)
        assertEquals(start, buffer.features())
        assertFalse(buffer.isModified)
        edits.forEach(buffer::reapply)
        assertEquals(end, buffer.features())
        assertFailsWith<IllegalStateException>("out of order") { buffer.revert(edits[0]) }
        assertFailsWith<IllegalStateException> { buffer.reapply(edits[3]) }
    }

    @Test
    fun deletingANewFeatureWritesNothing() {
        val e = buffer.add(point(0, 0))
        buffer.delete(e.fid)
        assertFalse(buffer.isModified)
        assertEquals(emptyMap(), buffer.save())
        assertTrue(store.writes.isEmpty())
        assertFailsWith<IllegalArgumentException> { buffer.changeValues(e.fid, json("""{"rock": "x"}""")) }
    }

    @Test
    fun aFailedTransactionKeepsEveryEdit() {
        val a = store.put("a")
        buffer.changeValues(a, json("""{"rock": "a2"}"""))
        buffer.add(point(1, 1), json("""{"rock": "b"}"""))
        buffer.add(point(2, 2), json("""{"rock": "c"}"""))
        val view = buffer.features()
        val before = store.rows.toMap()
        store.failOn = "commit"
        assertFailsWith<IllegalStateException> { buffer.save() }
        assertEquals(before, store.rows.toMap(), "rolled back")
        assertEquals(view, buffer.features())
        store.failOn = null
        assertEquals(mapOf(-1L to 2L, -2L to 3L), buffer.save(), "fids from the rolled-back try are not used up")
        assertEquals(listOf("a2", "b", "c"), rocks())
    }

    @Test
    fun withoutTransactionsWhatWasWrittenLeavesTheBuffer() {
        val store = FakeStore(Capabilities(add = true, update = true, delete = true, transactions = false))
        val buffer = EditBuffer(store, journal)
        val a = store.put("a")
        buffer.changeValues(a, json("""{"rock": "a2"}"""))
        buffer.add(point(1, 1), json("""{"rock": "b"}"""))
        store.failOn = "add"
        assertFailsWith<IllegalStateException> { buffer.save() }
        assertEquals(listOf("b"), rocks(buffer).drop(1), "the failed add is still there")
        assertEquals("a2", (store.rows.getValue(a).properties["rock"] as JsonPrimitive).content, "the update was written")
        store.failOn = null
        buffer.save()
        assertEquals(listOf("a2", "b"), store.rows.values.map { (it.properties["rock"] as JsonPrimitive).content })
        assertEquals(listOf("update", "add"), store.writes, "the update is not written twice")
    }

    @Test
    fun recoversUnsavedEditsAfterACrash() {
        val a = store.put("a")
        val b = store.put("b")
        buffer.changeValues(a, json("""{"rock": "a2"}"""))
        val gone = buffer.add(point(1, 1), json("""{"rock": "undone"}"""))
        buffer.revert(gone)
        buffer.add(point(2, 2), json("""{"rock": "c"}"""))
        buffer.delete(b)
        val view = buffer.features()

        val restarted = EditBuffer(store, journal)
        assertEquals(Recovery.RESTORED, restarted.recover())
        assertEquals(view, restarted.features())
        assertEquals(-3, restarted.add(point(0, 0)).fid, "new fids go on below the recovered ones")
        assertEquals(Recovery.NOTHING, EditBuffer(FakeStore(), MemoryJournal()).recover())
    }

    @Test
    fun aSaveThatWentThroughIsNotDoneTwice() {
        val a = store.put("a")
        buffer.changeValues(a, json("""{"rock": "a2"}"""))
        buffer.add(point(1, 1), json("""{"rock": "b"}"""))
        val crashed = journal.entries()
        buffer.save()
        // The app stopped after the commit, before the journal was cleared.
        journal.rewrite(crashed + JournalEntry.Saving(a))
        val restarted = EditBuffer(store, journal)
        assertEquals(Recovery.SAVED, restarted.recover())
        assertFalse(restarted.isModified)
        assertTrue(journal.entries().isEmpty())
        assertEquals(2, store.rows.size)
    }

    @Test
    fun aSaveThatDidNotGoThroughIsKept() {
        val a = store.put("a")
        buffer.add(point(1, 1), json("""{"rock": "b"}"""))
        journal.append(JournalEntry.Saving(store.lastFid())) // stopped before the commit
        val restarted = EditBuffer(store, journal)
        assertEquals(Recovery.RESTORED, restarted.recover())
        assertEquals(listOf("a", "b"), rocks(restarted))
        // Compared without the edit times, which come from the clock.
        val compacted = journal.entries().map { e -> (e as JournalEntry.Set).let { it.copy(slot = it.slot.copy(times = emptyMap(), geometryTime = null)) } }
        assertEquals(listOf(JournalEntry.Set(-1, Slot(added = restarted.feature(-1)))), compacted, "the journal is compacted")
        assertEquals(a, store.lastFid())
    }

    @Test
    fun withoutTransactionsAnInterruptedSaveKeepsWhatIsLeft() {
        val store = FakeStore(Capabilities(add = true, update = true, delete = true, transactions = false))
        val buffer = EditBuffer(store, journal)
        val a = store.put("a")
        val b = store.put("b")
        buffer.changeValues(a, json("""{"rock": "a2"}"""))
        buffer.delete(b)
        buffer.add(point(1, 1), json("""{"rock": "c"}"""))
        buffer.add(point(2, 2), json("""{"rock": "d"}"""))
        val mark = JournalEntry.Saving(store.lastFid())
        val entries = journal.entries()
        // The app stopped after writing the update, the deletion and the first new feature.
        store.update(a, json("""{"rock": "a2"}"""), false, null)
        store.delete(b)
        store.add(point(1, 1), json("""{"rock": "c"}"""))
        journal.rewrite(entries + mark)
        val restarted = EditBuffer(store, journal)
        assertEquals(Recovery.RESTORED, restarted.recover())
        restarted.save()
        assertEquals(listOf("a2", "c", "d"), store.rows.values.map { (it.properties["rock"] as JsonPrimitive).content })
    }

    @Test
    fun valuesMustFitTheirFields() {
        val a = store.put("a")
        for (bad in listOf(
            """{"colour": "red"}""", """{"dip": "45a"}""", """{"n": 1.5}""", """{"n": 3000000000}""", """{"rock": true}""",
            """{"ok": 1}""", """{"dip": 1e400}""", """{"day": "26/11/2026"}""", """{"at": "noon"}""", """{"blob": "00ff"}""", """{"rock": [1]}""",
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { buffer.changeValues(a, json(bad)) }
            assertFailsWith<IllegalArgumentException>(bad) { buffer.add(null, json(bad)) }
        }
        assertTrue(journal.entries().isEmpty(), "nothing refused reaches the journal")
        buffer.changeValues(
            a,
            json(
                """{"rock": 12, "dip": 3, "n": 2.0, "big": 9007199254740993, "ok": false, "day": "2026-11-26",
                   "at": "2026-11-26T08:30:15+08:00", "blob": null}""",
            ),
        )
        assertFailsWith<IllegalArgumentException> { buffer.changeValues(99, json("""{"rock": "x"}""")) }
    }

    @Test
    fun theDriverDecidesWhatCanBeEdited() {
        val store = FakeStore(Capabilities(add = false, update = false, delete = false, transactions = false))
        val buffer = EditBuffer(store, journal)
        val a = store.put("a")
        assertFailsWith<IllegalArgumentException> { buffer.add(point(0, 0)) }
        assertFailsWith<IllegalArgumentException> { buffer.changeValues(a, json("""{"rock": "x"}""")) }
        assertFailsWith<IllegalArgumentException> { buffer.changeGeometry(a, null) }
        assertFailsWith<IllegalArgumentException> { buffer.delete(a) }
        assertFalse(buffer.isModified)
    }

    @Test
    fun editsToFeaturesThatHaveGoneAreDropped() {
        val a = store.put("a")
        val b = store.put("b")
        val c = store.put("c")
        buffer.changeValues(a, json("""{"rock": "a2"}"""))
        buffer.delete(b)
        buffer.changeValues(c, json("""{"rock": "c2"}"""))
        store.rows.remove(a) // another program, or a delete that an interrupted save already made
        store.rows.remove(b)
        buffer.save()
        assertEquals(listOf("update", "commit"), store.writes)
        assertEquals(listOf("c2"), rocks())
        assertFalse(buffer.isModified)
    }

    @Test
    fun discardDropsEverything() {
        val a = store.put("a")
        buffer.changeValues(a, json("""{"rock": "x"}"""))
        buffer.add(point(0, 0))
        buffer.discard()
        assertFalse(buffer.isModified)
        assertTrue(journal.entries().isEmpty())
        assertEquals(listOf("a"), rocks())
    }

    @Test
    fun journalLinesAreJson() {
        val entries = listOf(
            JournalEntry.Set(-1, Slot(added = Feature(-1, point(1.5, 2), json("""{"rock": "花岗岩"}""")))),
            JournalEntry.Set(4, Slot(geometryChanged = true, values = json("""{"dip": 30}"""))),
            JournalEntry.Set(5, Slot.EMPTY),
            JournalEntry.Saving(12),
            JournalEntry.Failed,
        )
        val lines = entries.map { Json.encodeToString(JournalEntry.serializer(), it) }
        assertEquals("""{"type":"set","fid":5,"slot":{}}""", lines[2])
        assertEquals(entries, lines.map { Json.decodeFromString(JournalEntry.serializer(), it) })
    }
}
