package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedOverlayTest {
    private val sha = "a".repeat(64)
    private val corners = listOf(listOf(110.0, 30.1), listOf(110.1, 30.1), listOf(110.1, 30.0), listOf(110.0, 30.0))

    private fun local(id: String, shared: Boolean = false, visible: Boolean = true) =
        ImageOverlay(id, "图$id", "$sha.png", 100, 80, corners, 0.5f, visible, ImageOverlay.Placement.WORLD_FILE, 3, shared)

    @Test
    fun roundTripsThroughTheWireForm() {
        val o = local("1", shared = true, visible = false)
        val wire = SharedOverlay.of(o)!!
        assertEquals(sha, wire.file)
        assertEquals("image/png", wire.type)
        assertEquals("world_file", wire.placement)
        assertEquals(o, wire.toOverlay(visible = false))
        assertNull(SharedOverlay.of(o.copy(file = "photo.jpg")))
    }

    @Test
    fun rejectsWhatCannotBeDrawn() {
        val ok = SharedOverlay.of(local("1"))!!
        assertTrue(SharedOverlay.valid(ok))
        assertFalse(SharedOverlay.valid(ok.copy(file = "../x")))
        assertFalse(SharedOverlay.valid(ok.copy(type = "image/tiff")))
        assertFalse(SharedOverlay.valid(ok.copy(corners = corners.take(3))))
        assertFalse(SharedOverlay.valid(ok.copy(corners = corners.map { listOf(it[0], 95.0) })))
        assertFalse(SharedOverlay.valid(ok.copy(width = 0)))
    }

    @Test
    fun mergeKeepsOwnOverlaysAndLocalVisibility() {
        val mine = local("m")
        val top = local("t")
        val before = listOf(mine, local("s1", shared = true, visible = false), local("gone", shared = true), top)
        val remote = listOf(SharedOverlay.of(local("s1"))!!.copy(name = "新名"), SharedOverlay.of(local("s2"))!!)
        val out = SharedOverlay.merge(before, remote)
        assertEquals(listOf("m", "s1", "s2", "t"), out.map { it.id })
        assertEquals("新名", out[1].name)
        assertFalse(out[1].visible)
        assertTrue(out[2].visible && out[2].shared)
        assertEquals(mine, out[0])
    }

    @Test
    fun mergeNeverReplacesOwnOverlayAndAppendsWhenNoneShared() {
        val mine = local("x")
        val out = SharedOverlay.merge(listOf(mine), listOf(SharedOverlay.of(local("x"))!!, SharedOverlay.of(local("y"))!!))
        assertEquals(listOf("x", "y"), out.map { it.id })
        assertFalse(out[0].shared)
        assertEquals(emptyList<ImageOverlay>(), SharedOverlay.merge(listOf(local("s", shared = true)), emptyList()))
    }

    @Test
    fun publishesOnlySharedOnes() {
        assertEquals(listOf("b"), SharedOverlay.published(listOf(local("a"), local("b", shared = true))).map { it.id })
    }
}
