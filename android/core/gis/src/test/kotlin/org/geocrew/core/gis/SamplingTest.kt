package org.geocrew.core.gis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SamplingTest {
    private val vg = Variogram(VariogramModel.EXPONENTIAL, 0.0, 1.0, 300.0)

    // Samples in the west half of a 1 km square; candidates on a 50 m grid.
    private val xs = doubleArrayOf(0.0, 100.0, 0.0, 100.0, 50.0, 200.0)
    private val ys = doubleArrayOf(0.0, 0.0, 100.0, 100.0, 500.0, 900.0)
    private val cells = (0..20).flatMap { i -> (0..20).map { j -> i * 50.0 to j * 50.0 } }

    @Test fun sequentialPicksSpreadOut() {
        val picks = SamplingPlan.pick(xs, ys, vg, cells, 4)
        assertEquals(4, picks.size)
        // The first pick is the most uncertain cell, far from the samples.
        assertTrue(picks.toString(), picks[0].x > 700)
        // Each pick lowers the uncertainty, so later picks are no more uncertain than earlier ones
        // and never land next to each other.
        assertTrue(picks.zipWithNext().all { (a, b) -> b.sd <= a.sd + 1e-9 })
        for (i in picks.indices) for (j in i + 1 until picks.size) {
            assertTrue(hypot(picks[i].x - picks[j].x, picks[i].y - picks[j].y) > 100)
        }
    }

    @Test fun travelWeightKeepsPicksNearTheStart() {
        val start = 0.0 to 0.0
        val far = SamplingPlan.pick(xs, ys, vg, cells, 3, start = start)
        val near = SamplingPlan.pick(xs, ys, vg, cells, 3, start = start, travel = 4.0, scale = 1000.0)
        fun walk(p: List<SamplingPlan.Pick>) = SamplingPlan.length(listOf(start) + p.map { it.x to it.y })
        assertTrue(walk(near) < walk(far))
    }

    @Test fun routeIsShortAndStartsAtTheStart() {
        val pts = listOf(300.0 to 0.0, 100.0 to 0.0, 400.0 to 0.0, 200.0 to 0.0)
        val order = SamplingPlan.route(pts, 0.0 to 0.0)
        assertEquals(listOf(1, 3, 0, 2), order)
        // Without a start the first point stays first.
        val open = SamplingPlan.route(pts)
        assertEquals(0, open.first())
        assertEquals(4, open.toSet().size)
        assertEquals(400.0, SamplingPlan.length(open.map { pts[it] }), 1e-9)
    }
}
