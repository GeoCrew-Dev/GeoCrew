package org.geocrew.core.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MbTilesTest {
    @Test
    fun metadataIsReadWithDefaults() {
        val m = MbTiles.parseMeta(
            mapOf("name" to "测区影像", "format" to "JPEG", "minzoom" to "8", "maxzoom" to "16", "bounds" to "110.1, 30.2,110.5,30.6", "attribution" to "<a href=\"x\">&copy; 某单位</a>"),
            "file",
        )
        assertEquals("测区影像", m.name)
        assertEquals("jpg", m.format)
        assertEquals(8, m.minZoom)
        assertEquals(16, m.maxZoom)
        assertEquals(listOf(110.1, 30.2, 110.5, 30.6), m.bounds)
        assertEquals("© 某单位", m.attribution)
        assertNull(MbTiles.problem(m))

        val bare = MbTiles.parseMeta(emptyMap(), "my-area")
        assertEquals("my-area", bare.name)
        assertNull(bare.format)
        assertEquals(0, bare.minZoom)
        assertEquals(18, bare.maxZoom)
        assertNull(bare.bounds)
    }

    @Test
    fun vectorPackagesAreRefused() {
        val m = MbTiles.parseMeta(mapOf("format" to "pbf"), "v")
        assertNotNull(MbTiles.problem(m))
    }

    @Test
    fun badBoundsAreDropped() {
        assertNull(MbTiles.parseBounds("1,2,3"))
        assertNull(MbTiles.parseBounds("10,10,5,20"))
        assertNull(MbTiles.parseBounds("a,b,c,d"))
        assertEquals(listOf(-180.0, -85.0511, 180.0, 85.0511), MbTiles.parseBounds("-180,-90,180,90"))
    }

    @Test
    fun rowsAreFlippedFromTms() {
        assertEquals(0, MbTiles.xyzRow(0, 0))
        assertEquals(3, MbTiles.xyzRow(2, 0))
        assertEquals(0, MbTiles.xyzRow(2, 3))
        assertTrue(MbTiles.valid(2, 3, 3))
        assertFalse(MbTiles.valid(2, 4, 0))
        assertFalse(MbTiles.valid(25, 0, 0))
    }

    @Test
    fun tilesAreSniffed() {
        assertEquals("png", MbTiles.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("jpg", MbTiles.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("webp", MbTiles.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)))
        assertEquals("pbf", MbTiles.sniff(byteArrayOf(0x1F, 0x8B.toByte(), 8)))
        assertNull(MbTiles.sniff(byteArrayOf(1, 2)))
    }

    @Test
    fun sourcePointsAtTheFolder() {
        val m = MbTiles.parseMeta(mapOf("name" to "A", "maxzoom" to "15", "bounds" to "1,2,3,4"), "x")
        val s = MbTiles.source("offline-1", m, "png", "file:///data/basemaps/offline-1")
        assertEquals("file:///data/basemaps/offline-1/{z}/{x}/{y}.png", s.layers.single().url)
        assertEquals(15, s.layers.single().maxZoom)
        assertEquals(18, s.maxZoom)
        assertTrue(s.offline && s.custom)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), s.bounds)
    }
}
