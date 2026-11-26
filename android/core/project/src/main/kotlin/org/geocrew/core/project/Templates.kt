package org.geocrew.core.project

import org.geocrew.core.edit.Field
import org.geocrew.core.edit.FieldType

/**
 * A field of a layer template: [key] says what it is for (the app finds a
 * point's number by it), [names] what it is called in each language the app
 * speaks, [description] what it means for the forms and the AI manual.
 */
data class TemplateField(val key: String, val type: FieldType, val names: Map<String, String>, val description: String) {
    /** Its name in [language] ("zh", "en"); English for any other. */
    fun name(language: String): String = names[language] ?: names.getValue(ENGLISH)

    fun field(language: String): Field = Field(name(language), type)
}

/**
 * A built-in kind of layer (ADR 0004): its table is named after [names] in the
 * project's language, [geometryType] is an OGC name, [crs] the CRS its
 * coordinates are kept in.
 */
data class LayerTemplate(
    val id: String,
    val names: Map<String, String>,
    val geometryType: String,
    val crs: String,
    val fields: List<TemplateField>,
) {
    fun name(language: String): String = names[language] ?: names.getValue(ENGLISH)

    /** Which of [columns] is the template's field [key], under its name in any language; null when none is. */
    fun column(key: String, columns: Collection<String>): String? {
        val names = fields.firstOrNull { it.key == key }?.names?.values ?: return null
        return columns.firstOrNull { it in names }
    }
}

private const val ENGLISH = "en"

/**
 * The built-in layer templates. Points are not only for geologists: a
 * hydrogeologist never measures dip. So a point has only a name and a
 * description besides its coordinates; everything else, such as rock type or
 * the depth to water, the user adds. Routes, sections and boreholes have rows of their own (stations,
 * beds, intervals) and come with their tools.
 */
object Templates {
    /** Where field points go, by hand or through the quick field-point flow (ADR 0004). */
    val FIELD_POINT = LayerTemplate(
        id = "field-point",
        names = mapOf("zh" to "野外点", "en" to "Field points"),
        geometryType = "POINT",
        crs = "EPSG:4490", // CGCS2000; GNSS positions are taken as CGCS2000 (ADR 0015)
        // The point's coordinates are its geometry; every other field the user adds.
        fields = listOf(
            TemplateField(NAME, FieldType.STRING, mapOf("zh" to "点名", "en" to "Name"), "the point's name, D001 and on by default"),
            TemplateField(DESCRIPTION, FieldType.STRING, mapOf("zh" to "描述", "en" to "Description"), "what was seen at the point"),
        ),
    )

    /** Lines drawn in the field (boundaries, faults, routes), with a name and a description like points. */
    val FIELD_LINE = LayerTemplate(
        id = "field-line",
        names = mapOf("zh" to "野外线", "en" to "Field lines"),
        geometryType = "LINESTRING",
        crs = "EPSG:4490",
        fields = listOf(
            TemplateField(NAME, FieldType.STRING, mapOf("zh" to "名称", "en" to "Name"), "the line's name"),
            TemplateField(DESCRIPTION, FieldType.STRING, mapOf("zh" to "描述", "en" to "Description"), "what the line is"),
        ),
    )

    /** Areas drawn in the field (outcrops, zones, plots), with a name and a description like points. */
    val FIELD_POLYGON = LayerTemplate(
        id = "field-polygon",
        names = mapOf("zh" to "野外面", "en" to "Field areas"),
        geometryType = "POLYGON",
        crs = "EPSG:4490",
        fields = listOf(
            TemplateField(NAME, FieldType.STRING, mapOf("zh" to "名称", "en" to "Name"), "the area's name"),
            TemplateField(DESCRIPTION, FieldType.STRING, mapOf("zh" to "描述", "en" to "Description"), "what the area is"),
        ),
    )

    const val NAME = "name"
    const val DESCRIPTION = "description"

    val all: List<LayerTemplate> = listOf(FIELD_POINT, FIELD_LINE, FIELD_POLYGON)

    fun byId(id: String?): LayerTemplate? = all.firstOrNull { it.id == id }
}
