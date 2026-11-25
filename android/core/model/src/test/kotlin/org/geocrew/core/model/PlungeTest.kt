package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlungeTest {
    @Test
    fun phoneAxisGivesTheDownwardEndWhicheverWayItPoints() {
        // Pointing east and 30° down, or west and 30° up: the same line.
        val c = Math.cos(Math.toRadians(30.0))
        val s = Math.sin(Math.toRadians(30.0))
        val a = Plunge.fromVector(c, 0.0, -s)
        val b = Plunge.fromVector(-c, 0.0, s)
        assertEquals(90.0, a.trend, 1e-9)
        assertEquals(30.0, a.plunge, 1e-9)
        assertEquals(a.trend, b.trend, 1e-9)
        assertEquals(a.plunge, b.plunge, 1e-9)
        // Declination turns magnetic trends to true ones.
        assertEquals(85.0, Plunge.fromVector(c, 0.0, -s, declination = -5.0).trend, 1e-9)
        assertEquals("090°∠30°", a.format())
    }

    @Test
    fun meanTreatsOppositeReadingsAsTheSameLine() {
        val m = Plunge.mean(listOf(Plunge.of(0.0, 2.0), Plunge.of(180.0, 2.0), Plunge.of(0.0, 0.0)))!!
        // Near-horizontal north–south readings average to a near-horizontal N–S line.
        assertEquals(0.0, Math.min(m.trend, 360 - m.trend).let { Math.min(it, Math.abs(180 - m.trend)) }, 1.0)
        assertEquals(0.0, m.plunge, 2.1)
        assertNull(Plunge.mean(emptyList()))
        assertEquals(0.0, Plunge.spread(listOf(Plunge.of(10.0, 20.0)), Plunge.of(10.0, 20.0)), 1e-6)
    }

    @Test
    fun negativePlungeFlipsTheTrend() {
        val p = Plunge.of(45.0, -20.0)
        assertEquals(225.0, p.trend, 1e-9)
        assertEquals(20.0, p.plunge, 1e-9)
    }

    @Test
    fun rakeAndFitOnAPlane() {
        // Plane dipping 45° to the east; strike (right-hand) is 0° (north).
        val plane = Attitude.of(90.0, 45.0)
        // Its dip line: trend 90, plunge 45 lies in the plane with rake 90.
        val dipLine = Plunge.of(90.0, 45.0)
        assertEquals(0.0, dipLine.offPlane(plane), 1e-6)
        assertEquals(90.0, dipLine.rakeOn(plane)!!, 1e-6)
        // Its strike line, horizontal to the north, has rake 0.
        assertEquals(0.0, Plunge.of(0.0, 0.0).rakeOn(plane)!!, 1e-6)
        // A vertical line is 45° off the plane.
        assertEquals(45.0, Plunge.of(0.0, 90.0).offPlane(plane), 1e-6)
    }

    @Test
    fun readsAndWritesProperties() {
        val props = buildProperties { Plunge.of(235.04, 30.0).writeTo(this) }
        assertEquals(Plunge(235.0, 30.0), Plunge.fromProperties(props))
        assertNull(Plunge.fromProperties(buildProperties { this[Props.TREND] = 10.0; this[Props.PLUNGE] = 95.0 }))
        assertNull(Plunge.fromProperties(buildProperties { this[Props.TREND] = 10.0 }))
    }
}
