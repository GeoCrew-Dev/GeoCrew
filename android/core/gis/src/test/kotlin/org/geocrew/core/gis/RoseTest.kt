package org.geocrew.core.gis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoseTest {
    @Test
    fun binsFoldOppositeDirections() {
        val bins = Rose.axialBins(listOf(5.0, 185.0, 359.0, 179.9, 90.0, -10.0), binWidth = 30)
        assertArrayEquals(intArrayOf(2, 0, 0, 1, 0, 3), bins)
    }

    @Test
    fun axialMeanHandlesWrapAround() {
        // 170° and 10° are 20° apart as lines; their mean is north-south.
        val m = Rose.axialMean(listOf(170.0, 10.0))!!
        assertEquals(0.0, minOf(m.direction, 180 - m.direction), 1e-9)
        assertEquals(Math.cos(Math.toRadians(20.0)), m.resultant, 1e-9)

        val e = Rose.axialMean(listOf(80.0, 100.0, 260.0))!!
        // Doubled: 160°, 200°, 160° -> mean 173.07°, halved.
        assertEquals(86.54, e.direction, 0.01)
        assertNull(Rose.axialMean(emptyList()))
    }

    @Test
    fun perpendicularSetsCancel() {
        val m = Rose.axialMean(listOf(0.0, 90.0))!!
        assertEquals(0.0, m.resultant, 1e-9)
    }
}
