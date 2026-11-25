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
    fun densityPeaksAtTheClusterAndReadsAboutOnePercentWhenSpread() {
        // A tight cluster of poles plunging 60° to the north-east.
        val cluster = (0 until 30).map { i -> Lineation(45.0 + (i % 5 - 2), 60.0 + (i / 5 - 2)) }
        val d = Stereonet.density(cluster)!!
        assertTrue("max ${d.max}", d.max > 30)
        val peak = Stereonet.project(Lineation(45.0, 60.0))
        assertTrue("peak at ${d.maxAt}", hypot(d.maxAt.x - peak.x, d.maxAt.y - peak.y) < 0.08)
        assertEquals(generateSequence(1.0) { it * 2 }.takeWhile { it < d.max }.toList(), d.levels)
        assertTrue(d.lines.isNotEmpty())

        // Directions spread evenly over the lower hemisphere (golden-angle spiral): close to 1 % everywhere.
        val even = (0 until 400).map { i ->
            val down = (i + 0.5) / 400
            Lineation((i * 137.50776) % 360, Math.toDegrees(kotlin.math.asin(down)))
        }
        val e = Stereonet.density(even)!!
        assertTrue("even max ${e.max}", e.max in 1.0..2.5)
        assertNull(Stereonet.density(cluster.take(2)))
    }

    @Test
    fun densityWrapsAcrossThePrimitiveCircle() {
        // Near-horizontal poles trending north and south are the same axis.
        val axis = (0 until 20).map { i -> Lineation(if (i % 2 == 0) 0.0 + i % 3 else 180.0 - i % 3, 1.0) }
        val d = Stereonet.density(axis)!!
        assertTrue("max ${d.max}", d.max > 30)
        assertTrue("peak at ${d.maxAt}", hypot(d.maxAt.x, d.maxAt.y) > 0.9)
    }

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
