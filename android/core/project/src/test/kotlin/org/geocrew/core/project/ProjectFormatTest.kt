package org.geocrew.core.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun theGeologicalPointTemplate() {
        val t = Templates.GEO_POINT
        assertEquals(t, Templates.byId("geo-point"))
        assertEquals("POINT", t.geometryType)
        assertEquals(t.fields.size, t.fields.map { it.name }.toSet().size, "field names are unique")
        assertEquals(FieldType.REAL, t.fields.single { it.name == "倾角" }.type)
        assertEquals(null, ProjectFormat.nameProblem(t.name), "a template's name is also a fine table name")
    }
}
