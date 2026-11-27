package org.geocrew.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class MapLabelsTest {
    private fun geometry(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun near(expected: Double, actual: Double, within: Double = 1e-9) = assertEquals(expected, actual, within)

    @Test
    fun lineLabelSitsHalfwayAlongItsLength() {
        val (at, rotate, kind) = assertNotNull(MapLabels.anchor(geometry("""{"type":"LineString","coordinates":[[0,0],[1,0],[3,0]]}""")))
        near(1.5, at.first)
        near(0.0, at.second)
        near(0.0, rotate)
        assertEquals("line", kind)
    }

    @Test
    fun lineLabelTurnsWithTheLineAndStaysUpright() {
        // Going north-east near the equator (the longitude shrinks a little by the latitude's cosine): the text rises to the right, a turn of 45 degrees anticlockwise.
        near(-45.0, MapLabels.anchor(geometry("""{"type":"LineString","coordinates":[[0,0],[1,1]]}"""))!!.second, 0.01)
        // The same line drawn the other way would be upside down, so it is turned round.
        near(-45.0, MapLabels.anchor(geometry("""{"type":"LineString","coordinates":[[1,1],[0,0]]}"""))!!.second, 0.01)
        // Straight north: a quarter turn.
        near(-90.0, MapLabels.anchor(geometry("""{"type":"LineString","coordinates":[[0,0],[0,1]]}"""))!!.second)
    }

    @Test
    fun multiLineUsesItsLongestPart() {
        val (at, _, _) = assertNotNull(MapLabels.anchor(geometry("""{"type":"MultiLineString","coordinates":[[[10,0],[10.1,0]],[[0,0],[2,0]]]}""")))
        near(1.0, at.first)
    }

    @Test
    fun areaLabelSitsAtTheCentroid() {
        val (at, rotate, kind) = assertNotNull(MapLabels.anchor(geometry("""{"type":"Polygon","coordinates":[[[0,0],[4,0],[4,2],[0,2],[0,0]]]}""")))
        near(2.0, at.first)
        near(1.0, at.second)
        near(0.0, rotate)
        assertEquals("area", kind)
    }

    @Test
    fun multiPolygonUsesItsBiggestPart() {
        val (at, _, _) = assertNotNull(
            MapLabels.anchor(geometry("""{"type":"MultiPolygon","coordinates":[[[[10,10],[11,10],[11,11],[10,10]]],[[[0,0],[2,0],[2,2],[0,2],[0,0]]]]}""")),
        )
        near(1.0, at.first)
        near(1.0, at.second)
    }

    @Test
    fun flatRingFallsBackToTheMiddleOfItsPoints() {
        val (at, _, _) = assertNotNull(MapLabels.anchor(geometry("""{"type":"Polygon","coordinates":[[[0,0],[1,0],[2,0],[0,0]]]}""")))
        near(1.0, at.first)
    }

    @Test
    fun pointsAndBrokenGeometriesGetNoShapeLabel() {
        assertNull(MapLabels.anchor(geometry("""{"type":"Point","coordinates":[0,0]}""")))
        assertNull(MapLabels.anchor(geometry("""{"type":"LineString","coordinates":[[0,0]]}""")))
        assertNull(MapLabels.anchor(null))
    }
}
