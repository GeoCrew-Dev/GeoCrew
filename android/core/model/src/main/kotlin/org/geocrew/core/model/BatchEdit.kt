package org.geocrew.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Setting one attribute on many records at once, e.g. the formation of
 * twenty points mapped in the same unit. The attributes offered are the
 * lithology, the project's form fields and free attributes already in use;
 * app-managed keys (attitude, samples, photos...) have their own editors.
 */
object BatchEdit {
    /** An attribute that can be set on many records; [form] is set for form fields. */
    data class Target(val key: String, val label: String, val form: FieldTemplate? = null) {
        /** Choices to offer: a choice field's options, else none. */
        val options: List<String> get() = form?.takeIf { it.type == FieldType.CHOICE }?.options.orEmpty()

        /** Whether the attribute belongs on [f]; form fields can be limited to some kinds. */
        fun appliesTo(f: GeoFeature): Boolean = form?.appliesTo(f.kind) ?: true

        /**
         * The value to store for what was typed, or null to remove the
         * attribute. Form fields follow their type; free attributes store
         * numbers as numbers. Throws [IllegalArgumentException] when the
         * input does not fit the field.
         */
        fun encode(raw: String): JsonElement? {
            form?.let { return it.encode(raw) }
            val v = raw.trim()
            if (v.isEmpty()) return null
            val n = v.toDoubleOrNull()?.takeIf { it.isFinite() }
            return if (n != null && key != Props.LITHOLOGY) JsonPrimitive(n) else JsonPrimitive(v)
        }
    }

    /** Lithology first, then the form in its order, then other attributes in use. */
    fun targets(fields: List<FieldTemplate>, used: List<AttributeValues.Key>): List<Target> {
        val out = linkedMapOf(Props.LITHOLOGY to Target(Props.LITHOLOGY, "岩性"))
        for (f in fields) if (f.key !in Props.MANAGED) out.putIfAbsent(f.key, Target(f.key, f.label, f))
        for (k in used) if (k.key !in Props.MANAGED) out.putIfAbsent(k.key, Target(k.key, k.label))
        return out.values.toList()
    }

    /** What applying [value] to [target] would change: records it changes, and those it skips because the field is not for their kind. */
    data class Plan(val change: List<GeoFeature>, val skipped: Int)

    fun plan(records: List<GeoFeature>, target: Target, value: JsonElement?): Plan {
        val live = records.filter { !it.deleted }
        val (fit, unfit) = live.partition { target.appliesTo(it) }
        return Plan(fit.filter { it.properties[target.key] != value }, unfit.size)
    }
}
