package org.geocrew.core.model.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The user's choice per category: run, ask first, or never. */
data class AiPermissions(val modes: Map<ActionRisk, ActionMode> = emptyMap()) {
    fun mode(risk: ActionRisk): ActionMode = modes[risk] ?: risk.defaultMode

    fun with(risk: ActionRisk, mode: ActionMode) = copy(modes = modes + (risk to mode))

    fun toJson(): JsonObject = buildJsonObject { modes.forEach { (r, m) -> put(r.id, m.id) } }

    companion object {
        fun fromJson(o: JsonObject?): AiPermissions = AiPermissions(
            o.orEmpty().mapNotNull { (k, v) ->
                val r = ActionRisk.of(k) ?: return@mapNotNull null
                val m = ActionMode.of((v as? JsonPrimitive)?.contentOrNull) ?: return@mapNotNull null
                r to m
            }.toMap(),
        )
    }
}

/**
 * What a workspace leader forbids the assistant to do for anyone in the
 * workspace, kept in the shared settings as `ai_limits` (protocol §2.2):
 * `{"deny": ["send", "delete"]}`. Reading is never forbidden.
 */
data class TeamAiLimits(val denied: Set<ActionRisk> = emptySet()) {
    fun toJson(): JsonObject = buildJsonObject {
        put("deny", JsonArray(denied.filter { it != ActionRisk.READ }.map { JsonPrimitive(it.id) }))
    }

    companion object {
        const val KEY = "ai_limits"

        fun fromJson(o: JsonObject?): TeamAiLimits = TeamAiLimits(
            ((o?.get("deny") as? JsonArray).orEmpty()).mapNotNull { e ->
                runCatching { ActionRisk.of(e.jsonPrimitive.contentOrNull) }.getOrNull()
            }.filter { it != ActionRisk.READ }.toSet(),
        )
    }
}

sealed interface Decision {
    data object Run : Decision
    data class Confirm(val reason: String) : Decision
    data class Deny(val reason: String) : Decision
}

/** A call held for the user, with why. */
data class Pending(val call: ActionCall, val spec: ActionSpec, val risk: ActionRisk, val reason: String)

/** A call that will not run, with why; sent back to the assistant. */
data class Refused(val call: ActionCall, val reason: String)

/**
 * One request's calls sorted for execution: [run] goes ahead now, in order;
 * [confirm] waits on one card the user approves with a single tap;
 * [refused] is reported back.
 */
data class ActionPlan(val run: List<Pair<ActionCall, ActionSpec>>, val confirm: List<Pending>, val refused: List<Refused>) {
    val needsUser: Boolean get() = confirm.isNotEmpty()
}

/**
 * The permission check. It is a pure function of the user's settings, the
 * workspace limits and the request, so it costs nothing at run time and
 * can be tested exhaustively.
 *
 * Rules, in order:
 * - the user's own taps and local quick commands run;
 * - an action the workspace leader forbids, or the user set to 禁止, is refused;
 * - reading runs;
 * - anything proposed after the assistant read text from records, messages
 *   or files ([tainted]) waits for the user: that text is data, and must not
 *   be able to steer what the assistant changes;
 * - 直接执行 runs, 先问我 waits unless the user allowed that category for
 *   the rest of this conversation ([sessionAllowed]).
 */
class PermissionPolicy(
    private val permissions: AiPermissions = AiPermissions(),
    private val team: TeamAiLimits = TeamAiLimits(),
    private val sessionAllowed: Set<ActionRisk> = emptySet(),
    val maxPerRequest: Int = MAX_PER_REQUEST,
) {
    fun decide(spec: ActionSpec, call: ActionCall, source: ActionSource, tainted: Boolean = false): Decision {
        if (source == ActionSource.USER) return Decision.Run
        val risk = runCatching { spec.riskFor(call.args) }.getOrDefault(spec.risk)
        if (risk != ActionRisk.READ && risk in team.denied) return Decision.Deny("工作组组长设置了不允许 AI「${risk.label}」")
        val mode = permissions.mode(risk)
        if (mode == ActionMode.DENY) return Decision.Deny("你在设置里禁止了 AI「${risk.label}」")
        if (risk == ActionRisk.READ) return Decision.Run
        if (tainted) return Decision.Confirm("这一步是读到记录或文件里的文字之后提出的")
        if (mode == ActionMode.ALLOW || risk in sessionAllowed) return Decision.Run
        return Decision.Confirm(risk.label)
    }

    /** Sorts one request's [calls]; unknown names and calls past [maxPerRequest] are refused. */
    fun plan(calls: List<ActionCall>, specs: (String) -> ActionSpec?, source: ActionSource, tainted: Boolean = false): ActionPlan {
        val run = mutableListOf<Pair<ActionCall, ActionSpec>>()
        val confirm = mutableListOf<Pending>()
        val refused = mutableListOf<Refused>()
        calls.forEachIndexed { i, c ->
            val spec = specs(c.name)
            when {
                spec == null -> refused += Refused(c, "没有这个操作：${c.name}")
                source == ActionSource.AI && i >= maxPerRequest -> refused += Refused(c, "一次最多执行 $maxPerRequest 个操作")
                else -> when (val d = decide(spec, c, source, tainted)) {
                    Decision.Run -> run += c to spec
                    is Decision.Confirm -> confirm += Pending(c, spec, runCatching { spec.riskFor(c.args) }.getOrDefault(spec.risk), d.reason)
                    is Decision.Deny -> refused += Refused(c, d.reason)
                }
            }
        }
        return ActionPlan(run, confirm, refused)
    }

    companion object {
        const val MAX_PER_REQUEST = 12
    }
}

/**
 * Caps how fast the assistant can act, so a runaway loop stops long before
 * it can do much: at most [max] AI actions in any [windowMillis].
 */
class ActionRateLimiter(private val max: Int = 40, private val windowMillis: Long = 60_000) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(count: Int, now: Long): Boolean {
        while (times.isNotEmpty() && now - times.first() >= windowMillis) times.removeFirst()
        if (times.size + count > max) return false
        repeat(count) { times.addLast(now) }
        return true
    }
}
