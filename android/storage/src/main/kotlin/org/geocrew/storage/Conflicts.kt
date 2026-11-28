package org.geocrew.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.quote
import org.geocrew.nativelib.VectorDataset

/**
 * The conflict records a project keeps from its pulls (protocol v1 6.5), as
 * the user sees them: the feature and field, and the value kept against the
 * one that lost, each with who set it and when.
 */
internal object Conflicts {
    /** How many conflicts wait to be settled. */
    fun open(project: Project): Int = project.data { ds ->
        if (!exists(ds)) 0 else rows(ds, "SELECT COUNT(*) AS n FROM ${ProjectFormat.CONFLICTS_TABLE} WHERE resolved = 0").first()["n"]?.toIntOrNull() ?: 0
    }

    /**
     * The conflicts waiting to be settled, oldest first. [me] is this phone's
     * member, [members] the nicknames by member id. A feature this phone does
     * not have (a layer not here, a row deleted) comes without its fid.
     */
    fun list(project: Project, me: String, members: Map<String, String>): JsonArray {
        val records = project.data { ds ->
            if (!exists(ds)) {
                emptyList()
            } else {
                rows(ds, "SELECT id, layer_id, row, field, kind, kept, lost FROM ${ProjectFormat.CONFLICTS_TABLE} WHERE resolved = 0 ORDER BY rowid")
            }
        }
        return buildJsonArray {
            for (r in records) {
                val layer = project.layers.firstOrNull { it.id == r["layer_id"] }
                val store = layer?.let { project.target(it.name).store }
                val fid = layer?.let { l -> project.data { Outbox.fidOf(it, l.id, r["row"].orEmpty()) } }
                val feature = fid?.let { store?.feature(it) }
                val field = r["field"]
                add(
                    buildJsonObject {
                        put("id", r["id"])
                        put("kind", r["kind"])
                        layer?.let { put("layer", it.name) }
                        if (layer != null && store != null && feature != null) {
                            put("fid", feature.fid)
                            val name = Kml.nameField(layer, store.fields.map { it.name })
                            name?.let { (feature.properties[it] as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                        }
                        when (field) {
                            null -> {}
                            Outbox.GEOMETRY -> put("geometry", true)
                            else -> put("field", layer?.let { l -> project.fieldIds(l.name).entries.firstOrNull { it.value == field }?.key } ?: field)
                        }
                        side(r["kept"], me, members)?.let { put("kept", it) }
                        side(r["lost"], me, members)?.let { put("lost", it) }
                    },
                )
            }
        }
    }

    // One side of a conflict as kept: the value, whose it is (a nickname when known) and when.
    private fun side(text: String?, me: String, members: Map<String, String>): JsonObject? {
        val o = text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        val by = (o["by"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            o["value"]?.let { put("value", it) }
            members[by]?.let { put("by", it) }
            put("mine", by != null && by == me)
            (o["at"] as? JsonPrimitive)?.contentOrNull?.let { put("at", it) }
        }
    }

    private fun exists(ds: VectorDataset): Boolean =
        rows(ds, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ${quote(ProjectFormat.CONFLICTS_TABLE)}").isNotEmpty()
}
