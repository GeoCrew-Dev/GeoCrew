package org.geocrew.core.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.geocrew.core.edit.FieldType

class ProjectFormatTest {
    @Test
    fun namesFitMemoryCards() {
        for (good in listOf("测区一号", "2026 秋季填图", "A-1_b", "Ünïcode 名")) assertNull(ProjectFormat.nameProblem(good), good)
        for (bad in listOf("", "  ", " 前空格", "后空格 ", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", ".", "..", "末尾点.", "tab\tx", "x".repeat(65))) {
            assertNotNull(ProjectFormat.nameProblem(bad), bad)
        }
    }

    @Test
    fun sqlQuoting() {
        assertEquals("'it''s'", ProjectFormat.quote("it's"))
        assertEquals("\"a\"\"b\"", ProjectFormat.identifier("a\"b"))
    }

    // Points are for every field worker: only the basic fields, no rock type or dip.
    @Test
    fun theFieldPointTemplate() {
        val t = Templates.FIELD_POINT
        assertEquals(t, Templates.byId("field-point"))
        assertEquals("POINT", t.geometryType)
        assertEquals(listOf("点号", "描述", "海拔", "定位精度", "记录时间", "记录人"), t.fields.map { it.name("zh") })
        assertEquals(listOf("Number", "Note", "Elevation", "Accuracy", "Time", "Recorder"), t.fields.map { it.name("en") })
        assertEquals("Number", t.fields.first().name("fr"), "other languages get English")
        assertEquals("野外点" to "Field points", t.name("zh") to t.name("de"))
        assertEquals(FieldType.DATETIME, t.fields.single { it.key == Templates.TIME }.type)
        for (language in listOf("zh", "en")) {
            assertEquals(t.fields.size, t.fields.map { it.name(language) }.toSet().size, "field names are unique")
            assertNull(ProjectFormat.nameProblem(t.name(language)), "a template's name is also a fine table name")
        }
    }

    @Test
    fun theTemplateFindsItsFieldsInEitherLanguage() {
        val t = Templates.FIELD_POINT
        assertEquals("点号", t.column(Templates.NUMBER, listOf("描述", "点号", "水位埋深")))
        assertEquals("Time", t.column(Templates.TIME, listOf("Number", "Time")))
        assertNull(t.column(Templates.NUMBER, listOf("编号", "水位埋深")))
        assertNull(t.column("dip", listOf("倾角")))
        assertTrue(Templates.all.all { it.fields.isNotEmpty() })
    }
}
