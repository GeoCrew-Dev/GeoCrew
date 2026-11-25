package org.geocrew.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 沿用上一点: along a traverse, neighbouring points often share the
 * lithology and most form answers (formation, weathering, outcrop type…).
 * This picks what a fresh record could take over from the previous record
 * of the same kind. Names, descriptions, attitudes, photos, samples and
 * anything the record already has are never copied.
 */
object RecordCarry {
    /** The record of [current]'s kind made just before it in [all], if any. */
    fun previous(all: List<GeoFeature>, current: GeoFeature): GeoFeature? = all
        .filter { it.id != current.id && !it.deleted && it.kind == current.kind && it.createdAt <= current.createdAt }
        .maxWithOrNull(compareBy<GeoFeature> { it.createdAt }.thenBy { it.id })

    /** Values to add to [current] from [previous]: lithology and filled form fields it lacks. */
    fun values(previous: GeoFeature, current: GeoFeature, fields: List<FieldTemplate>): Map<String, JsonElement> {
        val keys = buildList {
            if (current.kind == FeatureKind.GEO_POINT) add(Props.LITHOLOGY)
            fields.filter { it.appliesTo(current.kind) && it.key !in Props.MANAGED }.forEach { add(it.key) }
        }.distinct()
        return keys.mapNotNull { k ->
            val v = previous.properties[k]?.takeIf(::filled) ?: return@mapNotNull null
            if (current.properties[k]?.let(::filled) == true) null else k to v
        }.toMap()
    }

    /** Whether [current] has none of the values carrying could fill yet: a record just made, not one worked on. */
    fun fresh(current: GeoFeature, fields: List<FieldTemplate>): Boolean =
        (listOf(Props.LITHOLOGY) + fields.filter { it.appliesTo(current.kind) }.map { it.key }).none { k -> current.properties[k]?.let(::filled) == true }

    /** Labels of [values] for the button, form labels where there are any. */
    fun labels(values: Map<String, JsonElement>, fields: List<FieldTemplate>): List<String> =
        values.keys.map { k -> if (k == Props.LITHOLOGY) "岩性" else fields.firstOrNull { it.key == k }?.label ?: k }

    private fun filled(v: JsonElement) = v !is JsonNull && !(v is JsonPrimitive && v.isString && v.content.isBlank())
}
