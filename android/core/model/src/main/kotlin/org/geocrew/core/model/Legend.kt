package org.geocrew.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** One value of the legend's attribute and the colour records with it get. */
@Serializable
data class LegendEntry(val value: String, val color: String)

/**
 * Colours records by one attribute (图例), e.g. lithology or formation, so
 * a map reads like a geological sketch map. In a team project the leader
 * maintains it and it travels in the workspace settings next to the form
 * (protocol v0 §2.2, key `legend`), so every member's map uses the same
 * colours. A record's own simplestyle colour (from analysis output or an
 * imported file) still wins.
 */
@Serializable
data class Legend(
    /** Property key whose value picks the colour; blank turns the legend off. */
    val key: String = "",
    val entries: List<LegendEntry> = emptyList(),
) {
    val active: Boolean get() = key.isNotBlank() && entries.isNotEmpty()

    /** The colour for [f], or null when the legend is off or has no entry for its value. */
    fun colorOf(f: GeoFeature): String? {
        if (!active) return null
        val v = valueOf(f) ?: return null
        return entries.firstOrNull { it.value.equals(v, ignoreCase = true) }?.color
    }

    /** [f]'s value of the legend attribute as text, or null when it has none. */
    fun valueOf(f: GeoFeature): String? {
        val p = f.properties[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content.trim().takeIf { it.isNotEmpty() }
    }

    /** Entries that [features] actually use, in legend order, for the map's key. */
    fun used(features: List<GeoFeature>): List<LegendEntry> {
        if (!active) return emptyList()
        val values = features.mapNotNull(::valueOf).map { it.lowercase() }.toSet()
        return entries.filter { it.value.lowercase() in values }
    }

    /** Adds or replaces the entry for [entry]'s value; a blank value is ignored. */
    fun put(entry: LegendEntry): Legend {
        val clean = entry.copy(value = entry.value.trim())
        if (clean.value.isEmpty()) return this
        val i = entries.indexOfFirst { it.value.equals(clean.value, ignoreCase = true) }
        return copy(entries = if (i < 0) entries + clean else entries.toMutableList().also { it[i] = clean })
    }

    fun remove(value: String) = copy(entries = entries.filterNot { it.value.equals(value, ignoreCase = true) })

    /**
     * Adds an entry for each of [values] the legend lacks, with a colour
     * from [suggestColor]; values already present keep their colour.
     */
    fun withValues(values: List<String>): Legend = values.fold(this) { l, v ->
        val t = v.trim()
        if (t.isEmpty() || l.entries.any { it.value.equals(t, ignoreCase = true) }) l
        else l.put(LegendEntry(t, suggestColor(t, l.entries.map { it.color })))
    }

    companion object {
        private val HEX = Regex("#[0-9a-fA-F]{6}")

        fun isColor(s: String) = HEX.matches(s)

        /** Drops entries a bad or newer settings file could carry: blank values, bad colours, repeats. */
        fun clean(l: Legend): Legend {
            val seen = mutableSetOf<String>()
            val ok = l.entries.mapNotNull { e ->
                val v = e.value.trim()
                if (v.isEmpty() || !isColor(e.color) || !seen.add(v.lowercase())) null else LegendEntry(v, e.color)
            }
            return Legend(l.key.trim(), ok)
        }

        /** Colours to choose from, distinct on a map and on paper. */
        val palette: List<String> = listOf(
            "#E57373", "#F06292", "#BA68C8", "#7986CB", "#4FC3F7", "#4DB6AC",
            "#81C784", "#DCE775", "#FFD54F", "#FFB74D", "#A1887F", "#90A4AE",
        )

        /**
         * Rough conventional colours by rock name, loosely after common
         * geological map practice: granites pink, mafic rocks green,
         * sandstones yellow, carbonates blue, schists purple. The most
         * specific keyword wins; anything else gets the first palette
         * colour not used yet.
         */
        private val conventional: List<Pair<String, String>> = listOf(
            "花岗" to "#F48FB1", "二长" to "#F48FB1", "正长" to "#EF9A9A", "伟晶" to "#F8BBD0",
            "闪长" to "#CE93D8", "辉长" to "#66BB6A", "辉绿" to "#81C784", "橄榄" to "#2E7D32", "辉石" to "#388E3C",
            "玄武" to "#43A047", "安山" to "#AED581", "流纹" to "#FFCCBC", "英安" to "#FFAB91", "凝灰" to "#FFE0B2",
            "砾岩" to "#FFB74D", "砾石" to "#FFCC80", "粉砂" to "#FFF59D", "砂岩" to "#FFEE58",
            "泥岩" to "#BCAAA4", "页岩" to "#A1887F", "黏土" to "#D7CCC8", "煤" to "#424242",
            "白云" to "#4FC3F7", "灰岩" to "#64B5F6", "泥灰岩" to "#90CAF9", "大理" to "#B3E5FC", "硅质" to "#B0BEC5",
            "板岩" to "#9FA8DA", "千枚" to "#B39DDB", "片岩" to "#9575CD", "片麻" to "#BA68C8", "石英岩" to "#EEEEEE",
            "角闪" to "#558B2F", "混合" to "#E1BEE7", "糜棱" to "#78909C", "矽卡" to "#8D6E63", "蛇纹" to "#00897B",
            "黄土" to "#FFE082", "冲积" to "#FFF8E1", "坡积" to "#FFECB3",
        )

        fun suggestColor(value: String, taken: List<String> = emptyList()): String {
            conventional.filter { value.contains(it.first) }.maxByOrNull { it.first.length }?.let { return it.second }
            val used = taken.map { it.lowercase() }.toSet()
            return palette.firstOrNull { it.lowercase() !in used } ?: palette[taken.size % palette.size]
        }
    }
}
