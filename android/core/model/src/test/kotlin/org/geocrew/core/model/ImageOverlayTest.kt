package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOverlayTest {
    @Test fun fitsInsideTheViewKeepingProportions() {
        val view = Bounds(116.0, 39.9, 116.2, 40.0)
        val q = ImageOverlay.fit(view, 2000, 1000)
        assertEquals(4, q.size)
        assertEquals("top edge is level", q[0].lat, q[1].lat, 1e-12)
        assertEquals("left edge is upright", q[0].lng, q[3].lng, 1e-12)
        assertTrue(q.all { it.lng in 116.0..116.2 && it.lat in 39.9..40.0 })
        // A wide image fills 90 % of the width.
        assertEquals(0.18, q[1].lng - q[0].lng, 1e-9)
        val c = (q[0].lng + q[1].lng) / 2
        assertEquals(116.1, c, 1e-9)
    }

    @Test fun readsAWorldFileInDegrees() {
        val w = WorldFile.parse("0.0001\n0\n0\n-0.0001\n116.00005\n40.99995\n")!!
        assertEquals(WorldFile.Crs.Degrees, WorldFile.guess(w))
        val q = WorldFile.corners(w, 1000, 500, WorldFile.Crs.Degrees)!!
        assertEquals(116.0, q[0].lng, 1e-9); assertEquals(41.0, q[0].lat, 1e-9)
        assertEquals(116.1, q[2].lng, 1e-9); assertEquals(40.95, q[2].lat, 1e-9)
        assertNull(WorldFile.parse("1 2 3"))
        assertNull(WorldFile.parse("a b c d e f"))
    }

    @Test fun readsAGaussKrugerWorldFileWithZone() {
        val p = GaussKruger.project(LngLat(116.4, 39.9), 3)
        // 2 m pixels, top-left pixel centred 1 m in from the point.
        val text = listOf(2.0, 0.0, 0.0, -2.0, p.yWithZone + 1, p.x - 1).joinToString("\n")
        val w = WorldFile.parse(text)!!
        val crs = WorldFile.guess(w)
        assertEquals(WorldFile.Crs.Plane(3), crs)
        val q = WorldFile.corners(w, 500, 500, crs!!)!!
        assertEquals(116.4, q[0].lng, 1e-7)
        assertEquals(39.9, q[0].lat, 1e-7)
        // 1 km east and south (the spherical distance is good to a few metres here).
        assertEquals(1000.0, Geodesy.distance(q[0], q[1]), 5.0)
        assertEquals(1000.0, Geodesy.distance(q[1], q[2]), 5.0)
        // A 6° zone number is told apart, and plane metres without a zone can't be placed.
        assertEquals(WorldFile.Crs.Plane(6), WorldFile.guess(w.copy(c = 20_500_000.0)))
        assertNull(WorldFile.guess(w.copy(c = 500_000.0)))
    }
}
