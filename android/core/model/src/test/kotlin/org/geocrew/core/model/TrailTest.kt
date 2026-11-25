package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TrailTest {
    private val t0 = Instant.parse("2026-11-24T08:00:00Z")
    private fun pt(dLat: Double, s: Long) = TrailPoint(LngLat(116.0, 40.0 + dLat), t0.plusSeconds(s))

    @Test fun keepsSpacedRecentPlausibleFixes() {
        val b = TrailBuffer()
        var m = emptyMap<String, List<TrailPoint>>()
        m = b.add(m, "a", pt(0.0, 0))
        m = b.add(m, "a", pt(0.00001, 10)) // ~1 m: too close
        m = b.add(m, "a", pt(0.0001, 20)) // ~11 m: kept
        m = b.add(m, "a", pt(0.0002, 15)) // out of order
        m = b.add(m, "a", pt(0.05, 30)) // 5.5 km in 10 s: a jump
        m = b.add(m, "a", pt(0.0003, 40))
        assertEquals(listOf(0L, 20L, 40L), m["a"]!!.map { it.at.epochSecond - t0.epochSecond })
        // Half an hour later the old fixes fall away.
        m = b.add(m, "a", pt(0.001, 40 + 31 * 60))
        assertEquals(1, m["a"]!!.size)
        assertTrue(b.prune(m, t0.plusSeconds(3 * 3600)).isEmpty())
    }

    @Test fun capsLength() {
        val b = TrailBuffer(maxPoints = 3)
        var m = emptyMap<String, List<TrailPoint>>()
        for (i in 0 until 6) m = b.add(m, "a", pt(i * 0.001, i * 60L))
        assertEquals(listOf(180L, 240L, 300L), m["a"]!!.map { it.at.epochSecond - t0.epochSecond })
    }
}
