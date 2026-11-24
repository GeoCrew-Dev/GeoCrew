package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeodesyTest {
    @Test
    fun distanceBeijingShanghai() {
        val beijing = LngLat(116.4074, 39.9042)
        val shanghai = LngLat(121.4737, 31.2304)
        assertEquals(1_067_000.0, Geodesy.distance(beijing, shanghai), 5_000.0)
    }

    @Test
    fun bearingAndDestinationRoundTrip() {
        val start = LngLat(105.0, 35.0)
        val end = Geodesy.destination(start, 45.0, 10_000.0)
        assertEquals(10_000.0, Geodesy.distance(start, end), 0.5)
        assertEquals(45.0, Geodesy.bearing(start, end), 0.01)
    }

    @Test
    fun oneDegreeSquareAtEquator() {
        val square = Geometry.Polygon.of(listOf(LngLat(0.0, 0.0), LngLat(1.0, 0.0), LngLat(1.0, 1.0), LngLat(0.0, 1.0)))
        // 1° × 1° at the equator on a sphere of the mean radius.
        assertEquals(12_364e6, square.areaSquareMeters(), 0.01 * 12_364e6)
    }

    @Test
    fun holeIsSubtracted() {
        val outer = listOf(LngLat(0.0, 0.0), LngLat(0.02, 0.0), LngLat(0.02, 0.02), LngLat(0.0, 0.02), LngLat(0.0, 0.0))
        val hole = listOf(LngLat(0.005, 0.005), LngLat(0.015, 0.005), LngLat(0.015, 0.015), LngLat(0.005, 0.015), LngLat(0.005, 0.005))
        val full = Geodesy.area(Geometry.Polygon(listOf(outer)))
        val holed = Geodesy.area(Geometry.Polygon(listOf(outer, hole)))
        assertEquals(full * 0.75, holed, full * 0.01)
    }

    @Test
    fun formatting() {
        assertEquals("116°23′26.5″E", CoordinateFormatter.dms(116.3907, "E", "W"))
        assertEquals("850.0 m", CoordinateFormatter.distance(850.0))
        assertEquals("1.25 km", CoordinateFormatter.distance(1250.0))
        assertTrue(CoordinateFormatter.area(25_000.0).endsWith("ha"))
    }
}
