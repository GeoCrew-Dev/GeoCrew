package org.geocrew.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Sync sends each field with when it was edited (ADR 0008): the buffer keeps the times, and a save hands them on.
class EditTimesTest {
    private var now = 1_000L
    private val store = FakeStore()
    private val journal = MemoryJournal()
    private val buffer = EditBuffer(store, journal) { now }

    private fun values(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun aNewFeatureGoesWithEveryFieldAndTheTimesOfThoseGiven() {
        buffer.add(point(1, 2), values("rock" to "granite"))
        buffer.save()
        val saved = store.recorded.single()
        assertTrue(saved.added)
        assertEquals(JsonPrimitive("granite"), saved.values["rock"])
        assertEquals(JsonNull, saved.values["dip"], "every field, null when not given")
        assertEquals(mapOf("rock" to 1_000L), saved.times)
        assertEquals(1_000L, saved.geometryTime)
        assertEquals(point(1, 2), saved.geometry)
    }

    @Test
    fun aChangedFeatureGoesWithWhatChangedAndWhen() {
        val fid = store.put("granite")
        now = 2_000
        buffer.changeValues(fid, values("rock" to "gneiss"))
        now = 3_000
        buffer.changeGeometry(fid, point(5, 5))
        now = 3_500
        buffer.changeValues(fid, values("rock" to "schist"))
        buffer.save()
        val saved = store.recorded.single()
        assertEquals(Saved(fid, values = values("rock" to "schist"), geometrySet = true, geometry = point(5, 5), times = mapOf("rock" to 3_500L), geometryTime = 3_000L), saved)
    }

    @Test
    fun attachmentsGoWithWhatTheFeatureHadBefore() {
        val photo = Attachment("0b6f1c3e-5d2a-4e8b-9f10-2a3b4c5d6e7f", "a".repeat(64), "image/jpeg", 10, "D001.jpg")
        val sketch = Attachment("1c7a2d4f-6e3b-4f9c-8a21-3b4c5d6e7f80", "b".repeat(64), "image/png", 20, "sketch.png")
        val made = buffer.add(point(1, 2), values("rock" to "granite"), listOf(photo)).fid
        val fid = buffer.save().getValue(made)
        assertEquals(listOf(photo), store.recorded.single().attachments, "a new feature's, none before")
        assertEquals(emptyList(), store.recorded.single().attachmentsBefore)
        store.recorded.clear()

        buffer.changeAttachments(fid, listOf(sketch))
        buffer.save()
        val saved = store.recorded.single()
        assertEquals(listOf(sketch), saved.attachments)
        assertEquals(listOf(photo), saved.attachmentsBefore)
        store.recorded.clear()

        buffer.changeValues(fid, values("rock" to "gneiss"))
        buffer.save()
        assertEquals(null, store.recorded.single().attachments, "attachments not changed: none said")
    }

    @Test
    fun aDeletionGoesWithItsTime() {
        val fid = store.put("granite")
        now = 4_000
        buffer.delete(fid)
        buffer.save()
        assertEquals(Saved(fid, deleted = true, deletedTime = 4_000L), store.recorded.single())
    }

    @Test
    fun theTimesOutliveACrash() {
        val fid = store.put("granite")
        now = 5_000
        buffer.changeValues(fid, values("rock" to "gneiss"))
        val restarted = EditBuffer(store, journal) { 9_999L }
        assertEquals(Recovery.RESTORED, restarted.recover())
        restarted.save()
        assertEquals(mapOf("rock" to 5_000L), store.recorded.single().times)
    }

    @Test
    fun whatSyncWouldSendFailsWithTheSave() {
        val fid = store.put("granite")
        buffer.changeValues(fid, values("rock" to "gneiss"))
        store.failOn = "saved"
        assertFailsWith<IllegalStateException> { buffer.save() }
        assertTrue(buffer.isModified, "the edit is kept")
        assertEquals(JsonPrimitive("granite"), store.feature(fid)!!.properties["rock"], "nothing written")
    }
}
