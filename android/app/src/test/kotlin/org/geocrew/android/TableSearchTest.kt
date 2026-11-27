package org.geocrew.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableSearchTest {
    private val row = listOf("12", "D012", "Granite outcrop", "花岗岩")

    @Test
    fun anyColumnMatchesWhateverTheCase() {
        assertTrue(matches(row, "d012"))
        assertTrue(matches(row, "OUTCROP"))
        assertTrue(matches(row, "花岗"))
        assertTrue(matches(row, " 12 "), "trimmed")
        assertFalse(matches(row, "basalt"))
    }

    @Test
    fun fullWidthIsReadAsPlain() {
        assertTrue(matches(row, "Ｄ０１２"))
        assertTrue(matches(listOf("ＧＫ２"), "gk2"))
    }

    @Test
    fun aBlankQueryKeepsEveryRow() {
        assertTrue(matches(row, ""))
        assertTrue(matches(row, "   "))
        assertTrue(matches(emptyList(), ""))
    }
}
