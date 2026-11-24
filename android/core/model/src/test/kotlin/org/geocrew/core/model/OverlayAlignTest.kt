package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayAlignTest {
    private val corners = listOf(LngLat(110.0, 30.1), LngLat(110.1, 30.1), LngLat(110.1, 30.0), LngLat(110.0, 30.0))

    private fun close(a: LngLat, b: LngLat) = assertEquals(0.0, Geodesy.distance(a, b), 0.5)

    @Test
    fun onePairMovesTheWholePicture() {
        val out = OverlayAlign.corners(corners, listOf(OverlayAlign.Pair(LngLat(110.05, 30.05), LngLat(110.06, 30.05))))!!
        close(LngLat(110.01, 30.1), out[0])
        close(LngLat(110.11, 30.0), out[2])
    }

    @Test
    fun twoPairsTurnAndScaleAndPassThroughBoth() {
        val a = OverlayAlign.Pair(LngLat(110.0, 30.0), LngLat(110.02, 30.01))
        val b = OverlayAlign.Pair(LngLat(110.1, 30.1), LngLat(110.1, 30.12))
        // Pinning two corners of the picture moves them exactly there.
        val out = OverlayAlign.corners(corners, listOf(a, b))!!
        close(a.to, out[3])
        close(b.to, out[1])
        assertNull(OverlayAlign.corners(corners, listOf(a, a.copy(to = b.to))))
    }

    @Test
    fun threeOrMorePairsFitAnAffineAndReportResiduals() {
        // A shear no similarity can produce: the top edge slides east.
        fun shear(p: LngLat) = LngLat(p.lng + (p.lat - 30.0) * 0.2, p.lat)
        val spots = listOf(LngLat(110.0, 30.0), LngLat(110.1, 30.0), LngLat(110.05, 30.1), LngLat(110.02, 30.06))
        val pairs = spots.map { OverlayAlign.Pair(it, shear(it)) }
        val out = OverlayAlign.corners(corners, pairs)!!
        corners.zip(out).forEach { (c, o) -> close(shear(c), o) }
        OverlayAlign.residuals(pairs).forEach { assertEquals(0.0, it, 0.5) }
        val off = pairs.toMutableList().apply { set(3, this[3].copy(to = LngLat(110.03, 30.06))) }
        assert(OverlayAlign.residuals(off).max() > 100)
        // Three points on one line cannot fix the direction across it.
        assertNull(OverlayAlign.corners(corners, listOf(0.0, 0.05, 0.1).map { OverlayAlign.Pair(LngLat(110 + it, 30.0), LngLat(110 + it, 30.01)) }))
    }
}
