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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.action.Action
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.ActionSpec
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Risk
import org.geocrew.core.action.Stability
import org.geocrew.core.action.Undo

/**
 * A layer to edit: where its features are kept, where its unsaved edits go,
 * and its CRS (such as EPSG:4490) when known, as moving features between
 * layers needs it.
 */
data class EditTarget(val store: LayerStore, val journal: Journal, val srs: String? = null)

/**
 * Layer editing as actions, the QGIS way (ADR 0005, 0010): start editing a
 * layer, change its features, then save or discard. Undo and redo work in the
 * registry scope [scopeOf] gives: one per layer, or with [sharedScope] one for
 * all of them, as a project wants. A shared history ends as a whole: saving,
 * discarding or stopping any one layer ends it for all, so a project saves and
 * discards all its layers together. [open] finds a layer by its id.
 */
class Editing(private val sharedScope: String? = null, private val open: (String) -> EditTarget) {
    private val buffers = HashMap<String, EditBuffer>()
    private val targets = HashMap<String, EditTarget>()

    /**
     * The edit buffer of [layer] while it is being edited. Not thread-safe:
     * outside this module, read layers through actions, which run one at a time.
     */
    internal fun buffer(layer: String): EditBuffer? = buffers[layer]

    /** The registry scope of [layer]'s undo history. */
    fun scopeOf(layer: String): String = sharedScope ?: scope(layer)

    /** The layers being edited. Read it inside an action, while no other runs. */
    val layersBeingEdited: Set<String> get() = buffers.keys.toSet()

    /** [layer]'s features as edited, or null when it is not being edited. Inside an action only. */
    fun features(layer: String): List<Feature>? = buffers[layer]?.features()

    /** Whether [layer] has edits to save. Inside an action only. */
    fun isModified(layer: String): Boolean = buffers[layer]?.isModified == true

    /**
     * Stops editing [layer] when it has nothing to save, as when its file must be
     * opened again; says whether it is no longer being edited. Inside an action
     * only; the action ends the layer's undo scope.
     */
    fun release(layer: String): Boolean {
        if (buffers[layer]?.isModified == true) return false
        buffers.remove(layer)
        targets.remove(layer)
        return true
    }

    // A buffer that was let go (its layer stopped, or its file opened again) takes no
    // more edits: an undo left over from it fails and drops out of the history.
    private fun live(layer: String, buffer: EditBuffer): EditBuffer =
        buffer.takeIf { buffers[layer] === it } ?: throw IllegalStateException("layer $layer is no longer being edited")

