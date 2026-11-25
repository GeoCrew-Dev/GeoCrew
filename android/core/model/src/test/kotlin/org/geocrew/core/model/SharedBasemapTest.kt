package org.geocrew.core.model

import org.geocrew.core.model.tiles.BuiltInSources
import org.geocrew.core.model.tiles.SharedBasemap
import org.geocrew.core.model.tiles.TileCatalog
import org.geocrew.core.model.tiles.TileLayer
import org.geocrew.core.model.tiles.TileSource
import org.geocrew.core.model.tiles.TileTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedBasemapTest {
    private val wms = TileSource(
        id = "custom-1a2b3c4d",
        name = "1:20万地质图",
        layers = listOf(TileLayer(TileTemplates.wms("https://geo.example.org/wms", "geo200k")!!, maxZoom = 16)),
        maxZoom = 16,
        custom = true,
    )

    @Test
    fun sharesOnlyKeylessOnlineCustomSources() {
        val b = SharedBasemap.of(wms)
        assertNotNull(b)
        assertEquals("custom-1a2b3c4d", b!!.id)
        assertEquals(16, b.maxZoom)
        assertNull(SharedBasemap.of(BuiltInSources.OSM))
        assertNull(SharedBasemap.of(BuiltInSources.TIANDITU_VECTOR.copy(custom = true)))
        assertNull(SharedBasemap.of(wms.copy(offline = true)))
        assertNull(SharedBasemap.of(wms.copy(layers = listOf(TileLayer("https://t/{z}/{x}/{y}.png?tk={key}")))))
        assertFalse(SharedBasemap.valid(b.copy(url = "https://t/{z}/{x}/{y}.png?tk={key}")))
        assertFalse(SharedBasemap.valid(b.copy(id = "a b")))
        assertFalse(SharedBasemap.valid(b.copy(url = "file:///sdcard/{z}/{x}/{y}.png")))
    }

    @Test
    fun catalogListsSharedSourcesOnce() {
        val shared = SharedBasemap.of(wms)!!.toSource()
        assertTrue(shared.shared)
        assertEquals(SharedBasemap.GROUP, shared.group)
        // A member: the shared source appears after the user's own.
        val member = TileCatalog(emptyList(), listOf(shared, shared))
        assertEquals(1, member.sources.count { it.id == wms.id })
        assertTrue(member.find(wms.id)!!.shared)
        assertFalse(member.find(wms.id)!!.custom)
        // The leader who shared it sees it once, as their own.
        val leader = TileCatalog(listOf(wms), listOf(shared))
        assertEquals(1, leader.sources.count { it.id == wms.id })
        assertTrue(leader.find(wms.id)!!.custom)
        assertFalse(leader.find(wms.id)!!.shared)
    }
}
