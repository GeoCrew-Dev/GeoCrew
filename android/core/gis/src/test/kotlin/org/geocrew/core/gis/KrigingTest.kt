package org.geocrew.core.gis

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sin
import kotlin.random.Random

class KrigingTest {
    @Test fun fitRecoversAKnownVariogram() {
        val truth = Variogram(VariogramModel.SPHERICAL, nugget = 0.2, partialSill = 1.0, range = 300.0)
        val lags = (1..12).map { Variogram.Lag(it * 25.0, truth.gamma(it * 25.0), 100) }
        val fit = Variogram.fit(lags, VariogramModel.SPHERICAL)!!
        assertEquals(0.2, fit.nugget, 0.05)
        assertEquals(1.2, fit.sill, 0.05)
        assertEquals(300.0, fit.range, 30.0)
    }

    @Test fun krigingHonoursSamplesAndIsUncertainFarAway() {
        val x = doubleArrayOf(0.0, 100.0, 0.0, 100.0, 50.0)
        val y = doubleArrayOf(0.0, 0.0, 100.0, 100.0, 50.0)
        val v = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 2.5)
        val ok = OrdinaryKriging(x, y, v, Variogram(VariogramModel.EXPONENTIAL, 0.0, 1.0, 200.0))
        val at = ok.estimate(100.0, 0.0)
        assertEquals(2.0, at.value, 1e-6)
        assertEquals(0.0, at.variance, 1e-6)
        val near = ok.estimate(60.0, 50.0).variance
        val far = ok.estimate(400.0, 400.0).variance
        assertTrue("far $far should exceed near $near", far > near)
        // Weights sum to one, so a constant field is reproduced everywhere.
        val flat = OrdinaryKriging(x, y, DoubleArray(5) { 7.0 }, Variogram(VariogramModel.GAUSSIAN, 0.1, 1.0, 150.0))
        assertEquals(7.0, flat.estimate(30.0, 80.0).value, 1e-6)
    }

    @Test fun toolMapsAFieldAndSuggestsSamplesInGaps() {
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val rnd = Random(4)
        // Samples cover the west half of a 2 km square only.
        val pts = (0 until 40).map { i ->
            val lng = 116.0 + rnd.nextDouble() * 0.012
            val lat = 40.0 + rnd.nextDouble() * 0.018
            GeoFeature(
                id = "p$i", projectId = "p", kind = FeatureKind.POINT, geometry = Geometry.Point(LngLat(lng, lat)),
                properties = buildProperties { this["grade"] = 5 + 2 * sin((lng - 116) * 500) + (lat - 40) * 100 },
                createdAt = t0, sync = SyncInfo(updatedAt = t0),
            )
        } + GeoFeature(
            id = "east", projectId = "p", kind = FeatureKind.POINT, geometry = Geometry.Point(LngLat(116.024, 40.009)),
            properties = buildProperties { this["grade"] = 6.0 }, createdAt = t0, sync = SyncInfo(updatedAt = t0),
        )
        val out = KrigingTool().run(ToolInput(pts, mapOf("field" to "grade", "suggest" to "2")))
        val cells = out.features.filter { it.kind == FeatureKind.POLYGON }
        val picks = out.features.filter { it.kind == FeatureKind.POINT }
        assertTrue(cells.size > 100)
        assertEquals(2, picks.size)
        assertTrue(cells.all { it.properties.double(KrigingTool.SD) != null })
        // The most uncertain places are away from the dense western samples.
        val west = pts.dropLast(1).map { (it.geometry as Geometry.Point).position.lng }.max()
        assertTrue(picks.any { (it.geometry as Geometry.Point).position.lng > west })
        assertNotNull(out.summary.takeIf { "变程" in it })
    }
}
