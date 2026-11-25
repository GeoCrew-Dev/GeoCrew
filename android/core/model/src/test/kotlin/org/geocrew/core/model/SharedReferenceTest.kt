package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedReferenceTest {
    private val hash = "a".repeat(64)

    @Test
    fun onlyLayersWithAFileArePublished() {
        val list = listOf(
            ReferenceLayer("own", "本机"),
            ReferenceLayer("s1", "地质图", colorBy = "代号", shared = true, file = hash, count = 12, opacity = 0.6f),
            ReferenceLayer("s2", "还没算哈希", shared = true),
        )
        assertEquals(listOf(SharedReference("s1", "地质图", hash, "代号", 12, 0.6f)), SharedReference.published(list))
    }

    @Test
    fun mergeKeepsOwnLayersAndThisPhonesVisibility() {
        val local = listOf(
            ReferenceLayer("own", "本机"),
            ReferenceLayer("s1", "旧名", shared = true, file = hash, visible = false),
            ReferenceLayer("gone", "组长已删", shared = true, file = hash),
        )
        val remote = listOf(
            SharedReference("s1", "新名", hash, opacity = 5f),
            SharedReference("own", "同名冒充", hash),
            SharedReference("s3", "新加", hash),
            SharedReference("bad id!", "x", hash),
            SharedReference("s4", "坏哈希", "xyz"),
        )
        val m = SharedReference.merge(local, remote)
        assertEquals(listOf("own", "s1", "s3"), m.map { it.id })
        assertEquals("本机", m[0].name)
        assertFalse(m[0].shared)
        assertEquals("新名", m[1].name)
        assertFalse(m[1].visible)
        assertEquals(1f, m[1].opacity)
        assertTrue(m[2].visible && m[2].shared)
    }
}
