package org.geocrew.core.project

/**
 * The colours layers are drawn in until they have a style of their own (ADR
 * 0006): by the order they were made, so a layer keeps its colour when the
 * list is reordered. The map and exports (KML) take the same ones.
 */
object LayerColors {
    val HEX = listOf("#e53935", "#1e88e5", "#43a047", "#fb8c00", "#8e24aa", "#00897b", "#6d4c41", "#d81b60")

    /** The colour of the layer made [made]th (0 first). */
    fun of(made: Int): String = HEX[Math.floorMod(made, HEX.size)]
}
