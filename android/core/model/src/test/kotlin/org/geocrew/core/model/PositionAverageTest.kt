package org.geocrew.core.model

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class PositionAverageTest {
    private val base = LngLat(116.0, 40.0, 50.0)
    private fun east(m: Double) = Geodesy.destination(base, 90.0, m)

    @Test
    fun weighsBetterFixesMore() {
        val avg = PositionAverage()
        avg.add(base, 3.0)
        avg.add(east(10.0), 6.0)
        val r = avg.result()!!
        // Weights 1/9 and 1/36: the mean sits a fifth of the way east.
        assertEquals(2.0, Geodesy.distance(base, r.position), 0.05)
        assertEquals(2, r.used.toLong())
        assertEquals(4.5, r.accuracy!!, 1e-9)
    }

    @Test
    fun dropsWildFixesAndReportsSpread() {
        val avg = PositionAverage(maxAccuracy = 20.0)
        assertTrue(!avg.add(east(500.0), 80.0))
        avg.add(east(-1.0), 4.0)
        avg.add(east(1.0), 4.0)
        val r = avg.result()!!
        assertEquals(0.0, Geodesy.distance(base, r.position), 0.05)
        assertEquals(1.0, r.spread, 0.05)
        assertEquals(3, avg.seen.toLong())
        assertEquals(2, r.used.toLong())
    }

    @Test
    fun emptyUntilFirstUsableFix() {
        val avg = PositionAverage()
        assertNull(avg.result())
        avg.add(LngLat(116.0, 40.0), null)
        val r = avg.result()!!
        assertNull(r.accuracy)
        assertNull(r.position.alt)
    }
}
