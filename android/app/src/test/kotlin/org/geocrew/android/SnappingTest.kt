package org.geocrew.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SnappingTest {
    // A square side of 100 px, its corners in order.
    private val square = listOf(Px(0.0, 0.0), Px(100.0, 0.0), Px(100.0, 100.0), Px(0.0, 100.0))
    private val sides = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)

    @Test
    fun aVertexNearByComesFirst() {
        // Nearer to the top side (4 px) than to the corner (about 8 px), but the corner wins.
        assertEquals(SnapHit.Vertex(1), snapOnScreen(Px(93.0, 4.0), square, sides, reach = 16.0))
    }

    @Test
    fun elseTheNearestPointOnAnEdge() {
        assertEquals(SnapHit.Edge(Px(50.0, 0.0)), snapOnScreen(Px(50.0, 10.0), square, sides, reach = 16.0))
        assertEquals(SnapHit.Edge(Px(100.0, 40.0)), snapOnScreen(Px(108.0, 40.0), square, sides, reach = 16.0))
    }

    @Test
    fun nothingOutOfReach() {
        assertNull(snapOnScreen(Px(50.0, 50.0), square, sides, reach = 16.0))
        assertNull(snapOnScreen(Px(50.0, 50.0), emptyList(), emptyList(), reach = 16.0))
    }

    @Test
    fun theNearestPointStaysOnTheSegment() {
        assertEquals(Px(0.0, 0.0), nearestOn(Px(-30.0, 5.0), Px(0.0, 0.0), Px(100.0, 0.0)))
        assertEquals(Px(100.0, 0.0), nearestOn(Px(130.0, -5.0), Px(0.0, 0.0), Px(100.0, 0.0)))
        assertEquals(Px(3.0, 4.0), nearestOn(Px(9.0, 9.0), Px(3.0, 4.0), Px(3.0, 4.0)), "a side of no length")
    }

    private fun geometry(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun shapesComeApartIntoTheirParts() {
        assertEquals(listOf(listOf(1.0 to 2.0)), partsOf(geometry("""{"type": "Point", "coordinates": [1, 2]}""")))
        assertEquals(listOf(listOf(0.0 to 0.0, 1.0 to 1.0)), partsOf(geometry("""{"type": "LineString", "coordinates": [[0, 0], [1, 1]]}""")))
        val rings = partsOf(geometry("""{"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 0], [1, 1], [0, 0]], [[0.2, 0.2], [0.4, 0.2], [0.4, 0.4], [0.2, 0.2]]], [[[5, 5], [6, 5], [6, 6], [5, 5]]]]}"""))
        assertEquals(3, rings.size, "each ring of each area")
        assertEquals(2, partsOf(geometry("""{"type": "MultiPoint", "coordinates": [[0, 0], [1, 1]]}""")).size)
        assertEquals(emptyList(), partsOf(null))
    }
}
