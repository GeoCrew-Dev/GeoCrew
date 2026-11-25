package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TrackTimesTest {
    private val t0 = Instant.parse("2026-11-24T02:00:00Z")
    private val line = Geometry.LineString(listOf(LngLat(116.0, 40.0), LngLat(116.001, 40.0), LngLat(116.002, 40.0)))

    private fun track(props: PropertiesBuilder.() -> Unit) = GeoFeature(
        id = "t", projectId = "p", kind = FeatureKind.TRACK, geometry = line,
        properties = buildProperties { this[Props.STARTED_AT] = t0.toString(); props() },
        createdAt = t0, sync = SyncInfo(updatedAt = t0),
    )

    @Test fun writesSecondsSinceTheStartAndReadsThemBack() {
        val times = listOf(t0.plusSeconds(3), t0.plusSeconds(13), t0.plusMillis(23_900))
        val f = track { TrackTimes.write(this, t0, times) }
        assertEquals(JsonArray(listOf(3, 13, 23).map(::JsonPrimitive)), f.properties[Props.TIMES_S])
        assertEquals(listOf(t0.plusSeconds(3), t0.plusSeconds(13), t0.plusSeconds(23)), TrackTimes.of(f))
        val fixes = TrackTimes.fixes(f)!!
        assertEquals(line.positions, fixes.map { it.position })
        // Synced fixes place a photo like the recorder's own.
        assertEquals(116.0005, PhotoGeotag.place(t0.plusSeconds(8), mapOf("t" to fixes))!!.position.lng, 1e-9)
    }

    @Test fun aClockSteppedBackDoesNotReverseTheList() {
        val f = track { TrackTimes.write(this, t0, listOf(t0.plusSeconds(10), t0.plusSeconds(5), t0.plusSeconds(20))) }
        assertEquals(JsonArray(listOf(10, 10, 20).map(::JsonPrimitive)), f.properties[Props.TIMES_S])
    }

    @Test fun unusableTimesAreIgnored() {
        fun with(vararg v: Any) = track { put(Props.TIMES_S, JsonArray(v.map { if (it is String) JsonPrimitive(it) else JsonPrimitive(it as Number) })) }
        assertNull(TrackTimes.of(with(0, 10)))           // one short of the coordinates
        assertNull(TrackTimes.of(with(0, 10, 5)))        // goes backwards
        assertNull(TrackTimes.of(with(0, "10", 20)))     // not a number
        assertNull(TrackTimes.of(with(0, 1.5, 20)))      // not whole seconds
        assertNull(TrackTimes.of(track { }))             // none at all
        val noStart = track { put(Props.TIMES_S, JsonArray(listOf(0, 1, 2).map(::JsonPrimitive))) }
            .let { it.copy(properties = it.properties.edit { remove(Props.STARTED_AT) }) }
        assertNull(TrackTimes.of(noStart))
    }

    @Test fun fewerThanTwoTimesRemoveTheKey() {
        val f = track { put(Props.TIMES_S, JsonArray(listOf(JsonPrimitive(1)))); TrackTimes.write(this, t0, listOf(t0)) }
        assertNull(f.properties[Props.TIMES_S])
    }
}
