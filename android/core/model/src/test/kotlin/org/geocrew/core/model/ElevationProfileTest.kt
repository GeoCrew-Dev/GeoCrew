package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationProfileTest {
    private fun path(vararg alts: Double?) = alts.mapIndexed { i, a -> LngLat(116.0 + i * 0.001, 40.0, a) }

    @Test
    fun needsTwoAltitudes() {
        assertNull(ElevationProfile.of(path(100.0, null, null)))
    }

    @Test
    fun countsClimbAndDescentAboveTheThreshold() {
        val p = ElevationProfile.of(path(100.0, 102.0, 99.0, 110.0, 130.0, 128.0, 120.0), threshold = 5.0)!!
        assertEquals(30.0, p.ascent, 1e-9)
        assertEquals(10.0, p.descent, 1e-9)
        assertEquals(99.0, p.min, 1e-9)
        assertEquals(130.0, p.max, 1e-9)
        assertEquals(Geodesy.length(path(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)), p.length, 1e-6)
    }

    @Test
    fun verticesWithoutAltitudeStillAddDistance() {
        val p = ElevationProfile.of(path(100.0, null, 120.0))!!
        assertEquals(2, p.samples.size)
        assertEquals(p.length, p.samples.last().distance, 1e-9)
    }

    @Test
    fun downsamplingKeepsEndsAndPeaks() {
        val alts = (0 until 1000).map { i -> if (i == 517) 500.0 else 100.0 + (i % 7) }
        val p = ElevationProfile.of(path(*alts.toTypedArray()))!!
        val d = p.downsampled(100)
        assertTrue(d.size <= 104)
        assertEquals(p.samples.first(), d.first())
        assertEquals(p.samples.last(), d.last())
        assertTrue(d.any { it.elevation == 500.0 })
    }
}
