package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NumberingTest {
    private val n = Numbering()

    @Test
    fun followsTheLargestNumberNotTheCount() {
        assertEquals("D001", n.next(FeatureKind.GEO_POINT, emptyList()))
        assertEquals("D008", n.next(FeatureKind.GEO_POINT, listOf("D002", "D007", null, "P099", "D007a", " D003 ")))
    }

    @Test
    fun prefixesAreTakenLiterally() {
        val team = n.copy(geoPoint = "D-王")
        assertEquals("D-王013", team.next(FeatureKind.GEO_POINT, listOf("D-王012", "D050")))
        assertEquals("a.b1", Numbering(width = 0).next("a.b", listOf("axb9")))
    }

    @Test
    fun kindsWithoutAPrefixAreLeftAlone() {
        assertNull(n.next(FeatureKind.TRACK, emptyList()))
        assertEquals("线3", n.copy(width = 0).next(FeatureKind.LINE, listOf("线2")))
    }

    @Test
    fun rejectsPrefixesEndingInDigits() {
        assertNotNull(Numbering.problem(n.copy(point = "P1")))
        assertNull(Numbering.problem(n))
    }
}
