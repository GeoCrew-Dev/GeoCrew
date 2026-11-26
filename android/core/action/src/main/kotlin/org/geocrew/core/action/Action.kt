package org.geocrew.core.action

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Risk groups (ADR 0010): what running an action can do to data or to the outside world. */
enum class Risk { VIEW, EDIT, BULK, DELETE, SEND, SETTINGS }

/**
 * How settled an action is (ADR 0010). Only stable actions are open to plugins
 * and MCP; their parameters may grow but never change, and a renamed action
 * keeps its old id as an alias.
 */
enum class Stability { STABLE, PREVIEW, INTERNAL }

/** Who asks for an action to run. */
enum class Caller { USER, AI, PLUGIN, MCP }

/**
 * What an action declares about itself (ADR 0010). [params] is a JSON Schema
 * for an object; [inputs] and [outputs] name the layer types it reads and
 * writes; [entries] are the places in the interface that offer it.
 */
data class ActionSpec(
    val id: String,
    val name: String,
    val description: String,
    val params: JsonObject,
    val risk: Risk,
    val stability: Stability,
    val aliases: List<String> = emptyList(),
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    val entries: List<String> = emptyList(),
    val examples: List<JsonObject> = emptyList(),
)

/**
 * Takes back what a run did ([undo]) and makes it again afterwards ([redo];
 * null when it cannot be). Each [scope] keeps its own undo history, the QGIS
 * way: one layer's editing has its own (ADR 0005), and null is the general one.
 */
class Undo(val scope: String? = null, val redo: (suspend () -> Unit)? = null, val undo: suspend () -> Unit)

/** What a run of an action ended with. */
sealed interface ActionResult {
    /**
     * It worked. [undo] takes it back, when the action can be undone.
     * [endsUndo] names a scope whose undo history ends with this run, as saving
     * or discarding a layer's edits ends that layer's.
     */
    data class Done(val output: JsonElement = JsonNull, val undo: Undo? = null, val endsUndo: String? = null) : ActionResult

    /** It could not be done; [code] is for programs, [message] for people. */
    data class Failed(val code: String, val message: String) : ActionResult
}

/** Something the interface, the AI, a plugin or MCP can ask for (ADR 0010). */
interface Action {
    val spec: ActionSpec

    /** Runs with parameters already checked against [ActionSpec.params]. */
    suspend fun run(caller: Caller, params: JsonObject): ActionResult
}
