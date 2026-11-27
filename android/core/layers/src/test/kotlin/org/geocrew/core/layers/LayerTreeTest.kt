package org.geocrew.core.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayerTreeTest {
    // Writes [places] back into [nodes], as the project does.
    private fun apply(nodes: List<TreeNode>, places: Map<String, Place>) =
        nodes.map { n -> places[n.id]?.let { n.copy(parent = it.parent, sort = it.sort) } ?: n }

    private val unordered = listOf(TreeNode("a", false, made = 0), TreeNode("b", false, made = 1), TreeNode("c", false, made = 2))

    @Test
    fun anUnorderedListGoesByWhenLayersWereMade() {
        assertEquals(listOf("a", "b", "c"), LayerTree.drawOrder(unordered))
        assertFalse(LayerTree.ordered(unordered))
    }

    @Test
    fun aGroupDrawsItsLayersTogetherWhereItIs() {
        var nodes = unordered + TreeNode("g", true)
        nodes = apply(nodes, LayerTree.move(nodes, "g", null, 1))
        nodes = apply(nodes, LayerTree.move(nodes, "c", "g", 0))
        nodes = apply(nodes, LayerTree.move(nodes, "a", "g", 5))
        // a went into the group from the top, past the end of it: last there.
        assertEquals(listOf("g", "b"), LayerTree.tree(nodes).map { it.first.id })
        assertEquals(listOf("c", "a"), LayerTree.tree(nodes).first().second.map { it.id })
        assertEquals(listOf("c", "a", "b"), LayerTree.drawOrder(nodes))
        assertTrue(LayerTree.ordered(nodes))
        assertTrue(nodes.filter { !it.group }.all { it.sort != null }, "every layer has its place once one is moved")

        // Up within the group, then out of it to the top.
        nodes = apply(nodes, LayerTree.move(nodes, "a", "g", 0))
        assertEquals(listOf("a", "c", "b"), LayerTree.drawOrder(nodes))
        nodes = apply(nodes, LayerTree.move(nodes, "a", null, 0))
        assertEquals(listOf("a", "g", "b"), LayerTree.tree(nodes).map { it.first.id })
        assertEquals(listOf("a", "c", "b"), LayerTree.drawOrder(nodes))
    }

    @Test
    fun groupsStayAtTheTopAndAnEmptyOneDrawsNothing() {
        var nodes = unordered + TreeNode("g", true) + TreeNode("h", true)
        nodes = apply(nodes, LayerTree.move(nodes, "h", "g", 0))
        assertEquals(null, nodes.single { it.id == "h" }.parent, "groups do not nest")
        assertEquals(listOf("a", "b", "c"), LayerTree.drawOrder(nodes))
        assertFailsWith<IllegalArgumentException> { LayerTree.move(nodes, "a", "nowhere", 0) }
        assertFailsWith<IllegalArgumentException> { LayerTree.move(nodes, "a", "b", 0) }
    }

    @Test
    fun aLayerOfAGroupThatHasGoneIsAtTheTop() {
        val nodes = listOf(TreeNode("a", false, parent = "gone", sort = 1), TreeNode("b", false, sort = 0))
        assertEquals(listOf("b", "a"), LayerTree.drawOrder(nodes))
    }

    @Test
    fun ungroupingPutsTheLayersWhereTheGroupWas() {
        var nodes = unordered + TreeNode("g", true)
        nodes = apply(nodes, LayerTree.move(nodes, "g", null, 1))
        nodes = apply(nodes, LayerTree.move(nodes, "b", "g", 0))
        nodes = apply(nodes, LayerTree.move(nodes, "c", "g", 1))
        assertEquals(listOf("a", "g"), LayerTree.tree(nodes).map { it.first.id })
        val places = LayerTree.ungroup(nodes, "g")
        assertEquals(setOf("a", "b", "c"), places.keys)
        nodes = apply(nodes.filter { it.id != "g" }, places)
        assertEquals(listOf("a", "b", "c"), LayerTree.drawOrder(nodes))
        assertTrue(nodes.all { it.parent == null })
    }
}
