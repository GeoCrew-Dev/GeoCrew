package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PhotoGeotagTest {
    private val t0 = Instant.parse("2026-11-24T02:00:00Z")
    private fun fix(sec: Long, lng: Double, lat: Double, alt: Double? = null) = TrackPoint(LngLat(lng, lat, alt), t0.plusSeconds(sec))

    // A walk east with a fix every 10 s, then a 30-minute lunch break, then on again.
    private val track = listOf(fix(0, 116.0, 40.0, 100.0), fix(10, 116.001, 40.0, 110.0), fix(20, 116.002, 40.0)) +
        listOf(fix(1820, 116.010, 40.0), fix(1830, 116.011, 40.0))

    @Test fun interpolatesBetweenCloseFixes() {
        val m = PhotoGeotag.place(t0.plusSeconds(5), mapOf("t" to track.shuffled()))!!
        assertEquals(116.0005, m.position.lng, 1e-9)
        assertEquals(105.0, m.position.alt!!, 1e-9)
        assertEquals(Duration.ZERO, m.gap)
        assertEquals("t", m.trackId)
    }

    @Test fun correctsTheCameraClock() {
        // The camera runs 1 minute fast: a photo stamped 02:01:05 was taken at 02:00:05.
        val m = PhotoGeotag.place(t0.plusSeconds(65), mapOf("t" to track), offset = Duration.ofMinutes(1))!!
        assertEquals(116.0005, m.position.lng, 1e-9)
    }

    @Test fun usesTheNearFixOnlyAcrossABreak() {
        val soon = PhotoGeotag.place(t0.plusSeconds(80), mapOf("t" to track))!!
        assertEquals(116.002, soon.position.lng, 1e-9)
        assertEquals(Duration.ofSeconds(60), soon.gap)
        assertNull(PhotoGeotag.place(t0.plusSeconds(900), mapOf("t" to track)))
        // A little before the first fix still counts; long before does not.
        assertEquals(116.0, PhotoGeotag.place(t0.minusSeconds(30), mapOf("t" to track))!!.position.lng, 1e-9)
        assertNull(PhotoGeotag.place(t0.minusSeconds(600), mapOf("t" to track)))
    }

    @Test fun picksTheClosestTrack() {
        val other = listOf(fix(1700, 117.0, 41.0), fix(1810, 117.1, 41.0))
        val m = PhotoGeotag.place(t0.plusSeconds(1815), mapOf("t" to track, "o" to other))!!
        assertEquals("t", m.trackId)
        assertEquals(Duration.ofSeconds(5), m.gap)
        assertNull(PhotoGeotag.place(t0, emptyMap()))
    }
}
