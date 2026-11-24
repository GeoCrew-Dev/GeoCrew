package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MapScaleTest {
    @Test fun metersPerDpHalvesPerZoomAndShrinksWithLatitude() {
        val eq = MapScale.metersPerDp(0.0, 0.0)
        assertEquals(78271.5, eq, 0.1)
        assertEquals(eq / 2, MapScale.metersPerDp(0.0, 1.0), 1e-6)
        assertEquals(eq / 2, MapScale.metersPerDp(60.0, 0.0), 1e-6)
    }

    @Test fun barIsRoundAndFits() {
        val b = MapScale.bar(3.0, 100.0) // 300 m fit
        assertEquals(200.0, b.meters, 0.0)
        assertEquals(66.67, b.dp, 0.01)
        assertEquals(500.0, MapScale.bar(5.0, 100.0).meters, 0.0)
        assertEquals(1000.0, MapScale.bar(10.0, 100.0).meters, 0.0)
        assertEquals("200 m", MapScale.lengthLabel(200.0))
        assertEquals("2 km", MapScale.lengthLabel(2000.0))
        assertEquals("1.5 km", MapScale.lengthLabel(1500.0))
        assertEquals("50 cm", MapScale.lengthLabel(0.5))
        assertEquals(0.0, MapScale.bar(0.0, 100.0).meters, 0.0)
    }

    @Test fun ratioInChineseUnits() {
        // 1 dp = 0.15875 mm; 3.97 m per dp ≈ 1:25000.
        assertEquals(25000L, MapScale.denominator(3.97))
        assertEquals("1:2.5万", MapScale.ratioLabel(25000))
        assertEquals("1:5000", MapScale.ratioLabel(5000))
        assertEquals("1:100万", MapScale.ratioLabel(1_000_000))
        assertEquals("1:1.2亿", MapScale.ratioLabel(120_000_000))
    }
}
