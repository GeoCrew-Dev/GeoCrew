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
 * hydrogeologist never measures dip. So the point layer has only the fields
 * every field worker needs; everything else, such as rock type or attitude, the
 * user adds. Routes, sections and boreholes have rows of their own (stations,
 * beds, intervals) and come with their tools.
 */
object Templates {
    /** Where field points go, by hand or through the quick field-point flow (ADR 0004). */
    val FIELD_POINT = LayerTemplate(
        id = "field-point",
        names = mapOf("zh" to "野外点", "en" to "Field points"),
        geometryType = "POINT",
        crs = "EPSG:4490", // CGCS2000; GNSS positions are taken as CGCS2000 (ADR 0015)
        fields = listOf(
            TemplateField(NUMBER, FieldType.STRING, mapOf("zh" to "点号", "en" to "Number"), "the point's number, such as D001"),
            TemplateField(NOTE, FieldType.STRING, mapOf("zh" to "描述", "en" to "Note"), "what was seen at the point"),
            TemplateField(ELEVATION, FieldType.REAL, mapOf("zh" to "海拔", "en" to "Elevation"), "height above sea level, metres"),
            TemplateField(ACCURACY, FieldType.REAL, mapOf("zh" to "定位精度", "en" to "Accuracy"), "horizontal accuracy of the position, metres"),
            TemplateField(TIME, FieldType.DATETIME, mapOf("zh" to "记录时间", "en" to "Time"), "when the point was recorded"),
            TemplateField(RECORDER, FieldType.STRING, mapOf("zh" to "记录人", "en" to "Recorder"), "who recorded it"),
        ),
    )

    const val NUMBER = "number"
    const val NOTE = "note"
    const val ELEVATION = "elevation"
    const val ACCURACY = "accuracy"
    const val TIME = "time"
    const val RECORDER = "recorder"

    val all: List<LayerTemplate> = listOf(FIELD_POINT)

    fun byId(id: String?): LayerTemplate? = all.firstOrNull { it.id == id }
}
