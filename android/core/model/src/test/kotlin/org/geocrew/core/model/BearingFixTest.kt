package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BearingFixTest {
    private val target = LngLat(103.85, 30.62)
    private val a = Geodesy.destination(target, 200.0, 1500.0)
    private val b = Geodesy.destination(target, 290.0, 2200.0)
    private val c = Geodesy.destination(target, 120.0, 900.0)

    @Test fun forwardIntersectionFindsTheTarget() {
        val r = BearingFix.intersect(listOf(
            BearingFix.Ray(a, Geodesy.bearing(a, target)),
            BearingFix.Ray(b, Geodesy.bearing(b, target)),
        ))!!
        assertEquals(0.0, Geodesy.distance(r.position, target), 0.5)
        assertEquals(90.0, r.minAngle, 0.5)
        assertFalse(r.behind)
        assertEquals(1500.0, r.ranges[0], 2.0)
    }

    @Test fun threeRaysGiveMissesWhenReadingsDisagree() {
        val r = BearingFix.intersect(listOf(
            BearingFix.Ray(a, Geodesy.bearing(a, target) + 1.0), // a degree off, as a hand compass reads
            BearingFix.Ray(b, Geodesy.bearing(b, target)),
            BearingFix.Ray(c, Geodesy.bearing(c, target)),
        ))!!
        assertTrue(Geodesy.distance(r.position, target) < 26.0)
        assertTrue(r.worstMiss > 1.0 && r.worstMiss < 26.0)
    }

    @Test fun resectionFromLandmarks() {
        val me = target
        val r = BearingFix.resection(listOf(a to Geodesy.bearing(me, a), b to Geodesy.bearing(me, b)))!!
        assertEquals(0.0, Geodesy.distance(r.position, me), 0.5)
        assertFalse(r.behind)
    }

    @Test fun warnsAboutBackwardBearingsAndRefusesParallelLines() {
        val wrong = BearingFix.intersect(listOf(
            BearingFix.Ray(a, Geodesy.bearing(a, target) + 180),
            BearingFix.Ray(b, Geodesy.bearing(b, target)),
        ))!!
        assertTrue(wrong.behind)
        assertNull(BearingFix.intersect(listOf(BearingFix.Ray(a, 45.0), BearingFix.Ray(Geodesy.destination(a, 135.0, 500.0), 45.5))))
        assertNull(BearingFix.intersect(listOf(BearingFix.Ray(a, 45.0))))
    }
}
