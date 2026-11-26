package org.geocrew.core.action

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

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
 * nowhere else.
 */
class ActionRegistry(
    private val gate: Gate = Gate { _, _, _ -> null },
    private val log: (LogEntry) -> Unit = {},
) {
    private val actions = LinkedHashMap<String, Action>()
    private val names = HashMap<String, Action>() // ids and aliases
    private val undos = ArrayDeque<Undo>()
    private val lock = Mutex()

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

    /** Whether the latest undoable run can be taken back. */
    val canUndo: Boolean get() = undos.isNotEmpty()

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
        } catch (e: Exception) {
            ActionResult.Failed("error", e.message ?: e.javaClass.simpleName)
        }
        return when (result) {
            is ActionResult.Done -> {
                result.undo?.let { undos.addLast(it) }
                Outcome.Done(result.output)
            }
            is ActionResult.Failed -> Outcome.Failed(result.code, result.message)
        }
    }

    /** Takes back the latest undoable run; false when there is none. */
    suspend fun undo(): Boolean = lock.withLock {
        val last = undos.removeLastOrNull() ?: return@withLock false
        last.undo()
        true
    }

    private fun opensTo(stability: Stability, caller: Caller): Boolean = when (stability) {
        Stability.STABLE -> true
        Stability.PREVIEW -> caller == Caller.USER || caller == Caller.AI
        Stability.INTERNAL -> caller == Caller.USER
    }
}
