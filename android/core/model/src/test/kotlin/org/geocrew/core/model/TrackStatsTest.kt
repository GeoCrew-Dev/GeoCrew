package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TrackStatsTest {
    private val t0 = Instant.parse("2026-11-24T01:00:00Z")
    private val metreLng = 1.0 / 85_000 // about a metre of longitude at 40°N
    private fun fix(sec: Long, eastM: Double) = TrackPoint(LngLat(116.0 + eastM * metreLng, 40.0), t0.plusSeconds(sec))

    // Walk east 1 m/s for 10 min, stand at an outcrop for 8 min (drifting a few metres),
    // walk on 5 min, pause recording for 30 min, walk 2 more minutes.
    private val track = buildList {
        for (s in 0..600 step 10) add(fix(s.toLong(), s.toDouble()))
        for (s in 610..1080 step 10) add(fix(s.toLong(), 600.0 + (s % 30) / 10.0))
        for (s in 1090..1380 step 10) add(fix(s.toLong(), 600.0 + (s - 1080)))
        for (s in 3180..3300 step 10) add(fix(s.toLong(), 900.0 + (s - 3180)))
    }

    @Test fun findsTheStopAndTheGap() {
        val st = TrackStats.of(track.shuffled())!!
        assertEquals(t0, st.start)
        assertEquals(Duration.ofSeconds(3300), st.total)
        assertEquals(1, st.stops.size)
        val stop = st.stops.single()
        // The stop starts at the last walking fix that is within 30 m of it.
        assertTrue(stop.duration >= Duration.ofMinutes(8) && stop.duration <= Duration.ofMinutes(9))
        assertEquals(116.0 + 600 * metreLng, stop.position.lng, 20 * metreLng)
        assertEquals(Duration.ofSeconds(1800), st.unrecorded)
        assertEquals(st.total - stop.duration - st.unrecorded, st.moving)
        // About 1 km walked in about 17 minutes of walking.
        assertEquals(3.6, st.movingKmh!!, 0.5)
    }

    @Test fun stopsWithNoRecordNearby() {
        val st = TrackStats.of(track)!!
        val far = GeoFeature("p", "x", FeatureKind.GEO_POINT, Geometry.Point(LngLat(116.01, 40.0)), buildProperties { }, t0, SyncInfo(updatedAt = t0))
        val near = far.copy(id = "q", geometry = Geometry.Point(LngLat(116.0 + 610 * metreLng, 40.0)))
        assertEquals(1, TrackStats.unrecordedStops(st, listOf(far)).size)
        assertTrue(TrackStats.unrecordedStops(st, listOf(far, near)).isEmpty())
        // A deleted record does not count.
        assertEquals(1, TrackStats.unrecordedStops(st, listOf(near.copy(sync = near.sync.copy(deleted = true)))).size)
    }

    @Test fun walkingOnlyHasNoStops() {
        val st = TrackStats.of(track.take(61))!!
        assertTrue(st.stops.isEmpty())
        assertEquals(Duration.ofMinutes(10), st.moving)
        assertNull(TrackStats.of(track.take(1)))
    }
}
