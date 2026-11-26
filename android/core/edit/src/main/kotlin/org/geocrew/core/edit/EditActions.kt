package org.geocrew.core.edit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.geocrew.core.action.Action
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.ActionSpec
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Risk
import org.geocrew.core.action.Stability
import org.geocrew.core.action.Undo

/** A layer to edit: where its features are kept and where its unsaved edits go. */
data class EditTarget(val store: LayerStore, val journal: Journal)

/**
 * Layer editing as actions, the QGIS way (ADR 0005, 0010): start editing a
 * layer, change its features, then save or discard; undo and redo work per
 * layer, in scope [scope] of the action registry, until the edits are saved or
 * discarded. [open] finds a layer by its id.
 */
class Editing(private val open: (String) -> EditTarget) {
    private val buffers = HashMap<String, EditBuffer>()

    /** The edit buffer of [layer] while it is being edited. */
    fun buffer(layer: String): EditBuffer? = buffers[layer]

    /** The actions, for the registry. */
    val actions: List<Action> = listOf(
        LayerAction(START, "Start editing", "Starts editing a layer; unsaved edits from before a crash come back", Risk.EDIT, editing = false) { layer, _ ->
            val target = open(layer)
            val buffer = EditBuffer(target.store, target.journal)
            val recovered = buffer.recover()
            buffers[layer] = buffer
            ActionResult.Done(buildJsonObject { put("recovered", recovered.name.lowercase()) }, endsUndo = scope(layer))
        },
        FeatureAction(ADD, "Add feature", "Adds a feature to a layer being edited", Risk.EDIT, ADD_PARAMS) { buffer, p ->
            buffer.add(p["geometry"].geometry(), (p["properties"] as? JsonObject) ?: JsonObject(emptyMap()))
        },
        FeatureAction(GEOMETRY, "Change geometry", "Gives a feature a new geometry, or none", Risk.EDIT, GEOMETRY_PARAMS) { buffer, p ->
            buffer.changeGeometry(p.fid(), p["geometry"].geometry())
        },
        FeatureAction(VALUES, "Change values", "Sets fields of a feature", Risk.EDIT, VALUES_PARAMS) { buffer, p ->
            buffer.changeValues(p.fid(), p.getValue("values").jsonObject)
        },
        FeatureAction(DELETE, "Delete feature", "Deletes a feature", Risk.DELETE, FID_PARAMS) { buffer, p -> buffer.delete(p.fid()) },
        LayerAction(SAVE, "Save edits", "Writes a layer's edits to its file", Risk.EDIT) { layer, _ ->
            val ids = buffers.getValue(layer).save()
            ActionResult.Done(buildJsonObject { put("fids", JsonObject(ids.entries.associate { (k, v) -> k.toString() to JsonPrimitive(v) })) }, endsUndo = scope(layer))
        },
        LayerAction(DISCARD, "Discard edits", "Drops a layer's unsaved edits", Risk.DELETE) { layer, _ ->
            buffers.getValue(layer).discard()
            ActionResult.Done(endsUndo = scope(layer))
        },
        LayerAction(STOP, "Stop editing", "Stops editing a layer that has nothing unsaved", Risk.VIEW) { layer, _ ->
            if (buffers.getValue(layer).isModified) {
                ActionResult.Failed("unsaved", "save or discard the edits first")
            } else {
                buffers.remove(layer)
                ActionResult.Done(endsUndo = scope(layer))
            }
        },
    )

    private inner class LayerAction(
        id: String,
        name: String,
        description: String,
        risk: Risk,
        private val editing: Boolean = true,
        private val body: (String, JsonObject) -> ActionResult,
    ) : Action {
        override val spec = ActionSpec(id, name, description, LAYER_PARAMS, risk, Stability.PREVIEW)

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult {
            val layer = params.getValue("layer").jsonPrimitive.content
            if (editing && layer !in buffers) return ActionResult.Failed("not-editing", "layer $layer is not being edited")
            if (!editing && layer in buffers) return ActionResult.Failed("editing", "layer $layer is already being edited")
            return body(layer, params)
        }
    }

    // A change to a feature: it goes into the layer's undo history.
    private inner class FeatureAction(
        id: String,
        name: String,
        description: String,
        risk: Risk,
        params: JsonObject,
        private val change: (EditBuffer, JsonObject) -> Edit,
    ) : Action {
        override val spec = ActionSpec(id, name, description, params, risk, Stability.PREVIEW)

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult {
            val layer = params.getValue("layer").jsonPrimitive.content
            val buffer = buffers[layer] ?: return ActionResult.Failed("not-editing", "layer $layer is not being edited")
            val edit = change(buffer, params)
            val undo = Undo(scope(layer), redo = { buffer.reapply(edit) }) { buffer.revert(edit) }
            return ActionResult.Done(buildJsonObject { put("fid", edit.fid) }, undo)
        }
    }

    companion object {
        const val START = "layer.edit.start"
        const val SAVE = "layer.edit.save"
        const val DISCARD = "layer.edit.discard"
        const val STOP = "layer.edit.stop"
        const val ADD = "feature.add"
        const val GEOMETRY = "feature.geometry"
        const val VALUES = "feature.values"
        const val DELETE = "feature.delete"

        /** The registry's undo scope for a layer being edited. */
        fun scope(layer: String) = "layer:$layer"

        private fun schema(s: String) = Json.parseToJsonElement(s).jsonObject

        private const val LAYER = """"layer": {"type": "string"}"""
        private const val FID = """"fid": {"type": "integer"}"""
        private const val GEOMETRY_ARG = """"geometry": {"description": "a GeoJSON geometry in the layer's CRS, or null"}"""
        private val LAYER_PARAMS = schema("""{"type": "object", "additionalProperties": false, "required": ["layer"], "properties": {$LAYER}}""")
        private val FID_PARAMS = schema("""{"type": "object", "additionalProperties": false, "required": ["layer", "fid"], "properties": {$LAYER, $FID}}""")
        private val ADD_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer"],
                "properties": {$LAYER, $GEOMETRY_ARG, "properties": {"type": "object"}}}""",
        )
        private val GEOMETRY_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "fid", "geometry"], "properties": {$LAYER, $FID, $GEOMETRY_ARG}}""",
        )
        private val VALUES_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "fid", "values"],
                "properties": {$LAYER, $FID, "values": {"type": "object"}}}""",
        )

        private fun JsonObject.fid(): Long = getValue("fid").jsonPrimitive.long

        private fun JsonElement?.geometry(): JsonObject? = when (this) {
            null, JsonNull -> null
            is JsonObject -> this
            else -> throw IllegalArgumentException("geometry must be a GeoJSON object or null")
        }
    }
}
