package org.geocrew.core.layers

/**
 * A node of the layer list (ADR 0017): a layer or a group of layers. [parent]
 * is the group a layer is in (null at the top; a group is always at the top,
 * groups do not nest), [sort] its place among the nodes beside it (none yet:
 * after those with one), and [made] breaks ties, as layers were listed in the
 * order they were made before they were put in order.
 */
data class TreeNode(val id: String, val group: Boolean, val parent: String? = null, val sort: Int? = null, val made: Int = 0)

/** Where a node goes: its group (null at the top) and its place there. */
data class Place(val parent: String?, val sort: Int)

/**
 * The layer list as a tree: the order of its nodes, the order layers are
 * drawn in, and the places that move a node. Pure: it reads nodes and gives
 * back places, the caller stores them.
 */
object LayerTree {
    /** The nodes at the top in order, each with its layers in order (none for a layer). */
    fun tree(nodes: List<TreeNode>): List<Pair<TreeNode, List<TreeNode>>> {
        val groups = nodes.filter { it.group }.map { it.id }.toSet()
        // A layer in a group that is not there any more is at the top.
        fun parentOf(n: TreeNode) = if (n.group) null else n.parent?.takeIf { it in groups }
        val order = compareBy<TreeNode>({ it.sort ?: Int.MAX_VALUE }, { it.made }, { it.id })
        val top = nodes.filter { parentOf(it) == null }.sortedWith(order)
        return top.map { node -> node to if (node.group) nodes.filter { parentOf(it) == node.id }.sortedWith(order) else emptyList() }
    }

    /** The layers in the order they are drawn, the first on top: each top node in turn, a group by its layers in turn. */
    fun drawOrder(nodes: List<TreeNode>): List<String> =
        tree(nodes).flatMap { (node, children) -> if (node.group) children.map { it.id } else listOf(node.id) }

    /**
     * The places that put node [id] at [index] among the nodes of group
     * [parent] (null for the top; a group always goes at the top), the first
     * place 0: every node at the top and in that group numbered in its new
     * order, so none is left without a place.
     */
    fun move(nodes: List<TreeNode>, id: String, parent: String?, index: Int): Map<String, Place> {
        val node = requireNotNull(nodes.firstOrNull { it.id == id }) { "there is no node $id" }
        val target = if (node.group) null else parent
        require(target == null || nodes.any { it.id == target && it.group }) { "there is no group $target" }
        val tree = tree(nodes)
        val top = tree.map { it.first.id }.toMutableList()
        val children = tree.associate { (n, c) -> n.id to c.map { it.id }.toMutableList() }
        // Out of where it was...
        top.remove(id)
        children.values.forEach { it.remove(id) }
        // ...and into its new place.
        val siblings = if (target == null) top else children.getValue(target)
        siblings.add(index.coerceIn(0, siblings.size), id)
        val places = LinkedHashMap<String, Place>()
        top.forEachIndexed { i, n -> places[n] = Place(null, i) }
        for ((group, list) in children) list.forEachIndexed { i, n -> places[n] = Place(group, i) }
        return places
    }

    /**
     * The places when group [group] goes: its layers take its place at the
     * top, in their order; the other nodes at the top keep theirs.
     */
    fun ungroup(nodes: List<TreeNode>, group: String): Map<String, Place> {
        require(nodes.any { it.id == group && it.group }) { "there is no group $group" }
        val top = tree(nodes).flatMap { (node, children) -> if (node.id == group) children.map { it.id } else listOf(node.id) }
        return top.withIndex().associate { (i, n) -> n to Place(null, i) }
    }

    /** Whether any node has been put in its place: then a new layer goes on top, as in QGIS; else it goes last, as made. */
    fun ordered(nodes: List<TreeNode>): Boolean = nodes.any { it.sort != null }
}
