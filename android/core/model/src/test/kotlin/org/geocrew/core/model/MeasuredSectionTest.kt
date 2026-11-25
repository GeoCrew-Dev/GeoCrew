package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasuredSectionTest {
    @Test
    fun levelTraverseAcrossStrikeGivesSinDip() {
        // Beds dip 30° east; walking 100 m east on level ground crosses 50 m of beds.
        val s = MeasuredSection(
            legs = listOf(TraverseLeg(90.0, 0.0, 60.0), TraverseLeg(90.0, 0.0, 40.0)),
            layers = listOf(
                SectionLayer("1", 30.0, "砂岩", Attitude.of(90.0, 30.0)),
                SectionLayer("2", 80.0, "页岩"),
                SectionLayer("3", 100.0, "灰岩", Attitude.of(90.0, 30.0)),
            ),
        )
        val r = s.compute()
        assertEquals(listOf(15.0, 25.0, 10.0), r.layers.map { Math.round(it.thickness!! * 1000) / 1000.0 })
        assertEquals(50.0, r.total, 1e-9)
        assertTrue(r.layers[1].borrowed)
        assertTrue(r.problems.isEmpty())
        assertEquals(100.0, r.profile.last().first, 1e-9)
    }

    @Test
    fun slopeWithTheDipAndProblemsAreReported() {
        // Walking down a 20° slope in the dip direction of 40° beds: t = L·sin(dip − slope).
        val s = MeasuredSection(
            legs = listOf(TraverseLeg(180.0, -20.0, 50.0)),
            layers = listOf(SectionLayer("1", 50.0, attitude = Attitude.of(180.0, 40.0)), SectionLayer("2", 40.0), SectionLayer("3", 70.0)),
        )
        val r = s.compute()
        assertEquals(50 * kotlin.math.sin(Math.toRadians(20.0)), r.layers[0].thickness!!, 1e-9)
        assertEquals(2, r.problems.size)
        assertEquals(-50 * kotlin.math.sin(Math.toRadians(20.0)), r.profile.last().second, 1e-9)
    }

    @Test
    fun noAttitudeMeansNoThickness() {
        val r = MeasuredSection(listOf(TraverseLeg(0.0, 0.0, 10.0)), listOf(SectionLayer("1", 10.0))).compute()
        assertNull(r.layers[0].thickness)
        assertEquals(1, r.problems.size)
    }

    @Test
    fun jsonRoundTripAndPath() {
        val s = MeasuredSection(
            legs = listOf(TraverseLeg(45.0, 10.0, 25.5)),
            layers = listOf(SectionLayer("P1-1", 25.5, "砾岩", Attitude.of(300.0, 55.0), "底部见冲刷面")),
        )
        assertEquals(s, MeasuredSection.fromJson(s.toJson()))
        val path = s.path(LngLat(116.0, 40.0, 100.0))
        assertEquals(2, path.size)
        assertEquals(25.5 * kotlin.math.cos(Math.toRadians(10.0)), Geodesy.distance(path[0], path[1]), 0.05)
        assertEquals(100 + 25.5 * kotlin.math.sin(Math.toRadians(10.0)), path[1].alt!!, 1e-6)
    }

    @Test
    fun profileAtInterpolatesAndClamps() {
        val s = MeasuredSection(listOf(TraverseLeg(90.0, 0.0, 10.0), TraverseLeg(90.0, 30.0, 20.0)))
        val (x1, z1) = s.profileAt(5.0)
        assertEquals(5.0, x1, 1e-9)
        assertEquals(0.0, z1, 1e-9)
        val (x2, z2) = s.profileAt(20.0)
        assertEquals(10 + 10 * kotlin.math.cos(Math.toRadians(30.0)), x2, 1e-9)
        assertEquals(10 * kotlin.math.sin(Math.toRadians(30.0)), z2, 1e-9)
        val end = s.profileAt(99.0)
        assertEquals(s.compute().profile.last().first, end.first, 1e-9)
        assertEquals(10.0, end.second, 1e-9)
    }
}
