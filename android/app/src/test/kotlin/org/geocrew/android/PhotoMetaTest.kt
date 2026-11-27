package org.geocrew.android

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PhotoMetaTest {
    private val taken = Instant.parse("2026-11-27T02:50:00Z")

    @Test
    fun aPhotoSaysWhereItLookedFromTrueNorth() {
        val located = Instant.parse("2026-11-27T02:49:20.5Z")
        val meta = photoMeta(magneticHeading = 355.5f, declination = -3.2f, longitude = 114.3, latitude = 30.6, accuracy = 4.25f, taken = taken, located = located)
        val expected = """{"azimuth": 352.3, "longitude": 114.3, "latitude": 30.6, "accuracy": 4.3, "located": "2026-11-27T02:49:20Z", "taken": "2026-11-27T02:50:00Z"}"""
        assertEquals(Json.parseToJsonElement(expected).jsonObject, meta)
        fun azimuth(magnetic: Float, declination: Float?) = photoMeta(magnetic, declination, 114.3, 30.6, null, taken).let { m ->
            (m["azimuth"] ?: m.getValue("magneticAzimuth")).toString().toDouble()
        }
        assertEquals(1.5, azimuth(359.0f, 2.5f), "past north")
        assertEquals(0.0, azimuth(359.97f, null), "never 360")
        assertEquals(357.0, azimuth(1.0f, -4.0f), "back past north")
    }

    @Test
    fun withoutAPositionItKeepsMagneticNorthAndNoPlace() {
        assertEquals(Json.parseToJsonElement("""{"magneticAzimuth": 120.0, "taken": "2026-11-27T02:50:00Z"}""").jsonObject, photoMeta(120f, null, null, null, null, taken))
        assertEquals(Json.parseToJsonElement("""{"taken": "2026-11-27T02:50:00Z"}""").jsonObject, photoMeta(null, null, null, null, null, taken), "no compass")
    }
}
