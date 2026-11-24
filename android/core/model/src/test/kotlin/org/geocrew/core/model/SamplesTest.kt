package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SamplesTest {
    private fun feature(props: JsonObject) = GeoFeature(
        id = "f", projectId = "p", kind = FeatureKind.GEO_POINT, geometry = null, properties = props,
        createdAt = Instant.EPOCH, sync = SyncInfo(updatedAt = Instant.EPOCH),
    )

    @Test
    fun roundTripKeepsUnknownKeysAndSkipsBadEntries() {
        val props = buildProperties {
            put(
                Props.SAMPLES,
                JsonArray(
                    listOf(
                        JsonObject(mapOf("no" to JsonPrimitive("D012-1"), "type" to JsonPrimitive("薄片"), "lab_id" to JsonPrimitive("L-88"))),
                        JsonObject(mapOf("type" to JsonPrimitive("化学"))),
                        JsonPrimitive("junk"),
                    ),
                ),
            )
        }
        val s = feature(props).samples
        assertEquals(listOf("D012-1"), s.map { it.no })
        val edited = props.edit { samples(listOf(s[0].copy(note = "风化面"))) }
        val o = (edited[Props.SAMPLES] as JsonArray)[0] as JsonObject
        assertEquals("L-88", o.string("lab_id"))
        assertEquals("风化面", o.string("note"))
        assertNull(props.edit { samples(emptyList()) }[Props.SAMPLES])
    }

    @Test
    fun numbersFollowTheStationAndSkipTakenOnes() {
        assertEquals("D012-1", SampleTypes.nextNumber("D012", emptyList()))
        assertEquals("D012-3", SampleTypes.nextNumber(" D012 ", listOf("d012-1", "D012-2 ")))
        assertEquals("S-1", SampleTypes.nextNumber(null, listOf("D012-1")))
    }
}
