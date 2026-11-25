package org.geocrew.core.gis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ContoursTest {
    @Test
    fun niceLevels() {
        assertEquals(listOf(2.0, 4.0, 6.0, 8.0), Contours.levels(1.0, 9.0, 2.0))
        val auto = Contours.levels(0.0, 1.0)
        assertTrue(auto.size in 4..10)
        assertEquals("0.3", Contours.label(Contours.levels(0.0, 1.0, 0.1)[2]))
        assertTrue(Contours.levels(0.0, 1.0, 1e-6).isEmpty())
        assertTrue(Contours.levels(3.0, 3.0).isEmpty())
    }

    @Test
    fun circleAroundAPeakIsOneClosedRing() {
        val n = 21
        val g = Array(n) { r -> DoubleArray(n) { c -> 10 - hypot(r - 10.0, c - 10.0) } }
        val lines = Contours.trace(g, 5.0)
        assertEquals(1, lines.size)
        val pts = lines[0].points
        assertEquals(pts.first(), pts.last())
        // Every vertex lies close to radius 5 from the peak.
        assertTrue(pts.all { (x, y) -> kotlin.math.abs(hypot(x - 10, y - 10) - 5) < 0.2 })
    }

    @Test
    fun noDataBreaksLinesAndARampGivesAStraightLine() {
        val g = Array(5) { DoubleArray(6) { c -> c.toDouble() } }
        val lines = Contours.trace(g, 2.5)
        assertEquals(1, lines.size)
        assertTrue(lines[0].points.all { it.first == 2.5 })
        g[2][2] = Double.NaN
        assertEquals(2, Contours.trace(g, 2.5).size)
    }
}
