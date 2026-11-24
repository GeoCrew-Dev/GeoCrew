package org.geocrew.core.model

/**
 * What a mapped line stands for, drawn after the usual geological map
 * conventions (protocol v0 §6, property `line_style`): contacts thin and
 * faults bold red, observed solid and inferred dashed. Lines without it
 * are drawn as before. Other clients may add values; unknown ones draw
 * as a plain line.
 */
enum class LineStyle(val id: String, val label: String, val dashed: Boolean, val fault: Boolean) {
    CONTACT("contact", "实测地质界线", dashed = false, fault = false),
    INFERRED_CONTACT("inferred_contact", "推测地质界线", dashed = true, fault = false),
    FAULT("fault", "实测断层", dashed = false, fault = true),
    INFERRED_FAULT("inferred_fault", "推测断层", dashed = true, fault = true),
    ;

    companion object {
        fun of(id: String?): LineStyle? = entries.firstOrNull { it.id == id }
    }
}

val GeoFeature.lineStyle: LineStyle? get() = LineStyle.of(properties.string(Props.LINE_STYLE))
