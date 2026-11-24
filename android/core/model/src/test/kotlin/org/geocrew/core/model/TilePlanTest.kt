package org.geocrew.core.model

import org.geocrew.core.model.tiles.BuiltInSources
import org.geocrew.core.model.tiles.BulkDownload
import org.geocrew.core.model.tiles.TileMath
import org.geocrew.core.model.tiles.TilePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePlanTest {
    @Test
    fun tileAddressesMatchTheUsualFormula() {
        assertEquals(0, TileMath.column(-180.0, 1))
        assertEquals(1, TileMath.column(180.0, 1))
        // Beijing at zoom 10: tile 843 / 387 in every slippy-map tool.
        assertEquals(843, TileMath.column(116.39, 10))
        assertEquals(387, TileMath.row(39.91, 10))
        val (lng, lat) = TileMath.corner(10, 843, 387)
        assertTrue(lng <= 116.39 && lat >= 39.91)
    }

    @Test
    fun countsAndLimits() {
        val b = Bounds(116.3, 39.8, 116.5, 40.0)
        val plan = TilePlan(b, 10, 12, 2)
        assertEquals(listOf(10, 11, 12), plan.ranges.map { it.zoom })
        var n = 0L
        plan.each { _, _, _ -> n++ }
        assertEquals(plan.tiles, n)
        assertEquals(plan.tiles * 2, plan.requests)
        val deepest = TilePlan.deepestWithin(b, 10, 18, 2)!!
        assertTrue(TilePlan(b, 10, deepest, 2).requests <= TilePlan.MAX_REQUESTS)
        assertTrue(deepest == 18 || TilePlan(b, 10, deepest + 1, 2).requests > TilePlan.MAX_REQUESTS)
        assertNull(TilePlan.deepestWithin(Bounds(70.0, 15.0, 135.0, 55.0), 12, 18, 2))
    }

    @Test
    fun osmIsNeverBulkDownloaded() {
        assertTrue(BulkDownload.of(BuiltInSources.OSM) is BulkDownload.Refused)
        assertTrue(BulkDownload.of(BuiltInSources.BLANK) is BulkDownload.Refused)
        assertTrue(BulkDownload.of(BuiltInSources.TIANDITU_IMAGE) is BulkDownload.Allowed)
    }
}
