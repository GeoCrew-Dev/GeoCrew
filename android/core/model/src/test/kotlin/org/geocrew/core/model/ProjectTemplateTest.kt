package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTemplateTest {
    @Test fun keepsFormAndDropsProjectData() {
        val src = ProjectConfig(
            baseSourceId = "tdt-img",
            description = "2026 年某幅填图",
            fields = listOf(FieldTemplate(key = "lith", label = "岩性")),
            teamFormVersion = 7,
            teamFormPending = true,
            teamCommentSeq = 40,
            chatReadSeq = 39,
            numbering = Numbering(geoPoint = "XD"),
            pushEnabled = true,
            phrases = listOf(PhraseGroup("颜色", listOf("灰白色"))),
            noticeSeenAt = "2026-11-24T00:00:00Z",
        )
        val t = src.template()
        assertEquals("tdt-img", t.baseSourceId)
        assertEquals(src.fields, t.fields)
        assertEquals("XD", t.numbering.geoPoint)
        assertEquals(src.phrases, t.phrases)
        assertEquals("", t.description)
        assertEquals(0L, t.teamFormVersion)
        assertFalse(t.teamFormPending)
        assertEquals(0L, t.teamCommentSeq)
        assertFalse(t.pushEnabled)
        assertNull(t.noticeSeenAt)
        assertTrue(t.overlays.isEmpty())
    }
}
