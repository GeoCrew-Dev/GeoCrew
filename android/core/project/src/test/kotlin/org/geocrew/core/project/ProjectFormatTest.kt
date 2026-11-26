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

    // Points are for every field worker: a name and a description; the coordinates are the geometry.
    @Test
    fun theFieldPointTemplate() {
        val t = Templates.FIELD_POINT
        assertEquals(t, Templates.byId("field-point"))
        assertEquals("POINT", t.geometryType)
        assertEquals(listOf("点名", "描述"), t.fields.map { it.name("zh") })
        assertEquals(listOf("Name", "Description"), t.fields.map { it.name("en") })
        assertEquals("Name", t.fields.first().name("fr"), "other languages get English")
        assertEquals("野外点" to "Field points", t.name("zh") to t.name("de"))
        assertEquals(FieldType.STRING, t.fields.single { it.key == Templates.DESCRIPTION }.type)
        for (language in listOf("zh", "en")) {
            assertEquals(t.fields.size, t.fields.map { it.name(language) }.toSet().size, "field names are unique")
            assertNull(ProjectFormat.nameProblem(t.name(language)), "a template's name is also a fine table name")
        }
    }

    @Test
    fun theTemplateFindsItsFieldsInEitherLanguage() {
        val t = Templates.FIELD_POINT
        assertEquals("点名", t.column(Templates.NAME, listOf("描述", "点名", "水位埋深")))
        assertEquals("Description", t.column(Templates.DESCRIPTION, listOf("Name", "Description")))
        assertNull(t.column(Templates.NAME, listOf("编号", "水位埋深")))
        assertNull(t.column("dip", listOf("倾角")))
        assertTrue(Templates.all.all { it.fields.isNotEmpty() })
    }
}
