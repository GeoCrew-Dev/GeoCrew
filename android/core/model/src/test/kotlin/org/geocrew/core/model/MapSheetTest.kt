package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSheetTest {
    // The textbook example: 114°33'45"E, 39°22'30"N.
    private val p = LngLat(114 + 33 / 60.0 + 45 / 3600.0, 39 + 22 / 60.0 + 30 / 3600.0)

    @Test
    fun namesTheSheetOfAPoint() {
        assertEquals("J50", MapSheet.of(p, MapSheet.Scale.M1000K)!!.code)
        assertEquals("J50D002002", MapSheet.of(p, MapSheet.Scale.M100K)!!.code)
        assertEquals("J50B001001", MapSheet.of(p, MapSheet.Scale.M500K)!!.code)
        // 1:5万 is 10' × 15': row 4 from the north, column 3.
        assertEquals("J50E004003", MapSheet.of(p, MapSheet.Scale.M50K)!!.code)
        assertNull(MapSheet.of(LngLat(-70.0, 40.0), MapSheet.Scale.M50K))
    }

    @Test
    fun parsesCodesBackToTheirSheets() {
        for (scale in MapSheet.Scale.entries) {
            val s = MapSheet.of(p, scale)!!
            val back = MapSheet.parse(s.code.lowercase())!!
            assertEquals(s, back)
            assertTrue(p.lng in back.bounds.west..back.bounds.east && p.lat in back.bounds.south..back.bounds.north)
        }
        assertEquals("J50", MapSheet.parse("J-50")!!.code)
        assertNull(MapSheet.parse("J50E025001"))
        assertNull(MapSheet.parse("hello"))
        // A point on a sheet's southern edge belongs to that sheet, not the one below.
        val s = MapSheet.parse("J50E004003")!!
        assertEquals("J50E004003", MapSheet.of(LngLat(s.bounds.west + 0.01, s.bounds.south), MapSheet.Scale.M50K)!!.code)
    }

    @Test
    fun coversAViewOrGivesUpWhenTooMany() {
        val view = Bounds(114.4, 39.2, 114.8, 39.5)
        val sheets = MapSheet.covering(view, MapSheet.Scale.M50K)!!
        assertEquals(3 * 3, sheets.size)
        assertEquals(sheets.size, sheets.map { it.code }.toSet().size)
        assertNull(MapSheet.covering(Bounds(100.0, 20.0, 120.0, 40.0), MapSheet.Scale.M50K))
    }
}
