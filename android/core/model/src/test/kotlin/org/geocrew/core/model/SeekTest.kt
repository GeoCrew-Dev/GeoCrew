package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekTest {
    private val here = LngLat(116.0, 40.0, 100.0)

    @Test fun northAndEast() {
        val north = SeekGuide.of(here, Geodesy.destination(here, 0.0, 200.0))
        assertEquals(200.0, north.distance, 0.01)
        assertEquals(0.0, Geodesy.normalizeDegrees(north.bearing + 0.0005) - 0.0005, 0.01)
        assertNull(north.turn)
        assertFalse(north.arrived)
        val east = SeekGuide.of(here, Geodesy.destination(here, 90.0, 1000.0), heading = 60.0)
        assertEquals(30.0, east.turn!!, 0.1)
        assertEquals("东", SeekGuide.direction(east.bearing))
    }

    @Test fun turnWrapsAround() {
        val t = Geodesy.destination(here, 350.0, 100.0)
        assertEquals(-20.0, SeekGuide.of(here, t, heading = 10.0).turn!!, 0.1)
        assertEquals(20.0, SeekGuide.of(here, Geodesy.destination(here, 10.0, 100.0), heading = 350.0).turn!!, 0.1)
        assertEquals("向左 20°", SeekGuide.turnLabel(-20.0))
        assertEquals("正前方", SeekGuide.turnLabel(5.0))
        assertEquals("在身后", SeekGuide.turnLabel(-175.0))
    }

    @Test fun arrivalUsesTheLargerOfRadiusAndAccuracy() {
        val t = Geodesy.destination(here, 45.0, 8.0)
        assertFalse(SeekGuide.of(here, t).arrived)
        val g = SeekGuide.of(here, t, accuracy = 12.0)
        assertTrue(g.arrived)
        assertEquals(12.0, g.arrivalRadius, 0.0)
    }

    @Test fun climbNeedsBothAltitudes() {
        val t = Geodesy.destination(here, 0.0, 50.0).copy(alt = 130.0)
        assertEquals(30.0, SeekGuide.of(here, t).climb!!, 1e-9)
        assertNull(SeekGuide.of(here.copy(alt = null), t).climb)
    }

    @Test fun directions() {
        assertEquals("北", SeekGuide.direction(359.0))
        assertEquals("东北", SeekGuide.direction(40.0))
        assertEquals("西北", SeekGuide.direction(320.0))
    }
}
