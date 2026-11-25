package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    // Just enough pinyin for the examples; the app uses the platform's full table.
    private val table = mapOf(
        '花' to "hua", '岗' to "gang", '岩' to "yan", '眼' to "yan", '言' to "yan",
        '片' to "pian", '麻' to "ma", '马' to "ma", '灰' to "hui", '色' to "se",
        '中' to "zhong", '粒' to "li", '力' to "li", '结' to "jie", '构' to "gou", '够' to "gou",
        '见' to "jian", '的' to "de", '是' to "shi", '露' to "lu", '头' to "tou",
    )
    private val py: (Char) -> String? = { table[it] }

    @Test
    fun cleanDropsSpacesBetweenChineseWords() {
        assertEquals("灰色中粒花岗岩，产状120度", SpeechText.clean("  灰色 中粒 花岗岩，产状 120 度 "))
        assertEquals("sample A 12", SpeechText.clean("sample  A 12"))
    }

    @Test
    fun sameSoundingRunsBecomeTerms() {
        val r = SpeechText.fix("露头见的是灰色中力花岗眼，片马言", listOf("花岗岩", "片麻岩", "中粒", "灰色"), py)
        assertEquals("露头见的是灰色中粒花岗岩，片麻岩", r.text)
        assertEquals(listOf("中力" to "中粒", "花岗眼" to "花岗岩", "片马言" to "片麻岩"), r.fixes.map { it.heard to it.term })
    }

    @Test
    fun correctTextAndUnknownSoundsStayAsTheyAre() {
        val r = SpeechText.fix("花岗岩结构", listOf("花岗岩", "结构"), py)
        assertEquals("花岗岩结构", r.text)
        assertTrue(r.fixes.isEmpty())
        assertTrue(SpeechText.fix("ABC", listOf("花岗岩"), py).fixes.isEmpty())
        // A term that is not all Chinese, or a single character, is not used.
        assertTrue(SpeechText.fix("眼", listOf("岩", "D12"), py).fixes.isEmpty())
    }
}
