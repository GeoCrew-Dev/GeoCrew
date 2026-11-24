package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GaussKrugerTest {
    // Reference values from PROJ 9 (pyproj) with +proj=tmerc; GRS80 shares
    // its a and f with CGCS2000.
    private fun check(p: LngLat, width: Int, ellipsoid: Ellipsoid, zone: Int, x: Double, y: Double) {
        val r = GaussKruger.project(p, width, ellipsoid)
        assertEquals(zone, r.zone)
        assertEquals(x, r.x, 0.001)
        assertEquals(y, r.y, 0.001)
        val back = GaussKruger.unproject(r.x, r.yWithZone, width, ellipsoid)
        assertEquals(p.lng, back.lng, 1e-9)
        assertEquals(p.lat, back.lat, 1e-9)
    }

    @Test fun matchesProjInThreeDegreeZones() {
        check(LngLat(116.391, 39.907), 3, Ellipsoid.CGCS2000, 39, 4419380.4476, 447924.4832)
        check(LngLat(118.45, 30.2), 3, Ellipsoid.CGCS2000, 39, 3343173.1001, 639631.1347)
    }

    @Test fun matchesProjInSixDegreeZones() {
        check(LngLat(115.6, 45.0), 6, Ellipsoid.CGCS2000, 20, 4985898.0877, 389614.4134)
        check(LngLat(114.5, 22.3), 6, Ellipsoid.BEIJING54, 20, 2469198.2024, 242328.1655)
        check(LngLat(108.01, -10.5), 6, Ellipsoid.CGCS2000, 19, -1162717.6295, 172552.1489)
    }

    @Test fun matchesUtm() {
        val tm = TransverseMercator(Ellipsoid.WGS84, -75.0, scale = 0.9996)
        val (e, n) = tm.forward(LngLat(-72.0, 40.5))
        assertEquals(754219.9599, e, 0.001)
        assertEquals(4487579.2555, n, 0.001)
    }

    @Test fun quarterMeridian() {
        // WGS 84 equator-to-pole meridian arc: 10 001 965.729 m.
        val (_, n) = TransverseMercator(Ellipsoid.WGS84, 0.0).forward(LngLat(0.0, 90.0))
        assertEquals(10_001_965.729, n, 0.001)
    }

    @Test fun zones() {
        assertEquals(39, GaussKruger.zone(116.391, 3))
        assertEquals(20, GaussKruger.zone(116.391, 6))
        assertEquals(117.0, GaussKruger.centralMeridian(39, 3), 0.0)
        assertEquals(117.0, GaussKruger.centralMeridian(20, 6), 0.0)
        assertEquals(-75.0, GaussKruger.centralMeridian(GaussKruger.zone(-75.0, 6), 6), 0.0)
        assertEquals(-75.0, GaussKruger.centralMeridian(GaussKruger.zone(-75.0, 3), 3), 0.0)
        // Forcing the neighbouring zone, as at a zone edge.
        val p = GaussKruger.project(LngLat(118.49, 31.0), 3, zone = 40)
        assertEquals(120.0, p.centralMeridian, 0.0)
        val back = GaussKruger.unproject(p.x, p.y, 3, zone = 40)
        assertEquals(118.49, back.lng, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unprefixedYNeedsAZone() {
        GaussKruger.unproject(4419380.0, 447924.0, 3)
    }
}