    /** The actions, for the registry. */
    val actions: List<Action> = listOf(
        LayerAction(START, "Start editing", "Starts editing a layer; unsaved edits from before a crash come back", Risk.EDIT, editing = false) { layer, _ ->
            val target = open(layer)
            val buffer = EditBuffer(target.store, target.journal)
            val recovered = buffer.recover()
            buffers[layer] = buffer
            targets[layer] = target
            // A layer's own history starts afresh; a shared one carries on with the other layers'.
            ActionResult.Done(buildJsonObject { put("recovered", recovered.name.lowercase()) }, endsUndo = if (sharedScope == null) listOf(scope(layer)) else emptyList())
        },
        FeatureAction(
            ADD, "Add feature", "Adds a feature to a layer being edited", Risk.EDIT, ADD_PARAMS,
            """{"layer": "野外点", "geometry": {"type": "Point", "coordinates": [114.305, 30.5928]}, "properties": {"点名": "D001", "描述": "泉水出露"}}""",
        ) { buffer, p ->
            buffer.add(p["geometry"].geometry(), (p["properties"] as? JsonObject) ?: JsonObject(emptyMap()))
        },
        FeatureAction(
            GEOMETRY, "Change geometry", "Gives a feature a new geometry, or none", Risk.EDIT, GEOMETRY_PARAMS,
            """{"layer": "野外点", "fid": 12, "geometry": {"type": "Point", "coordinates": [114.3051, 30.5929]}}""",
        ) { buffer, p ->
            buffer.changeGeometry(p.fid(), p["geometry"].geometry())
        },
        FeatureAction(
            VALUES, "Change values", "Sets fields of a feature", Risk.EDIT, VALUES_PARAMS,
            """{"layer": "野外点", "fid": 12, "values": {"点名": "S1", "描述": "泉水出露，流量约 2 L/s"}}""",
        ) { buffer, p ->
            buffer.changeValues(p.fid(), p.getValue("values").jsonObject)
        },
        FeatureAction(DELETE, "Delete feature", "Deletes a feature", Risk.DELETE, FID_PARAMS, """{"layer": "野外点", "fid": 12}""") { buffer, p ->
            buffer.delete(p.fid())
        },
        LayerAction(SAVE, "Save edits", "Writes a layer's edits to its file", Risk.EDIT) { layer, _ ->
            val ids = buffers.getValue(layer).save()
            ActionResult.Done(buildJsonObject { put("fids", fids(ids)) }, endsUndo = listOf(scopeOf(layer)))
        },
        LayerAction(DISCARD, "Discard edits", "Drops a layer's unsaved edits", Risk.DELETE) { layer, _ ->
            buffers.getValue(layer).discard()
            ActionResult.Done(endsUndo = listOf(scopeOf(layer)))
        },
        LayerAction(STOP, "Stop editing", "Stops editing a layer that has nothing unsaved", Risk.VIEW) { layer, _ ->
            if (buffers.getValue(layer).isModified) {
                ActionResult.Failed("unsaved", "save or discard the edits first")
            } else {
                buffers.remove(layer)
                targets.remove(layer)
                ActionResult.Done(endsUndo = listOf(scopeOf(layer)))
            }
        },
        Move(),
        AllAction(SAVE_ALL, "Save all edits", "Writes the edits of every layer being edited to its file", Risk.EDIT) {
            val saved = buffers.filterValues { it.isModified }.mapValues { (_, buffer) -> buffer.save() }
            ActionResult.Done(
                buildJsonObject { for ((layer, ids) in saved) put(layer, buildJsonObject { put("fids", fids(ids)) }) },
                endsUndo = buffers.keys.map(::scopeOf).distinct(),
            )
        },
        AllAction(DISCARD_ALL, "Discard all edits", "Drops the unsaved edits of every layer being edited", Risk.DELETE) {
            buffers.values.forEach { it.discard() }
            ActionResult.Done(endsUndo = buffers.keys.map(::scopeOf).distinct())
        },
    )

    // A feature moved to another layer, as QGIS cuts and pastes: the values of
    // fields the other layer has go along, the rest are dropped and named in
    // the result. Both layers change in one step of the shared undo history.
    private inner class Move : Action {
        override val spec = ActionSpec(
            MOVE, "Move feature", "Moves a feature to another layer being edited; the values of fields that layer lacks are dropped",
            MOVE_PARAMS, Risk.EDIT, Stability.PREVIEW, inputs = listOf("vector"), outputs = listOf("vector"),
            examples = listOf(schema("""{"layer": "野外点", "fid": 12, "to": "泉点"}""")),
        )

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult {
            val layer = params.getValue("layer").jsonPrimitive.content
            val to = params.getValue("to").jsonPrimitive.content
            val fid = params.fid()
            if (layer == to) return ActionResult.Failed("same-layer", "the feature is in $to already")
            val source = buffers[layer] ?: return ActionResult.Failed("not-editing", "layer $layer is not being edited")
            val target = buffers[to] ?: return ActionResult.Failed("not-editing", "layer $to is not being edited")
            if (scopeOf(layer) != scopeOf(to)) return ActionResult.Failed("unsupported", "moving needs one undo history for both layers")
            val from = targets.getValue(layer)
            val into = targets.getValue(to)
            if (from.srs != into.srs) return ActionResult.Failed("crs", "$layer and $to have different coordinate systems")
            if (fid >= 0 && !from.store.capabilities.delete) return ActionResult.Failed("read-only", "layer $layer does not allow deleting its features")
            val feature = requireNotNull(source.feature(fid)) { "there is no feature $fid" }
            val fields = into.store.fields.associateBy { it.name }
            val kept = LinkedHashMap<String, JsonElement>()
            val dropped = ArrayList<String>()
            for ((name, value) in feature.properties) {
                if (value is JsonNull) continue
                if (fields[name]?.type?.accepts(value) == true) kept[name] = value else dropped += name
            }
            // The copy goes in first: a crash between the two leaves the feature in both layers, never in neither.
            val added = target.add(feature.geometry, JsonObject(kept))
            val deleted = try {
                source.delete(fid)
            } catch (e: IllegalArgumentException) {
                target.revert(added)
                throw e
            }
            val undo = Undo(
                scopeOf(layer),
                redo = {
                    live(to, target).reapply(added)
                    live(layer, source).reapply(deleted)
                },
            ) {
                live(layer, source).revert(deleted)
                live(to, target).revert(added)
            }
            return ActionResult.Done(buildJsonObject { put("fid", added.fid); putJsonArray("dropped") { dropped.forEach { add(it) } } }, undo)
        }
    }

