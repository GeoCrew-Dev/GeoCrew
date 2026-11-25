package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AttachmentsTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    private fun feature(edit: PropertiesBuilder.() -> Unit) = GeoFeature(
        id = "f", projectId = "p", kind = FeatureKind.POINT, geometry = null,
        properties = buildProperties(edit), createdAt = Instant.EPOCH, sync = SyncInfo(updatedAt = Instant.EPOCH),
    )

    @Test
    fun listsEveryKindOnceAndSkipsJunk() {
        val f = feature {
            put(Props.PHOTOS, JsonArray(listOf(JsonPrimitive(a), JsonPrimitive("../etc"), JsonPrimitive(a))))
            strings(Props.AUDIO, listOf(b, a))
        }
        assertEquals(listOf(AttachmentRef(a, AttachmentKind.PHOTO), AttachmentRef(b, AttachmentKind.AUDIO)), f.attachments)
        assertEquals(listOf(b, a), f.audio)
    }

    @Test
    fun audioInfoRoundTrips() {
        val t = Instant.parse("2026-11-24T10:41:00Z")
        val f = feature { audioInfo(b, AudioInfo(42.54, t, LngLat(116.391, 39.907))) }
        val info = f.audioInfo.getValue(b)
        assertEquals(42.5, info.durationSeconds!!, 1e-9)
        assertEquals(t, info.recordedAt)
        assertEquals(39.907, info.position!!.lat, 1e-9)
        val cleared = f.copy(properties = f.properties.edit { audioInfo(b, null) })
        assertNull(cleared.properties[Props.AUDIO_INFO])
        assertTrue(cleared.audioInfo.isEmpty())
    }

    @Test
    fun clockFormatsLengths() {
        assertEquals("0:07", AudioInfo.clock(6.6))
        assertEquals("1:05", AudioInfo.clock(65.0))
        assertEquals("1:00:00", AudioInfo.clock(3600.0))
    }
}
