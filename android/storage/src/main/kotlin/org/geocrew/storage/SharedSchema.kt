package org.geocrew.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.geocrew.core.edit.FieldType
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.quote
import org.geocrew.core.sync.FieldDef
import org.geocrew.nativelib.VectorDataset

/**
 * The structure of a project's shared layers as its workspace has it
 * (protocol v1 7.2-7.4, [ProjectFormat.LAYER_SCHEMA_TABLE]): what the phone
 * has that the workspace has not yet is its own, to send at the next sync,
 * and the values in it wait till the workspace takes it.
 */
internal object SharedSchema {
    /** A layer's structure as the workspace has it: its schema version and its fields. */
    data class Layer(val version: Long, val fields: List<FieldDef>)

    fun of(ds: VectorDataset, layerId: String): Layer? {
        if (!exists(ds)) return null
        val row = rows(ds, "SELECT version, fields FROM ${ProjectFormat.LAYER_SCHEMA_TABLE} WHERE layer_id = ${quote(layerId)}").firstOrNull() ?: return null
        val fields = runCatching { Json.parseToJsonElement(row["fields"].orEmpty()).jsonArray }.getOrNull().orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            FieldDef(text("id") ?: return@mapNotNull null, text("name").orEmpty(), text("type").orEmpty())
        }
        return Layer(row["version"]?.toLongOrNull() ?: 0, fields)
    }

    /** Layer [layerId]'s structure as the workspace said it last: [version] and [fields]; an older one than kept is not. */
    fun put(ds: VectorDataset, layerId: String, version: Long, fields: List<FieldDef>) {
        ds.sql(ProjectFormat.LAYER_SCHEMA_SCHEMA)
        if ((of(ds, layerId)?.version ?: -1) > version) return
        val json = buildJsonArray {
            for (f in fields) add(buildJsonObject { put("id", f.id); put("name", f.name); put("type", f.type) })
        }
        ds.sql("INSERT OR REPLACE INTO ${ProjectFormat.LAYER_SCHEMA_TABLE} (layer_id, version, fields) VALUES (${quote(layerId)}, $version, ${quote(json.toString())})")
    }

    /** What this phone has that the workspace has not taken yet: [layers] by id, and [fields] by their layer's id. */
    data class Unsent(val layers: Set<String>, val fields: Map<String, Set<String>>) {
        /** How many layers and fields, a layer's own fields aside. */
        val size: Int get() = layers.size + fields.values.sumOf { it.size }
    }

    /**
     * What the workspace has not taken of a linked project's structure: layers
     * made here since the project was shared, and fields added here. A layer
     * shared before the phone kept structures has none.
     */
    fun unsent(ds: VectorDataset, project: Project): Unsent {
        if (!Outbox.linked(ds)) return Unsent(emptySet(), emptyMap())
        val shared = Outbox.systemRows(ds)
        val fields = project.layers.filter { it.id in shared }.associate { layer ->
            val known = of(ds, layer.id)?.fields?.mapTo(HashSet()) { it.id }
            layer.id to if (known == null) emptySet() else Outbox.fieldIds(ds, layer.name).values.filterTo(HashSet()) { it !in known }
        }
        return Unsent(project.layers.filter { it.id !in shared }.mapTo(HashSet()) { it.id }, fields.filterValues { it.isNotEmpty() })
    }

    /** Whether two field names are one (protocol v1 7.2): with no spaces at either end, in any case. */
    fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    /** A field's name as the workspace has it, made one this phone can give a field: no spaces at either end, not too long, not fid or geom. */
    fun localName(name: String): String {
        val cut = name.trim().take(ProjectFormat.MAX_NAME).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.trimEnd()
        return if (Workspace.RESERVED.any { it.equals(cut, ignoreCase = true) }) "$cut 2" else cut
    }

    /** A field's type as the workspace has it (protocol v1 7.2). */
    fun remoteType(type: FieldType): String = when (type) {
        FieldType.INTEGER, FieldType.INTEGER64 -> "integer"
        FieldType.REAL -> "real"
        FieldType.STRING -> "string"
        FieldType.DATE -> "date"
        FieldType.DATETIME -> "datetime"
        FieldType.BOOLEAN -> "boolean"
        FieldType.OTHER -> "json"
    }

    /** A field of the workspace's type [type] as this phone keeps it: json as its text. */
    fun localType(type: String): FieldType = when (type) {
        "integer" -> FieldType.INTEGER64
        "real" -> FieldType.REAL
        "boolean" -> FieldType.BOOLEAN
        "date" -> FieldType.DATE
        "datetime" -> FieldType.DATETIME
        else -> FieldType.STRING
    }

    private fun exists(ds: VectorDataset): Boolean =
        rows(ds, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ${quote(ProjectFormat.LAYER_SCHEMA_TABLE)}").isNotEmpty()
}
