package org.geocrew.core.edit

import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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

/**
 * The numbers a field takes, as a GeoPackage range constraint gives them
 * (ADR 0004): from [min] to [max], an end left open when null, each end itself
 * in the range or not. Null and values that are not numbers are left to the
 * field's type.
 */
data class ValueRange(val min: Double? = null, val max: Double? = null, val minIncluded: Boolean = true, val maxIncluded: Boolean = true) {
    init {
        require(min != null || max != null) { "a range has a minimum, a maximum or both" }
        require(min?.isFinite() != false && max?.isFinite() != false) { "the ends of a range are finite numbers" }
        require(min == null || max == null || min <= max) { "the minimum of a range is not above its maximum" }
    }

    operator fun contains(x: Double): Boolean =
        (min == null || if (minIncluded) x >= min else x > min) && (max == null || if (maxIncluded) x <= max else x < max)

    /** Whether [value] fits: a number in the range, or anything else. */
    fun accepts(value: JsonElement): Boolean {
        val x = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return true
        return x in this
    }

    /** The range in words, for messages: "at least 0 and at most 90". */
    fun describe(): String = listOfNotNull(
        min?.let { (if (minIncluded) "at least " else "more than ") + plain(it) },
        max?.let { (if (maxIncluded) "at most " else "less than ") + plain(it) },
    ).joinToString(" and ")

    /** As actions give it: "min" and "max" when set, "minIncluded" or "maxIncluded" false for an end not in it. */
    fun toJson(): JsonObject = buildJsonObject {
        min?.let { put("min", it) }
        max?.let { put("max", it) }
        if (!minIncluded) put("minIncluded", false)
        if (!maxIncluded) put("maxIncluded", false)
    }

    companion object {
        /** The range [json] gives as [toJson] writes it; null for none, or for one that is no range. */
        fun of(json: JsonElement?): ValueRange? {
            val o = json as? JsonObject ?: return null
            fun number(key: String) = (o[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            fun flag(key: String) = (o[key] as? JsonPrimitive)?.booleanOrNull != false
            return runCatching { ValueRange(number("min"), number("max"), flag("minIncluded"), flag("maxIncluded")) }.getOrNull()
        }

        // A whole number without its ".0", as people write it.
        private fun plain(x: Double): String = if (x % 1.0 == 0.0 && abs(x) < 1e15) x.toLong().toString() else x.toString()
    }
}

/** A field of a layer; with a [range], a number field takes only the numbers in it. */
data class Field(val name: String, val type: FieldType, val range: ValueRange? = null) {
    /** Whether the field takes [value]: one of its type, and within its range. */
    fun accepts(value: JsonElement): Boolean = type.accepts(value) && range?.accepts(value) != false
}

/**
 * A file kept with a feature (ADR 0004, protocol v1 section 9.1): a photo, a
 * sketch, a recording. [file] and [thumb] are the SHA-256 hashes (lower-case
 * hex) of files in the project's attachment folder, [type] the file's MIME
 * type, [name] what it was called; [id] is the attachment's own UUID, as sync
 * needs; [meta] holds the rest, such as where a photo looked.
 */
@Serializable
data class Attachment(
    val id: String,
    val file: String,
    val type: String,
    val size: Long,
    val name: String,
    val thumb: String? = null,
    val meta: JsonObject? = null,
) {
    init {
        require(HASH.matches(file) && (thumb == null || HASH.matches(thumb))) { "an attachment's files are named by their SHA-256 in lower-case hex" }
        require(type.isNotBlank() && size >= 0) { "an attachment has a type and a size" }
    }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
    }
}

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

    /** Feature [fid]'s attachments, in their order; none where the store keeps none. */
    fun attachments(fid: Long): List<Attachment> = emptyList()

    /** Gives feature [fid] these [attachments] and no others, in this order. */
    fun setAttachments(fid: Long, attachments: List<Attachment>) {
        require(attachments.isEmpty()) { "this layer keeps no attachments" }
    }

    /**
     * What a save wrote, before it commits (in its transaction where the store
     * has them), for sync to send (ADR 0008); nothing to do by default.
     */
    fun saved(changes: List<Saved>) {}

    /** Adds a feature and returns the fid the store gave it. */
    fun add(geometry: JsonObject?, properties: JsonObject): Long

    /** Sets the properties named in [properties] and, when [setGeometry], the geometry (null included). */
    fun update(fid: Long, properties: JsonObject, setGeometry: Boolean, geometry: JsonObject?)

    fun delete(fid: Long)

    fun begin()

    fun commit()

    fun rollback()
}