    // Every layer being edited at once, as a project's save button does.
    private inner class AllAction(id: String, name: String, description: String, risk: Risk, private val body: () -> ActionResult) : Action {
        override val spec = ActionSpec(id, name, description, NO_PARAMS, risk, Stability.PREVIEW, inputs = listOf("vector"), examples = listOf(schema("{}")))

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult = body()
    }

    private inner class LayerAction(
        id: String,
        name: String,
        description: String,
        risk: Risk,
        private val editing: Boolean = true,
        private val body: (String, JsonObject) -> ActionResult,
    ) : Action {
        override val spec = ActionSpec(
            id, name, description, LAYER_PARAMS, risk, Stability.PREVIEW,
            inputs = listOf("vector"), examples = listOf(schema("""{"layer": "野外点"}""")),
        )

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
        example: String,
        private val change: (EditBuffer, JsonObject) -> Edit,
    ) : Action {
        override val spec = ActionSpec(
            id, name, description, params, risk, Stability.PREVIEW,
            inputs = listOf("vector"), outputs = listOf("vector"), examples = listOf(schema(example)),
        )

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult {
            val layer = params.getValue("layer").jsonPrimitive.content
            val buffer = buffers[layer] ?: return ActionResult.Failed("not-editing", "layer $layer is not being edited")
            val edit = change(buffer, params)
            val undo = Undo(scopeOf(layer), redo = { live(layer, buffer).reapply(edit) }) { live(layer, buffer).revert(edit) }
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
        const val MOVE = "feature.move"
        const val SAVE_ALL = "edits.save"
        const val DISCARD_ALL = "edits.discard"

        /** The registry's undo scope for a layer being edited. */
        fun scope(layer: String) = "layer:$layer"

        private fun schema(s: String) = Json.parseToJsonElement(s).jsonObject

        private const val LAYER = """"layer": {"type": "string"}"""
        private const val FID = """"fid": {"type": "integer"}"""
        private const val GEOMETRY_ARG = """"geometry": {"description": "a GeoJSON geometry in the layer's CRS, or null"}"""
        private val NO_PARAMS = schema("""{"type": "object", "additionalProperties": false}""")
        private val LAYER_PARAMS = schema("""{"type": "object", "additionalProperties": false, "required": ["layer"], "properties": {$LAYER}}""")
        private val FID_PARAMS = schema("""{"type": "object", "additionalProperties": false, "required": ["layer", "fid"], "properties": {$LAYER, $FID}}""")
        private val ADD_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer"],
                "properties": {$LAYER, $GEOMETRY_ARG, "properties": {"type": "object"}}}""",
        )
        private val GEOMETRY_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "fid", "geometry"], "properties": {$LAYER, $FID, $GEOMETRY_ARG}}""",
        )
        private val MOVE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "fid", "to"],
                "properties": {$LAYER, $FID, "to": {"type": "string"}}}""",
        )
        private val VALUES_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "fid", "values"],
                "properties": {$LAYER, $FID, "values": {"type": "object"}}}""",
        )

        private fun JsonObject.fid(): Long = getValue("fid").jsonPrimitive.long

        // New features' temporary fids and the fids the store gave them.
        private fun fids(ids: Map<Long, Long>) = JsonObject(ids.entries.associate { (k, v) -> k.toString() to JsonPrimitive(v) })

        private fun JsonElement?.geometry(): JsonObject? = when (this) {
            null, JsonNull -> null
            is JsonObject -> this
            else -> throw IllegalArgumentException("geometry must be a GeoJSON object or null")
        }
    }
}
