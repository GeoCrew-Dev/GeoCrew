package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VertexEditTest {
    private val at = Instant.parse("2026-11-24T02:00:00Z")

    // About 85 m east and 111 m north per 0.001° at 40°N.
    private fun p(x: Double, y: Double) = LngLat(116.0 + x * 0.001, 40.0 + y * 0.001)

    private fun rec(kind: FeatureKind, g: Geometry?) =
        GeoFeature("a", "p", kind, g, buildProperties { }, at, SyncInfo(updatedAt = at))

    @Test fun tellsWhatCanBeReshaped() {
        assertTrue(VertexEdit.of(rec(FeatureKind.GEO_POINT, Geometry.Point(p(0.0, 0.0)))) is VertexEdit.Edit.Move)
        val line = VertexEdit.of(rec(FeatureKind.LINE, Geometry.LineString(listOf(p(0.0, 0.0), p(1.0, 0.0)))))
        assertEquals(VertexEdit.Edit.Reshape(listOf(p(0.0, 0.0), p(1.0, 0.0)), false), line)
        val square = Geometry.Polygon.of(listOf(p(0.0, 0.0), p(1.0, 0.0), p(1.0, 1.0), p(0.0, 1.0)))
        val poly = VertexEdit.of(rec(FeatureKind.POLYGON, square)) as VertexEdit.Edit.Reshape
        assertEquals("the ring comes back open", 4, poly.vertices.size)
        assertTrue(poly.closed)
        val track = Geometry.LineString(listOf(p(0.0, 0.0), p(1.0, 0.0)))
        assertTrue(VertexEdit.of(rec(FeatureKind.TRACK, track)) is VertexEdit.Edit.No)
        assertTrue(VertexEdit.of(rec(FeatureKind.LINE, null)) is VertexEdit.Edit.No)
        val holed = Geometry.Polygon(square.rings + square.rings)
        assertTrue(VertexEdit.of(rec(FeatureKind.POLYGON, holed)) is VertexEdit.Edit.No)
    }

    @Test fun picksTheVertexUnderTheFinger() {
        val v = listOf(p(0.0, 0.0), p(1.0, 0.0), p(2.0, 0.0))
        assertEquals(1, VertexEdit.near(v, p(1.1, 0.05), 20.0))
        assertNull(VertexEdit.near(v, p(1.5, 0.0), 20.0))
    }

    @Test fun putsANewVertexIntoTheNearestSegment() {
        val line = listOf(p(0.0, 0.0), p(2.0, 0.0), p(2.0, 2.0))
        assertEquals(listOf(p(0.0, 0.0), p(1.0, 0.3), p(2.0, 0.0), p(2.0, 2.0)), VertexEdit.insert(line, p(1.0, 0.3), false))
        assertEquals(listOf(p(0.0, 0.0), p(2.0, 0.0), p(1.8, 1.0), p(2.0, 2.0)), VertexEdit.insert(line, p(1.8, 1.0), false))
        // Beyond either end of an open line it grows there.
        assertEquals(p(-1.0, 0.0), VertexEdit.insert(line, p(-1.0, 0.0), false).first())
        assertEquals(p(2.0, 3.0), VertexEdit.insert(line, p(2.0, 3.0), false).last())
        // A polygon's closing edge takes it too.
        val ring = listOf(p(0.0, 0.0), p(2.0, 0.0), p(2.0, 2.0), p(0.0, 2.0))
        assertEquals(listOf(p(0.0, 0.0), p(2.0, 0.0), p(2.0, 2.0), p(0.0, 2.0), p(-0.2, 1.0)), VertexEdit.insert(ring, p(-0.2, 1.0), true))
    }
}
