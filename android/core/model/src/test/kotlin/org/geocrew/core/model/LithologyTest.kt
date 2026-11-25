package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LithologyTest {
    @Test
    fun projectNamesComeFirstByUse() {
        val used = listOf("灰岩", "砂岩", "灰岩", " ", "紫红色砂岩")
        val s = Lithology.suggest("", used, limit = 4)
        assertEquals(listOf("灰岩", "砂岩", "紫红色砂岩", "花岗岩"), s)
    }

    @Test
    fun matchesPrefixBeforeContains() {
        val s = Lithology.suggest("砂", listOf("紫红色砂岩"))
        assertEquals("砂岩", s.first())
        assertTrue("紫红色砂岩" in s && "石英砂岩" in s)
        assertTrue(s.none { it == "砂" })
    }

    @Test
    fun exactTypedNameIsNotOfferedAgain() {
        assertTrue("花岗岩" !in Lithology.suggest("花岗岩", emptyList()))
        assertEquals(Lithology.common.size, Lithology.common.distinct().size)
    }
}
