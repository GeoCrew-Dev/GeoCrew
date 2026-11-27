package org.geocrew.android

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The layer list's key for a layer's row. */
fun layerKey(name: String): String = "layer:$name"

/** The layer list's key for a group's row. */
fun groupKey(id: String): String = "group:$id"

/**
 * Where row [key] is in [tree]: the group it is in ("" at the top) and its
 * place among the rows beside it; null for a row that does not move (a
 * raster, a basemap, a heading).
 */
fun placeOf(tree: List<TopNode>, key: Any): Pair<String, Int>? {
    for ((i, node) in tree.withIndex()) {
        when (node) {
            is TopNode.Single -> if (key == layerKey(node.layer)) return "" to i
            is TopNode.Group -> {
                if (key == groupKey(node.group.id)) return "" to i
                val at = node.group.layers.indexOfFirst { key == layerKey(it) }
                if (at >= 0) return node.group.id to at
            }
        }
    }
    return null
}

/** Whether row [key] can take the place of row [to] in [tree]: both move, beside each other at the top or in one group. */
fun besides(tree: List<TopNode>, key: Any, to: Any): Boolean {
    val a = placeOf(tree, key) ?: return false
    val b = placeOf(tree, to) ?: return false
    return a.first == b.first
}

/** [tree] with row [key] where row [to] was, and the rows between moved over by one; [tree] itself for rows not beside each other. */
fun movedRow(tree: List<TopNode>, key: Any, to: Any): List<TopNode> {
    val (parent, from) = placeOf(tree, key) ?: return tree
    val (other, at) = placeOf(tree, to) ?: return tree
    if (parent != other || from == at) return tree
    fun <T> List<T>.moved(): List<T> = toMutableList().apply { add(at, removeAt(from)) }
    if (parent == "") return tree.moved()
    return tree.map { node ->
        if (node is TopNode.Group && node.group.id == parent) TopNode.Group(node.group.copy(layers = node.group.layers.moved())) else node
    }
}

/**
 * The layer.move parameters (Workspace.LAYER_MOVE) that put row [key] where
 * it is in [draft], from where it is in [tree]; null when it has not moved.
 */
fun moveParams(tree: List<TopNode>, draft: List<TopNode>, key: Any): JsonObject? {
    val (parent, at) = placeOf(draft, key) ?: return null
    if (placeOf(tree, key) == parent to at) return null
    val name = key.toString()
    return buildJsonObject {
        if (name.startsWith("group:")) {
            put("group", name.removePrefix("group:"))
        } else {
            put("layer", name.removePrefix("layer:"))
            if (parent != "") put("into", parent)
        }
        put("index", at)
    }
}
