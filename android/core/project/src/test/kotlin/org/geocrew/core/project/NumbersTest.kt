package org.geocrew.core.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumbersTest {
    @Test
    fun fullWidthDigitsAndPointsAreRead() {
        assertEquals(123.5, Numbers.double("１２３．５"))
        assertEquals(114.3, Numbers.double("114。3"), "the ideographic full stop a pinyin keyboard gives")
        assertEquals(-5.0, Numbers.double("－5"))
        assertEquals(12.0, Numbers.double("　12 "))
        assertEquals(3_500_000L, Numbers.long("３５０００００"))
        assertEquals(42, Numbers.int("＋42"))
    }

    @Test
    fun whatIsNoNumberStaysNone() {
        assertNull(Numbers.double("abc"))
        assertNull(Numbers.double("NaN"))
        assertNull(Numbers.double("Infinity"))
        assertNull(Numbers.double(""))
        assertNull(Numbers.double("1,5"), "a plain comma is not read as a decimal point")
        assertNull(Numbers.double("1，500"), "nor a full-width one: it may be a thousands separator")
        assertNull(Numbers.long("1.5"))
    }

    @Test
    fun textOtherThanNumbersIsLeftAlone() {
        assertEquals("砂岩 12.5", Numbers.plain("砂岩 １２．５"))
        assertEquals("E114°18'", Numbers.plain("Ｅ114°18＇"))
    }
}
