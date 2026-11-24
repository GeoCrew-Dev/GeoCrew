package org.geocrew.core.model

import kotlinx.serialization.json.Json
import org.geocrew.core.model.tiles.BuiltInSources
import org.geocrew.core.model.tiles.TileCatalog
import org.geocrew.core.model.tiles.TileKeys
import org.geocrew.core.model.tiles.TileLayer
import org.geocrew.core.model.tiles.TileSource
import org.geocrew.core.model.tiles.TileTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSourceTest {
    @Test
    fun validatesTemplates() {
        assertNull(TileTemplates.validate("https://tile.example.org/{z}/{x}/{y}.png"))
        assertNull(TileTemplates.validate("https://{s}.example.org/{z}/{x}/{-y}.png", listOf("a", "b")))
        assertNotNull(TileTemplates.validate("ftp://x/{z}/{x}/{y}"))
        assertNotNull(TileTemplates.validate("https://x/{z}/{x}"))
        assertNotNull(TileTemplates.validate("https://x/{z}/{x}/{y}/{q}"))
        assertNotNull(TileTemplates.validate("https://{s}.x/{z}/{x}/{y}"))
    }

    @Test
    fun resolvesSubdomainsKeysAndTms() {
        val r = TileTemplates.resolve(TileLayer("https://{s}.x/{z}/{x}/{-y}.png?k={key}", listOf("a", "b")), "K1")
        assertEquals(listOf("https://a.x/{z}/{x}/{y}.png?k=K1", "https://b.x/{z}/{x}/{y}.png?k=K1"), r.urls)
        assertTrue(r.tms)
        val tdt = TileTemplates.resolve(BuiltInSources.TIANDITU_IMAGE.layers[0], "abc")
        assertEquals(8, tdt.urls.size)
        assertTrue(tdt.urls[7].startsWith("https://t7.tianditu.gov.cn/img_w/") && tdt.urls[7].endsWith("tk=abc"))
        assertEquals(listOf("0", "1", "2", "3"), TileTemplates.parseSubdomains("0-3"))
        assertEquals(listOf("a", "b", "c"), TileTemplates.parseSubdomains("a, b，c"))
    }

    @Test
    fun fallsBackWhenKeyMissing() {
        val custom = TileSource("my", "自建", listOf(TileLayer("https://x/{z}/{x}/{y}.png")))
        val catalog = TileCatalog(listOf(custom))
        val none = TileKeys(emptyMap())
        assertEquals("osm", catalog.effective(BuiltInSources.TIANDITU_VECTOR.id, none).id)
        val withKey = TileKeys(mapOf(BuiltInSources.TIANDITU_KEY_ID to "k"))
        assertEquals(BuiltInSources.TIANDITU_VECTOR.id, catalog.effective(BuiltInSources.TIANDITU_VECTOR.id, withKey).id)
        assertEquals("my", catalog.effective("my", none).id)
        assertTrue(catalog.find("my")!!.custom)
        assertTrue(BuiltInSources.isPlausibleTiandituKey("0123456789abcdef0123456789ABCDEF"))
    }

    @Test
    fun serializesRoundTrip() {
        val s = TileSource("my", "自建", listOf(TileLayer("https://x/{z}/{x}/{y}.png", opacity = 0.5f)), custom = true)
        val json = Json.encodeToString(TileSource.serializer(), s)
        assertEquals(s, Json.decodeFromString(TileSource.serializer(), json))
    }
}
