package org.geocrew.core.action

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Decides whether a caller may run an action with these parameters: the
 * member's permissions, the leader's locks and the user's risk settings
 * (ADR 0009, 0010). Returns why not, or null to allow.
 */
fun interface Gate {
    fun deny(caller: Caller, spec: ActionSpec, params: JsonObject): String?
}

/** What an invocation came to, as the operation log keeps it. */
sealed interface Outcome {
    data class Done(val output: JsonElement = JsonNull) : Outcome
    data class Failed(val code: String, val message: String) : Outcome
    data class Denied(val reason: String) : Outcome
    data class Invalid(val reason: String) : Outcome
    data object Unknown : Outcome
}

/** One line of the operation log. */
data class LogEntry(val caller: Caller, val action: String, val params: JsonObject, val outcome: Outcome)

/**
 * The one registry every action goes through (ADR 0010): the interface's
 * buttons, built-in features, GDAL algorithms, plugins, the AI and MCP alike.
 * Parameter checks, permissions, the operation log and undo happen here and
 * nowhere else. Undo and redo are actions too, [UNDO] and [REDO], each for
 * one scope of undo history (see [Undo]).
 */
class ActionRegistry(
    private val gate: Gate = Gate { _, _, _ -> null },
    private val log: (LogEntry) -> Unit = {},
) {
    private class History {
        val undos = ArrayDeque<Undo>()
        val redos = ArrayDeque<Undo>()
    }

    private val actions = LinkedHashMap<String, Action>()
    private val names = HashMap<String, Action>() // ids and aliases
    private val histories = HashMap<String?, History>()
    private val lock = Mutex()

    // How many undos and redos each scope has, for reading without the lock:
    // invoke holds it for a whole run, and it is not reentrant.
    @Volatile private var sizes: Map<String?, Pair<Int, Int>> = emptyMap()

    init {
        register(HistoryAction(UNDO, "Undo", "Takes back the latest run in a scope of undo history") { undoLocked(it) })
        register(HistoryAction(REDO, "Redo", "Makes the latest undone run in a scope again") { redoLocked(it) })
    }

    /** Adds an action. Its id and aliases must be new. */
    fun register(action: Action) {
        val spec = action.spec
        require(Schema.isObjectSchema(spec.params)) { "${spec.id}: params must be a JSON Schema for an object" }
        for (name in listOf(spec.id) + spec.aliases) {
            require(name.isNotBlank() && name !in names) { "${spec.id}: the name $name is already taken" }
        }
        actions[spec.id] = action
        for (name in listOf(spec.id) + spec.aliases) names[name] = action
    }

    /** Finds an action by id or alias. */
    fun find(name: String): Action? = names[name]

    /** Every action, in the order they were registered. */
    fun specs(): List<ActionSpec> = actions.values.map { it.spec }

    /** Whether [UNDO] has something to take back in [scope]. */
    fun canUndo(scope: String? = null): Boolean = (sizes[scope]?.first ?: 0) > 0

    /** Whether [REDO] has something to make again in [scope]. */
    fun canRedo(scope: String? = null): Boolean = (sizes[scope]?.second ?: 0) > 0

    /** Runs an action for a caller, after checking who may and with what. */
    suspend fun invoke(caller: Caller, name: String, params: JsonObject = JsonObject(emptyMap())): Outcome = lock.withLock {
        val action = find(name)
        val outcome = when {
            action == null -> Outcome.Unknown
            !opensTo(action.spec.stability, caller) ->
                Outcome.Denied("${action.spec.id} is ${action.spec.stability.name.lowercase()}, not open to ${caller.name.lowercase()}")
            else -> run(caller, action, params)
        }
        log(LogEntry(caller, action?.spec?.id ?: name, params, outcome))
        outcome
    }

    private suspend fun run(caller: Caller, action: Action, params: JsonObject): Outcome {
        Schema.check(action.spec.params, params)?.let { return Outcome.Invalid(it) }
        gate.deny(caller, action.spec, params)?.let { return Outcome.Denied(it) }
        val result = try {
            action.run(caller, params)
        } catch (e: CancellationException) {
            throw e // a cancelled caller stops; it is not a failure of the action
        } catch (e: Exception) {
            ActionResult.Failed("error", e.message ?: e.javaClass.simpleName)
        }
        return when (result) {
            is ActionResult.Done -> {
                result.endsUndo.forEach { histories.remove(it) }
                result.undo?.let { undo ->
                    val history = histories.getOrPut(undo.scope) { History() }
                    history.undos.push(undo)
                    history.redos.clear() // a new run starts a new line of history
                }
                if (result.endsUndo.isNotEmpty() || result.undo != null) publish()
                Outcome.Done(result.output)
            }
            is ActionResult.Failed -> {
                result.endsUndo.forEach { histories.remove(it) }
                if (result.endsUndo.isNotEmpty()) publish()
                Outcome.Failed(result.code, result.message)
            }
        }
    }

    // Called inside invoke, which holds the lock. A step that throws leaves the history.
    private suspend fun undoLocked(scope: String?): Boolean {
        val history = histories[scope] ?: return false
        val last = history.undos.removeLastOrNull() ?: return false
        try {
            last.undo()
            if (last.redo != null) history.redos.push(last)
        } finally {
            publish()
        }
        return true
    }

    private suspend fun redoLocked(scope: String?): Boolean {
        val history = histories[scope] ?: return false
        val last = history.redos.removeLastOrNull() ?: return false
        try {
            checkNotNull(last.redo)()
            history.undos.push(last)
        } finally {
            publish()
        }
        return true
    }

    private fun publish() {
        sizes = histories.mapValues { it.value.undos.size to it.value.redos.size }
    }

    private fun ArrayDeque<Undo>.push(undo: Undo) {
        addLast(undo)
        if (size > MAX_UNDO) removeFirst()
    }

    companion object {
        /** How many runs back undo reaches in each scope; older ones are let go. */
        const val MAX_UNDO = 100

        /** Takes back the latest run in the scope given as `scope` (none for the general one). */
        const val UNDO = "app.undo"

        /** Makes the latest undone run in the scope given as `scope` again. */
        const val REDO = "app.redo"

        private val HISTORY_PARAMS = Json.parseToJsonElement(
            """{"type": "object", "additionalProperties": false, "properties": {"scope": {"type": "string"}}}""",
        ).jsonObject
    }

    private class HistoryAction(
        id: String,
        name: String,
        description: String,
        private val step: suspend (String?) -> Boolean,
    ) : Action {
        override val spec = ActionSpec(id, name, description, HISTORY_PARAMS, Risk.EDIT, Stability.PREVIEW)

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult {
            val scope = (params["scope"] as? JsonPrimitive)?.contentOrNull
            return if (step(scope)) ActionResult.Done() else ActionResult.Failed("nothing", "there is nothing to ${spec.name.lowercase()}")
        }
    }

    private fun opensTo(stability: Stability, caller: Caller): Boolean = when (stability) {
        Stability.STABLE -> true
        Stability.PREVIEW -> caller == Caller.USER || caller == Caller.AI
        Stability.INTERNAL -> caller == Caller.USER
    }
}
