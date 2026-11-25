package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTermsTest {
    @Test
    fun insertsWithACommaOnlyWhenNeeded() {
        assertEquals("灰白色" to 3, FieldTerms.insert("", 0, "灰白色"))
        assertEquals("灰白色，中粒" to 6, FieldTerms.insert("灰白色", 3, "中粒"))
        assertEquals("灰白色，中粒" to 6, FieldTerms.insert("灰白色，", 4, "中粒"))
        assertEquals("岩性：花岗岩" to 6, FieldTerms.insert("岩性：", 3, "花岗岩"))
        // In the middle, the rest stays after the cursor.
        assertEquals("灰白色，中粒，块状构造" to 7, FieldTerms.insert("灰白色，块状构造", 4, "中粒，"))
        assertEquals("ab，c" to 4, FieldTerms.insert("ab", 99, "c"))
    }

    @Test
    fun projectGroupsComeFirstAndExtendBuiltInOnes() {
        val own = listOf(PhraseGroup("地层", listOf("蓟县系", " 长城系 ", "")), PhraseGroup("颜色", listOf("铅灰色", "灰白色")))
        val all = FieldTerms.all(own)
        assertEquals("地层", all.first().name)
        assertEquals(listOf("蓟县系", "长城系"), all.first().items)
        val colours = all.first { it.name == "颜色" }.items
        assertEquals("铅灰色", colours.first())
        assertEquals(1, colours.count { it == "灰白色" })
        assertEquals(FieldTerms.builtIn.size + 1, all.size)
    }

    @Test
    fun addsAndRemovesProjectWords() {
        var own = FieldTerms.add(emptyList(), "", "雾迷山组")
        assertEquals(listOf(PhraseGroup(FieldTerms.PROJECT_GROUP, listOf("雾迷山组"))), own)
        own = FieldTerms.add(own, FieldTerms.PROJECT_GROUP, "雾迷山组")
        assertEquals(1, own.single().items.size)
        own = FieldTerms.add(own, "地层", "高于庄组")
        assertEquals("地层", own.first().name)
        own = FieldTerms.remove(own, FieldTerms.PROJECT_GROUP, "雾迷山组")
        assertEquals(listOf("地层"), own.map { it.name })
        assertTrue(FieldTerms.add(own, "地层", "x".repeat(FieldTerms.MAX_ITEM + 1)).single().items == listOf("高于庄组"))
    }
}
