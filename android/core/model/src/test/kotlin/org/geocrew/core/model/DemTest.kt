package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DemTest {
    @Test fun terrariumColoursRoundTrip() {
        for (h in listOf(-10.5, 0.0, 52.25, 1203.0, 8848.86)) assertEquals(h, Terrarium.decode(Terrarium.encode(h))!!, 1.0 / 256)
        assertNull(Terrarium.decode(0x00FFFFFF))
    }

    @Test fun pixelOfWellKnownPlaces() {
        val (x, y) = Terrarium.pixel(LngLat(0.0, 0.0), 0)
        assertEquals(128.0, x, 1e-9)
        assertEquals(128.0, y, 1e-9)
        val (bx, _) = Terrarium.pixel(LngLat(116.39, 39.91), 12)
        assertEquals(3372, (bx / 256).toInt()) // Beijing's tile column at zoom 12
    }

    @Test fun samplesAPlaneExactlyAcrossTileEdges() {
        // Synthetic tiles: height rises 1 m per global pixel eastwards.
        var fetched = 0
        val dem = DemSampler(zoom = 12) { _, tx, _ ->
            fetched++
            IntArray(Terrarium.SIZE * Terrarium.SIZE) { i -> Terrarium.encode((tx * Terrarium.SIZE + i % Terrarium.SIZE + 0.5) * 1.0 - 863_000) }
        }
        val pts = listOf(LngLat(116.39, 39.91), LngLat(116.40, 39.91), LngLat(116.4140625, 39.91))
        val got = dem.at(pts)
        pts.forEachIndexed { k, p ->
            val (x, _) = Terrarium.pixel(p, 12)
            assertEquals(x - 863_000, got[k]!!, 0.02)
        }
        val before = fetched
        dem.at(pts)
        assertEquals(before, fetched) // cached
    }

    @Test fun refusesHugeAreas() {
        val dem = DemSampler(zoom = 12, maxTiles = 1) { _, _, _ -> null }
        assertThrows(IllegalArgumentException::class.java) { dem.at(listOf(LngLat(100.0, 30.0), LngLat(110.0, 30.0))) }
        assertNull(DemSampler(zoom = 12) { _, _, _ -> null }.at(listOf(LngLat(100.0, 30.0)))[0])
    }
}
