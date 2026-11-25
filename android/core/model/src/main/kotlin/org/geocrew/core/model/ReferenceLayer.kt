package org.geocrew.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * An existing map brought in to look at, not to edit: a published geological
 * map's units and faults, a mining licence boundary, last year's points. Its
 * shapes are drawn under the records, coloured by one attribute, and can be
 * tapped to read their attributes. They are not records: they do not sync,
 * do not show in lists, checks or exports, and stay on this phone.
 */
@Serializable
data class ReferenceLayer(
    val id: String,
    val name: String,
    /** Attribute whose value picks each shape's colour; null draws everything in one colour. */
    @SerialName("color_by") val colorBy: String? = null,
    val visible: Boolean = true,
    /** Fill opacity of areas, 0.1–1. */
    val opacity: Float = 0.45f,
    val count: Int = 0,
    /** Shared with the workspace by a leader (protocol v0 §2.2, key `reference_layers`); members only show or hide it. */
    val shared: Boolean = false,
    /** SHA-256 of the stored GeoJSON, once the layer has been shared or received. */
    val file: String? = null,
) {
    companion object {
        private val preferred = listOf(
            "lithology", "formation", "unit", "code", "地层", "地层代号", "代号", "岩性", "岩石", "单元", "填图单元", "符号", "name", "名称",
        )

        /**
         * A sensible attribute to colour by: a familiar name (lithology,
         * formation, 代号…) if present, else the attribute with between 2
         * and 40 different values shared by the most shapes.
         */
        fun guessColorBy(features: List<GeoFeature>): String? {
            if (features.isEmpty()) return null
            val keys = features.flatMap { it.properties.keys }.toSet() - setOf(Props.KIND, "layer", "id", "fid", "objectid", "OBJECTID")
            fun values(k: String) = features.mapNotNull { text(it, k) }
            preferred.forEach { p ->
                val k = keys.firstOrNull { it.equals(p, ignoreCase = true) } ?: return@forEach
                if (values(k).toSet().size in 1..200) return k
            }
            return keys.map { k -> k to values(k) }
                .filter { (_, v) -> v.toSet().size in 2..40 }
                .maxByOrNull { (_, v) -> v.size }?.first
        }

        fun text(f: GeoFeature, key: String): String? {
            val p = f.properties[key] as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            return p.content.trim().takeIf { it.isNotEmpty() }
        }

        /** Soft, distinct colours for values the project legend does not name. */
        private val palette = listOf(
            "#E6A157", "#8DB86B", "#6FA8DC", "#C27BA0", "#F1C232", "#76A5AF", "#E06666", "#93C47D",
            "#B4A7D6", "#F6B26B", "#A2C4C9", "#D5A6BD", "#FFD966", "#9FC5E8", "#B6D7A8", "#EA9999",
        )

        const val DEFAULT_COLOR = "#7E57C2"

        /**
         * The colour for [value]: the project legend's when it names the value,
         * else a stable colour from the palette, so the same unit keeps its
         * colour between sessions and phones.
         */
        fun colorFor(value: String?, legend: Legend = Legend()): String {
            if (value == null) return DEFAULT_COLOR
            legend.entries.firstOrNull { it.value.equals(value, ignoreCase = true) }?.let { return it.color }
            val h = value.lowercase().fold(7) { acc, c -> acc * 31 + c.code }
            return palette[Math.floorMod(h, palette.size)]
        }
    }
}
