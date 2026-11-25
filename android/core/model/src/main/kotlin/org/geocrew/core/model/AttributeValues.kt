package org.geocrew.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/**
 * Tidying an attribute across a project's records before mapping or export:
 * which text attributes are in use, the values each takes and how often, the
 * spellings that are probably one value ("花岗岩" and "花岗岩 ", "Granite"
 * and "granite"), and renaming or merging a value on every record at once.
 * Legends, interpolation and exports group by the exact text, so one stray
 * spelling makes a second legend entry.
 */
object AttributeValues {
    data class Key(val key: String, val label: String, val records: Int, val values: Int)

    data class Value(val value: String, val count: Int)

    /** Built-in attributes worth tidying, with their names. */
    private val BUILT_IN = mapOf(Props.LITHOLOGY to "岩性")

    /** simplestyle colours and widths are styling, not data. */
    private fun styling(key: String) = key.startsWith("stroke") || key.startsWith("fill") || key.startsWith("marker-")

    /**
     * Text attributes used by the live records, form fields first in form
     * order, then by how many records use them. Numbers are left out: they
     * are measured, not spelled.
     */
    fun keys(features: List<GeoFeature>, fields: List<FieldTemplate>): List<Key> {
        val live = features.filter { !it.deleted }
        val counts = linkedMapOf<String, MutableMap<String, Int>>()
        for (f in live) for ((k, v) in f.properties) {
            if (k in Props.MANAGED && k !in BUILT_IN || styling(k)) continue
            val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (text.isEmpty()) continue
            counts.getOrPut(k) { mutableMapOf() }.merge(text, 1, Int::plus)
        }
        val order = fields.map { it.key }
        return counts.map { (k, m) ->
            Key(k, fields.firstOrNull { it.key == k }?.label?.ifBlank { null } ?: BUILT_IN[k] ?: k, m.values.sum(), m.size)
        }.sortedWith(
            compareBy<Key> { order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenByDescending { it.records }.thenBy { it.key },
        )
    }

    /** The values [key] takes on the live records, trimmed, most used first. */
    fun values(features: List<GeoFeature>, key: String): List<Value> {
        val m = mutableMapOf<String, Int>()
        for (f in features) {
            if (f.deleted) continue
            val text = (f.properties[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (text.isNotEmpty()) m.merge(text, 1, Int::plus)
        }
        return m.map { (v, n) -> Value(v, n) }.sortedWith(compareByDescending<Value> { it.count }.thenBy { it.value })
    }

    /**
     * How two spellings compare once case, full-width forms, spaces and
     * trailing punctuation are set aside, e.g. "Ｇｒａｎｉｔｅ。" and "granite".
     */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().replace(Regex("\\s+"), "").trimEnd('.', '。', ',', '，', ';', '；')

    /**
     * Groups of values that are probably one, each led by its most used
     * spelling, which is the one to keep.
     */
    fun similar(values: List<Value>): List<List<Value>> =
        values.groupBy { normalize(it.value) }.values.filter { it.size > 1 }
            .map { g -> g.sortedWith(compareByDescending<Value> { it.count }.thenBy { it.value }) }
            .sortedByDescending { g -> g.sumOf { it.count } }

    /**
     * [props] with [key] changed from any of [from] (compared trimmed) to
     * [to]; a blank [to] removes the attribute. Null when the record does not
     * carry one of [from], so it is left untouched.
     */
    fun rename(props: JsonObject, key: String, from: Set<String>, to: String): JsonObject? {
        val cur = (props[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        if (cur !in from) return null
        val next = to.trim()
        if (next == (props[key] as JsonPrimitive).content) return null
        return props.edit { if (next.isEmpty()) remove(key) else this[key] = next }
    }
}
