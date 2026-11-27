package org.geocrew.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// Dragging rows of the layer list: the order the list shows while dragging, and the one move that saves it.
class LayerOrderTest {
    private val group = TopNode.Group(GroupInfo("g1", "填图", vector = false, visible = true, layers = listOf("断层", "地层界线", "产状")))
    private val tree = listOf(TopNode.Single("野外点"), group, TopNode.Single("轨迹"))

    @Test
    fun rowsKnowWhereTheyAre() {
        assertEquals("" to 0, placeOf(tree, layerKey("野外点")))
        assertEquals("" to 1, placeOf(tree, groupKey("g1")))
        assertEquals("g1" to 2, placeOf(tree, layerKey("产状")))
        assertNull(placeOf(tree, "raster:r1"), "rasters do not move")
    }

    @Test
    fun onlyRowsBesideEachOtherSwap() {
        assertTrue(besides(tree, layerKey("野外点"), groupKey("g1")))
        assertTrue(besides(tree, layerKey("断层"), layerKey("产状")))
        assertFalse(besides(tree, layerKey("野外点"), layerKey("断层")), "into a group goes by its menu")
        assertFalse(besides(tree, layerKey("野外点"), "basemap:img"))
    }

    @Test
    fun aRowTakesThePlaceOfTheOneItPasses() {
        val down = movedRow(tree, layerKey("野外点"), groupKey("g1"))
        assertEquals(listOf(group, TopNode.Single("野外点"), TopNode.Single("轨迹")), down)
        val up = movedRow(tree, layerKey("轨迹"), layerKey("野外点"))
        assertEquals(listOf(TopNode.Single("轨迹"), TopNode.Single("野外点"), group), up)
        val inside = movedRow(tree, layerKey("产状"), layerKey("断层"))
        assertEquals(listOf("产状", "断层", "地层界线"), (inside[1] as TopNode.Group).group.layers)
        assertEquals(tree, movedRow(tree, layerKey("野外点"), layerKey("断层")), "not beside each other: nothing moves")
    }

    @Test
    fun aDropSavesOneMove() {
        val draft = movedRow(movedRow(tree, layerKey("野外点"), groupKey("g1")), layerKey("野外点"), layerKey("轨迹"))
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"layer": "野外点", "index": 2}"""), moveParams(tree, draft, layerKey("野外点")))
        val inGroup = movedRow(tree, layerKey("断层"), layerKey("产状"))
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"layer": "断层", "into": "g1", "index": 2}"""), moveParams(tree, inGroup, layerKey("断层")))
        val groupUp = movedRow(tree, groupKey("g1"), layerKey("野外点"))
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"group": "g1", "index": 0}"""), moveParams(tree, groupUp, groupKey("g1")))
        assertNull(moveParams(tree, tree, layerKey("轨迹")), "dropped where it was")
    }
}
