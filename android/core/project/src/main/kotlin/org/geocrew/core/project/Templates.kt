package org.geocrew.core.project

import org.geocrew.core.edit.Field
import org.geocrew.core.edit.FieldType

/** A field of a layer template, with what it means for the forms and the AI manual. */
data class TemplateField(val name: String, val type: FieldType, val description: String) {
    val field: Field get() = Field(name, type)
}

/**
 * A built-in kind of layer (ADR 0004): [name] becomes the layer's table,
 * [geometryType] is an OGC name, [crs] the CRS its coordinates are kept in.
 */
data class LayerTemplate(
    val id: String,
    val name: String,
    val geometryType: String,
    val crs: String,
    val fields: List<TemplateField>,
)

/**
 * The built-in layer templates. Only the geological point so far; routes,
 * sections and boreholes have rows of their own (stations, beds, intervals)
 * and come with their tools. A draft, to be settled after a day in the field.
 */
object Templates {
    /** Where field points go, by hand or through the quick field-point flow (ADR 0004). */
    val GEO_POINT = LayerTemplate(
        id = "geo-point",
        name = "地质点",
        geometryType = "POINT",
        crs = "EPSG:4490", // CGCS2000; GNSS positions are taken as CGCS2000 (ADR 0015)
        fields = listOf(
            TemplateField("点号", FieldType.STRING, "the point's number, such as D001"),
            TemplateField("岩性", FieldType.STRING, "the rock at the point"),
            TemplateField("倾向", FieldType.REAL, "dip direction of the main attitude, degrees 0-360"),
            TemplateField("倾角", FieldType.REAL, "dip of the main attitude, degrees 0-90"),
            TemplateField("描述", FieldType.STRING, "what was seen at the point"),
            TemplateField("海拔", FieldType.REAL, "height above sea level, metres"),
            TemplateField("定位精度", FieldType.REAL, "horizontal accuracy of the position, metres"),
            TemplateField("记录时间", FieldType.DATETIME, "when the point was recorded"),
            TemplateField("记录人", FieldType.STRING, "who recorded it"),
        ),
    )

    val all: List<LayerTemplate> = listOf(GEO_POINT)

    fun byId(id: String): LayerTemplate? = all.firstOrNull { it.id == id }
}
