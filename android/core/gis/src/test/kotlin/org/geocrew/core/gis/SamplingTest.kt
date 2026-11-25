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

    @Test fun routeByFollowsAnAsymmetricCostAndKeepsTheStart() {
        val rnd = java.util.Random(7)
        repeat(20) {
            val n = 7
            val c = Array(n) { DoubleArray(n) { rnd.nextDouble() * 10 } }
            val path = SamplingPlan.routeBy(n) { a, b -> c[a][b] }
            assertEquals(0, path.first())
            assertEquals((0 until n).toSet(), path.toSet())
            // Never worse than walking to the nearest stop each time.
            val left = (1 until n).toMutableSet()
            val greedy = mutableListOf(0)
            while (left.isNotEmpty()) left.minBy { c[greedy.last()][it] }.also { greedy += it; left -= it }
            assertTrue(SamplingPlan.cost(path) { a, b -> c[a][b] } <= SamplingPlan.cost(greedy) { a, b -> c[a][b] } + 1e-9)
        }
    }

    @Test fun routeByPrefersGoingUpOnceOverUpAndDownTwice() {
        // A valley floor at the start, two stops up the east slope and one on the floor to the west.
        val x = doubleArrayOf(0.0, 300.0, 600.0, -400.0)
        val z = doubleArrayOf(0.0, 150.0, 300.0, 0.0)
        fun hours(a: Int, b: Int): Double {
            val run = kotlin.math.abs(x[b] - x[a])
            return run / 1000 / org.geocrew.core.model.Hiking.speedKmh((z[b] - z[a]) / run)
        }
        val path = SamplingPlan.routeBy(4, ::hours)
        // By distance the west stop would come last, after walking back down.
        assertEquals(listOf(0, 1, 2), SamplingPlan.route(listOf(300.0 to 0.0, 600.0 to 0.0, -400.0 to 0.0), 0.0 to 0.0))
        // West first, then up the slope: one climb and no descent back to the floor.
        assertEquals(listOf(0, 3, 1, 2), path)
    }

    @Test fun walkHoursAreSlowerUphill() {
        val a = org.geocrew.core.model.LngLat(110.0, 30.0)
        val b = org.geocrew.core.model.LngLat(110.005, 30.0)
        // Ground rising 200 m over the roughly 480 m between them.
        val dem = org.geocrew.core.model.Elevation { pts -> pts.map { (it.lng - 110.0) / 0.005 * 200 } }
        val h = SamplingPlan.walkHours(listOf(a, b), dem, 1.0)!!
        assertTrue("up ${h[0][1]} down ${h[1][0]}", h[0][1] > 1.2 * h[1][0])
        assertEquals(0.0, h[0][0], 0.0)
        assertEquals(null, SamplingPlan.walkHours(listOf(a, b), { pts -> pts.map { null } }, 1.0))
    }
}
