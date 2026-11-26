package org.geocrew.core.project

import kotlin.test.Test
import kotlin.test.assertEquals

class PointNumbersTest {
    @Test
    fun countsOnFromTheHighest() {
        assertEquals("D001", PointNumbers.next(emptyList()))
        assertEquals("D004", PointNumbers.next(listOf("D001", "D003", null, "D002")))
        assertEquals("D0013", PointNumbers.next(listOf("D0012", "D007")), "keeps the widest numbers' width")
        assertEquals("D1000", PointNumbers.next(listOf("D999")))
        assertEquals("D002", PointNumbers.next(listOf("D001", "d005", "D1a", "XD9", "补D7")), "only the prefix and digits count")
        assertEquals("P.002", PointNumbers.next(listOf("P.001", "PX003"), prefix = "P."))
    }
}
