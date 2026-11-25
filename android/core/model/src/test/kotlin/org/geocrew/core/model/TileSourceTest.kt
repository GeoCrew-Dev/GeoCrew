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

    @Test
    fun fillsTileNumbersAndWmsExtents() {
        assertEquals("https://t/3/2/5.png", TileTemplates.fill("https://t/{z}/{x}/{y}.png", 3, 2, 5))
        assertEquals("https://t/3/2/2.png", TileTemplates.fill("https://t/{z}/{x}/{y}.png", 3, 2, 5, tms = true))
        val b = TileTemplates.bbox(0, 0, 0)
        assertEquals(-20037508.34, b[0], 0.01)
        assertEquals(20037508.34, b[3], 0.01)
        val q = TileTemplates.bbox(1, 1, 0) // top right quarter
        assertEquals(0.0, q[0], 1e-6)
        assertEquals(0.0, q[1], 1e-6)
        assertEquals(
            "https://w?BBOX=0.000000,0.000000,20037508.342789,20037508.342789",
            TileTemplates.fill("https://w?BBOX={bbox-epsg-3857}", 1, 1, 0),
        )
    }

    @Test
    fun buildsWmsAddresses() {
        val u = TileTemplates.wms("https://maps.example.org/wms?map=geo&request=GetCapabilities&service=WMS", "geo:units, faults")
        assertNotNull(u)
        assertTrue(u!!.startsWith("https://maps.example.org/wms?map=geo&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=geo%3Aunits,faults&"))
        assertTrue("CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256" in u)
        assertNull(TileTemplates.validate(u))
        assertTrue("SRS=EPSG:3857" in TileTemplates.wms("http://h/ows", "a", version = "1.1.1")!!)
        assertNull(TileTemplates.wms("ftp://h/ows", "a"))
        assertNull(TileTemplates.wms("https://h/ows", " , "))
        assertNotNull(TileTemplates.validate("https://h/ows?BBOX={bbox}"))
    }
}
