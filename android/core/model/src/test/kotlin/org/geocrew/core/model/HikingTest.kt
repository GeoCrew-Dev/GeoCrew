package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HikingTest {
    @Test
    fun toblerIsFastestOnAGentleDescent() {
        assertEquals(5.04, Hiking.speedKmh(0.0), 0.01)
        assertEquals(6.0, Hiking.speedKmh(-0.05), 1e-9)
        assertTrue(Hiking.speedKmh(0.3) < Hiking.speedKmh(-0.3))
    }

    @Test
    fun climbTakesLongerThanTheWayDown() {
        val line = Hiking.densify(listOf(LngLat(117.0, 40.0), LngLat(117.0, 40.018)), 30.0) // about 2 km north
        val up = line.indices.map { 100.0 + 300.0 * it / (line.size - 1) }
        val climb = Hiking.estimate(line, up)
        val back = Hiking.estimate(line.reversed(), up.reversed())
        assertEquals(2000.0, climb.distance, 20.0)
        assertEquals(300.0, climb.ascent, 1.0)
        assertEquals(300.0, back.descent, 1.0)
        assertTrue(climb.hours > back.hours)
        val flat = Hiking.estimate(line, line.map { 100.0 })
        assertEquals(2.0 / 5.04, flat.hours, 0.01)
        assertEquals(flat.hours / Hiking.OFF_PATH, Hiking.estimate(line, line.map { 100.0 }, Hiking.OFF_PATH).hours, 1e-9)
    }

    @Test
    fun gapsAndNoiseAreHandled() {
        val line = Hiking.densify(listOf(LngLat(117.0, 40.0), LngLat(117.0, 40.009)), 30.0)
        val noisy = line.indices.map { if (it == 3) null else 100.0 + if (it % 2 == 0) 1.0 else -1.0 }
        val e = Hiking.estimate(line, noisy)
        assertTrue(e.missingHeights)
        assertEquals(0.0, e.ascent, 1e-9)
        assertTrue(Hiking.estimate(line, line.map { null }).missingHeights)
        assertEquals("约 40 分钟", Hiking.format(0.66))
        assertEquals("约 2 小时 15 分", Hiking.format(2.25))
    }
}
