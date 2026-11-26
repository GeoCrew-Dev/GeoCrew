package org.geocrew.core.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The part of JSON Schema action parameters use: type (object, array, string,
 * integer, number, boolean, null), properties, required,
 * additionalProperties: false, items and enum. Anything else in a schema is
 * documentation and not checked.
 */
object Schema {
    fun isObjectSchema(schema: JsonObject): Boolean = (schema["type"] as? JsonPrimitive)?.content == "object"

    /** Why value does not fit schema, or null when it does. */
    fun check(schema: JsonObject, value: JsonElement, path: String = "params"): String? {
        val type = (schema["type"] as? JsonPrimitive)?.content
        if (type != null && !fits(type, value)) return "$path must be $type"
        (schema["enum"] as? JsonArray)?.let { if (value !in it) return "$path must be one of $it" }
        if (value is JsonObject) {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (schema["required"] as? JsonArray)?.forEach {
                val name = it.jsonPrimitive.content
                if (name !in value) return "$path.$name is required"
            }
            for ((name, v) in value) {
                val sub = properties[name] as? JsonObject
                if (sub == null) {
                    if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false) return "$path.$name is not a parameter"
                    continue
                }
                check(sub, v, "$path.$name")?.let { return it }
            }
        }
        if (value is JsonArray) {
            val items = schema["items"] as? JsonObject ?: return null
            value.forEachIndexed { i, v -> check(items, v, "$path[$i]")?.let { return it } }
        }
        return null
    }

    private fun fits(type: String, value: JsonElement): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        else -> true
    }
}
