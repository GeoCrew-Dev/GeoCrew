package org.geocrew.core.gis

import org.geocrew.core.model.Attitude
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StereonetTest {
    private fun radius(p: NetPoint) = hypot(p.x, p.y)

    @Test
    fun projectsTheCardinalCases() {
        val centre = Stereonet.project(Lineation(123.0, 90.0))
        assertEquals(0.0, radius(centre), 1e-9)
        val east = Stereonet.project(Lineation(90.0, 0.0))
        assertEquals(1.0, east.x, 1e-9)
        assertEquals(0.0, east.y, 1e-9)
        // Equal-area: 45° plunge sits at sqrt(2)·sin(22.5°) ≈ 0.541, not halfway.
        assertEquals(0.5412, radius(Stereonet.project(Lineation(0.0, 45.0))), 1e-4)
    }

    @Test
    fun poleIsOppositeTheDipDirection() {
        val pole = Stereonet.pole(Attitude.of(90.0, 30.0))
        assertEquals(270.0, pole.trend, 1e-9)
        assertEquals(60.0, pole.plunge, 1e-9)
        assertTrue(Stereonet.project(pole).x < 0)
        assertEquals(Attitude.of(90.0, 30.0), Stereonet.planeOfPole(pole))
    }

    @Test
    fun greatCircleRunsFromStrikeToStrikeThroughTheDip() {
        val a = Attitude.of(90.0, 40.0) // strikes north-south, dips east
        val c = Stereonet.greatCircle(a, steps = 90)
        assertEquals(1.0, radius(c.first()), 1e-9)
        assertEquals(1.0, radius(c.last()), 1e-9)
        assertEquals(0.0, c.first().x, 1e-9)
        val mid = c[45]
        assertEquals(0.0, mid.y, 1e-9)
        assertEquals(radius(Stereonet.project(Lineation(90.0, 40.0))), mid.x, 1e-9)
        // A vertical plane is a straight diameter.
        Stereonet.greatCircle(Attitude.of(90.0, 90.0)).forEach { assertEquals(0.0, it.x, 1e-9) }
    }

    @Test
    fun smallCircleKeepsItsAngleToTheAxis() {
        val axis = Lineation(0.0, 0.0)
        val pieces = Stereonet.smallCircle(axis, 30.0)
        assertTrue(pieces.isNotEmpty())
        pieces.flatten().forEach { assertTrue(radius(it) <= 1.0 + 1e-9) }
    }

    @Test
    fun fisherStatisticsOfAClusterAndOfOneReading() {
        val poles = listOf(Attitude.of(88.0, 40.0), Attitude.of(92.0, 42.0), Attitude.of(90.0, 38.0), Attitude.of(91.0, 40.0))
            .map(Stereonet::pole)
        assertNotNull(Stereonet.fisher(poles))
        val s = Stereonet.fisher(poles)!!
        assertEquals(4, s.count)
        val plane = Stereonet.planeOfPole(s.mean)
        assertEquals(90.0, plane.dipDirection, 1.0)
        assertEquals(40.0, plane.dip, 1.0)
        assertTrue(s.resultant > 0.99)
        assertTrue(s.kappa!! > 100)
        assertTrue(s.alpha95!! in 0.5..10.0)

        val one = Stereonet.fisher(listOf(Lineation(10.0, 20.0)))!!
        assertNull(one.kappa)
        assertNull(one.alpha95)
        assertNull(Stereonet.fisher(emptyList()))
    }
}
