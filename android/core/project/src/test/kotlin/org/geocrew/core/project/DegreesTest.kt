package org.geocrew.core.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DegreesTest {
    private fun near(expected: Double, text: String) = assertEquals(expected, Degrees.parse(text)!!, 1e-9, text)

    @Test
    fun fullWidthIsReadAsAChineseKeyboardTypesIt() {
        near(114.3, "114。3")
        near(114.305138888888889, "１１４°１８′１８．５″")
        near(-30.5, "Ｓ30。5")
        near(30.5, "30，5")
        assertNull(Degrees.parse("30,114"), "a plain comma is no decimal point: it may part two numbers")
        assertNull(Degrees.parse("Ｓ30", Degrees.Axis.LONGITUDE), "a full-width hemisphere letter of the other axis")
        assertFalse(Degrees.isSexagesimal("114。3"), "plain decimal degrees, only typed full-width")
    }

    @Test
    fun theWaysPeopleWriteThem() {
        near(114.305, "114.305")
        near(114.30513888888889, "114°18'18.5\"")
        near(114.30513888888889, "114°18′18.5″")
        near(114.30513888888889, "114 18 18.5")
        near(114.30513888888889, "114度18分18.5秒")
        near(114.30513333333333, "114°18.308'")
        near(30.5, "30:30")
        near(30.5, "  30°30'  ")
    }

    @Test
    fun hemispheresAndSigns() {
        near(-114.5, "114°30'W")
        near(-114.5, "W114°30'")
        near(-30.5, "南30°30′")
        near(30.5, "北30°30′")
        near(-30.5, "-30.5")
        near(114.5, "E 114 30")
        assertNull(Degrees.parse("30°30'E", Degrees.Axis.LATITUDE), "east is no latitude")
        assertNull(Degrees.parse("N114", Degrees.Axis.LONGITUDE))
        assertEquals(-30.5, Degrees.parse("30°30'S", Degrees.Axis.LATITUDE)!!, 1e-9)
    }

    @Test
    fun nonsenseIsNotDegrees() {
        assertNull(Degrees.parse(""))
        assertNull(Degrees.parse("abc"))
        assertNull(Degrees.parse("114°60'"), "minutes stay under 60")
        assertNull(Degrees.parse("114.5°30'"), "only the last part has decimals")
        assertNull(Degrees.parse("1 2 3 4"))
        assertNull(Degrees.parse("114°-3'"))
    }

    @Test
    fun dmsAndBack() {
        assertEquals("114°18′18.5″", Degrees.dms(114.30513888888889))
        assertEquals("-30°30′0.0″", Degrees.dms(-30.5))
        near(114.30513888888889, Degrees.dms(114.30513888888889))
        assertTrue(Degrees.isSexagesimal("114 18 18.5"))
        assertFalse(Degrees.isSexagesimal("114.305"))
    }
}
