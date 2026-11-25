package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPrintTest {
    @Test
    fun scaleIsTheLargestRoundOneThatFits() {
        // 4.2 km on 240 mm of paper needs 1:17 500, so 1:2 万.
        assertEquals(20_000L, MapPrint.scaleToFit(4200.0, 240.0))
        assertEquals(25_000L, MapPrint.scaleToFit(6000.0, 240.0))
        assertEquals(500L, MapPrint.scaleToFit(50.0, 240.0))
        assertEquals(7_000_000L, MapPrint.scaleToFit(1_500_000.0, 240.0))
        assertEquals(168.0, MapPrint.paperMm(4200.0, 25_000), 1e-9)
        // At 1:2.5 万 a 50 mm bar holds 1 km.
        val bar = MapPrint.bar(25_000, 50.0)
        assertEquals(1000.0, bar.meters, 1e-9)
        assertEquals(40.0, bar.dp, 1e-9)
    }

    @Test
    fun ticksFollowARoundStepAndItsPrecision() {
        val lng = MapPrint.ticks(116.31, 116.40, lat = false)
        assertEquals(listOf("116°20′E", "116°22′E"), lng.map { it.label }.take(2))
        assertEquals(2, lng.size)
        assertEquals(116 + 20 / 60.0, lng[0].value, 1e-9)

        val lat = MapPrint.ticks(40.0000, 40.0050, lat = true)
        assertEquals("40°00′05″N", lat[0].label)
        assertTrue(lat.all { it.value > 40.0 && it.value < 40.005 })

        val wide = MapPrint.ticks(100.5, 117.2, lat = false)
        assertEquals(listOf("105°E", "110°E", "115°E"), wide.map { it.label })
        assertEquals("33°S", MapPrint.label(-33.0, 1.0, lat = true))
        assertEquals("70°30′W", MapPrint.label(-70.5, 0.5, lat = false))
        assertTrue(MapPrint.ticks(1.0, 1.0, lat = true).isEmpty())
    }
}
