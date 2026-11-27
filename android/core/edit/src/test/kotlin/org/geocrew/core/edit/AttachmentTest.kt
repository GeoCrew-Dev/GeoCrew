package org.geocrew.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome

// Attachments go through the edit buffer like values (ADR 0005): undone, saved with their feature, kept over a crash.
class AttachmentTest {
    private val photo = Attachment("a1", "a".repeat(64), "image/jpeg", 2048, "露头.jpg", thumb = "b".repeat(64))
    private val sketch = Attachment("a2", "c".repeat(64), "image/png", 512, "素描.png")

    @Test
    fun aNewFeatureIsSavedWithItsAttachments() {
        val store = FakeStore()
        val buffer = EditBuffer(store, MemoryJournal())
        val added = buffer.add(point(1, 2), json("""{"rock": "granite"}"""), listOf(photo, sketch))
        assertEquals(listOf(photo, sketch), buffer.attachments(added.fid))
        val ids = buffer.save()
        val stored = ids.getValue(added.fid)
        assertEquals(listOf(photo, sketch), store.attached[stored], "in their order")
        assertEquals(listOf("add", "attach", "commit"), store.writes, "in the same transaction")
    }

    @Test
    fun attachmentsChangeAndComeBack() {
        val store = FakeStore()
        val a = store.put("a")
        store.attached[a] = listOf(photo)
        val buffer = EditBuffer(store, MemoryJournal())
        val changed = buffer.changeAttachments(a, listOf(photo, sketch))
        assertEquals(listOf(photo, sketch), buffer.attachments(a))
        buffer.revert(changed)
        assertEquals(listOf(photo), buffer.attachments(a), "undone")
        buffer.reapply(changed)
        buffer.save()
        assertEquals(listOf(photo, sketch), store.attached[a])
        assertEquals(listOf("attach", "commit"), store.writes, "the feature itself is not written again")
        assertFailsWith<IllegalArgumentException> { buffer.changeAttachments(a, listOf(photo, photo)) }
    }

    @Test
    fun aDeletedFeatureTakesItsAttachments() {
        val store = FakeStore()
        val a = store.put("a")
        store.attached[a] = listOf(photo)
        val buffer = EditBuffer(store, MemoryJournal())
        buffer.delete(a)
        assertEquals(emptyList(), buffer.attachments(a))
        buffer.save()
        assertTrue(a !in store.attached)
    }

    @Test
    fun aFailedSaveKeepsThemAndACrashDoesNotLoseThem() {
        val store = FakeStore().apply { failOn = "attach" }
        val journal = MemoryJournal()
        val buffer = EditBuffer(store, journal)
        val added = buffer.add(point(0, 0), attachments = listOf(photo))
        assertFailsWith<IllegalStateException> { buffer.save() }
        assertTrue(store.rows.isEmpty(), "rolled back")
        assertEquals(listOf(photo), buffer.attachments(added.fid))

        val again = EditBuffer(store.apply { failOn = null }, journal)
        assertEquals(Recovery.RESTORED, again.recover())
        assertEquals(listOf(photo), again.attachments(added.fid), "the journal keeps them")
        val stored = again.save().getValue(added.fid)
        assertEquals(listOf(photo), store.attached[stored])
    }

    @Test
    fun filesAreNamedByTheirHashes() {
        assertFailsWith<IllegalArgumentException> { photo.copy(file = "露头.jpg") }
        assertFailsWith<IllegalArgumentException> { photo.copy(thumb = "A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { photo.copy(size = -1) }
    }

    @Test
    fun actionsGiveNewAttachmentsIdsAndMovesTakeThemAlong() = runTest {
        val points = FakeStore()
        val planes = FakeStore()
        val stores = mapOf("points" to points, "planes" to planes)
        val project = Editing(sharedScope = "project") { layer -> EditTarget(stores.getValue(layer), MemoryJournal(), "EPSG:4490") }
        val registry = ActionRegistry().apply { project.actions.forEach(::register) }
        suspend fun run(action: String, params: String) = registry.invoke(Caller.USER, action, json(params))
        run(Editing.START, """{"layer": "points"}""")
        run(Editing.START, """{"layer": "planes"}""")
        val file = "a".repeat(64)
        val added = run(Editing.ADD, """{"layer": "points", "properties": {"rock": "granite"}, "attachments": [{"file": "$file", "type": "image/jpeg", "size": 10}]}""")
        val fid = (added as Outcome.Done).output.jsonObject.getValue("fid").jsonPrimitive.long
        val first = project.attachments("points", fid)!!.single()
        assertEquals(36, first.id.length, "a UUID")
        assertIs<Outcome.Failed>(run(Editing.ATTACHMENTS, """{"layer": "points", "fid": $fid, "attachments": [{"file": "x", "type": "image/jpeg", "size": 1}]}"""))
        val kept = """{"id": "${first.id}", "file": "$file", "type": "image/jpeg", "size": 10}"""
        assertIs<Outcome.Done>(run(Editing.ATTACHMENTS, """{"layer": "points", "fid": $fid, "attachments": [$kept, {"file": "${"d".repeat(64)}", "type": "audio/mp4", "size": 5}]}"""))
        assertEquals(first.id, project.attachments("points", fid)!!.first().id, "one it had keeps its id")

        val moved = run(Editing.MOVE, """{"layer": "points", "fid": $fid, "to": "planes"}""")
        val to = (moved as Outcome.Done).output.jsonObject.getValue("fid").jsonPrimitive.long
        val carried = project.attachments("planes", to)!!
        assertEquals(listOf(file, "d".repeat(64)), carried.map { it.file })
        assertNotEquals(first.id, carried.first().id, "new attachments on the new row")
        assertIs<Outcome.Done>(registry.invoke(Caller.USER, ActionRegistry.UNDO, json("""{"scope": "project"}""")))
        assertEquals(2, project.attachments("points", fid)!!.size, "undone, they are back where they were")
    }
}
