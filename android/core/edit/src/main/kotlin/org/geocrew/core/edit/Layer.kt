package org.geocrew.core.edit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A feature in its layer's own CRS: [geometry] is a GeoJSON geometry, or null;
 * [properties] has a value for every field, null included. This is the JSON
 * GeoCrew-Native's VectorDataset reads and writes.
 */
@Serializable
data class Feature(val fid: Long, val geometry: JsonObject? = null, val properties: JsonObject = JsonObject(emptyMap()))

/** Field types, as GeoCrew-Native's VectorDataset names them in lower case. */
enum class FieldType {
    INTEGER, INTEGER64, REAL, STRING, DATE, DATETIME, BOOLEAN, OTHER;

    /**
     * Whether a field of this type takes [value]: null always; numbers for the
     * number types, whole and in range for the integers; true or false for
     * booleans; ISO 8601 strings for dates. Strings also take numbers. Fields of
     * other types (binary and the like) cannot be edited yet.
     */
    fun accepts(value: JsonElement): Boolean {
        if (value is JsonNull) return true
        if (value !is JsonPrimitive || this == OTHER) return false
        if (value.isString) {
            return when (this) {
                STRING -> true
                DATE -> DATE_PATTERN.matches(value.content)
                DATETIME -> DATETIME_PATTERN.matches(value.content)
                else -> false
            }
        }
        if (value.booleanOrNull != null) return this == BOOLEAN
        val d = value.doubleOrNull?.takeIf { it.isFinite() } ?: return false
        return when (this) {
            REAL, STRING -> true
            INTEGER -> value.longOrNull?.let { it in Int.MIN_VALUE..Int.MAX_VALUE }
                ?: (d % 1.0 == 0.0 && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE)
            INTEGER64 -> value.longOrNull != null || (d % 1.0 == 0.0 && d >= -TWO_TO_63 && d < TWO_TO_63)
            else -> false
        }
    }

    companion object {
        private const val TWO_TO_63 = 9.223372036854775808E18
        private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
        private val DATETIME_PATTERN = Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}(:?\d{2})?)?""")

        /** The type VectorDataset calls [name]; OTHER for names it does not know. */
        fun of(name: String): FieldType = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OTHER
    }
}

data class Field(val name: String, val type: FieldType)

/** What a layer's driver can do (ADR 0005: edits follow the driver's capabilities). */
data class Capabilities(val add: Boolean, val update: Boolean, val delete: Boolean, val transactions: Boolean)

/**
 * Where a layer's features are kept: what an [EditBuffer] reads through and
 * saves to. The app's stores wrap GeoCrew-Native's VectorDataset. Calls block,
 * and failures throw.
 */
interface LayerStore {
    val fields: List<Field>
    val capabilities: Capabilities

    fun feature(fid: Long): Feature?

    /**
     * The highest fid in the store, or -1 when it is empty. New features always
     * get higher fids than any before them (GeoPackage, Shapefile), which tells
     * a recovery whether an interrupted save went through.
     */
    fun lastFid(): Long

    /** Every feature of the layer. */
    fun features(): List<Feature>

    /** Adds a feature and returns the fid the store gave it. */
    fun add(geometry: JsonObject?, properties: JsonObject): Long

    /** Sets the properties named in [properties] and, when [setGeometry], the geometry (null included). */
    fun update(fid: Long, properties: JsonObject, setGeometry: Boolean, geometry: JsonObject?)

    fun delete(fid: Long)

    fun begin()

    fun commit()

    fun rollback()
}
