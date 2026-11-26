package org.geocrew.core.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

private val pointParams = json(
    """{"type": "object", "additionalProperties": false, "required": ["name"],
       "properties": {"name": {"type": "string"}, "elev": {"type": "number"}, "count": {"type": "integer"},
                      "kind": {"enum": ["rock", "water"]}, "tags": {"type": "array", "items": {"type": "string"}}}}""",
)

/** A counter the tests act on: add moves it up, and its undo moves it back. */
private class Counter(
    private val stability: Stability = Stability.STABLE,
    private val id: String = "counter.add",
    private val aliases: List<String> = emptyList(),
) : Action {
    var value = 0
    val callers = mutableListOf<Caller>()
    override val spec = ActionSpec(id, "Add", "Adds one", pointParams, Risk.EDIT, stability, aliases)
    override suspend fun run(caller: Caller, params: JsonObject): ActionResult {
        callers += caller
        if (params["name"] == JsonPrimitive("boom")) error("it broke")
        if (params["name"] == JsonPrimitive("no")) return ActionResult.Failed("refused", "not today")
        value++
        return ActionResult.Done(JsonPrimitive(value)) { value-- }
    }
}

class ActionRegistryTest {
    private val ok = json("""{"name": "D001"}""")

    @Test
    fun runsByIdAndAlias() = runTest {
        val counter = Counter(aliases = listOf("counter.plus"))
        val registry = ActionRegistry().apply { register(counter) }
        assertEquals(Outcome.Done(JsonPrimitive(1)), registry.invoke(Caller.USER, "counter.add", ok))
        assertEquals(Outcome.Done(JsonPrimitive(2)), registry.invoke(Caller.USER, "counter.plus", ok))
        assertEquals(Outcome.Unknown, registry.invoke(Caller.USER, "counter.fly", ok))
        assertEquals(listOf("counter.add"), registry.specs().map { it.id })
    }

    @Test
    fun namesAreUnique() {
        val registry = ActionRegistry().apply { register(Counter(aliases = listOf("counter.plus"))) }
        assertFailsWith<IllegalArgumentException> { registry.register(Counter()) }
        assertFailsWith<IllegalArgumentException> { registry.register(Counter(id = "counter.plus")) }
        assertFailsWith<IllegalArgumentException> { registry.register(Counter(id = "other", aliases = listOf("counter.add"))) }
    }

    @Test
    fun checksParameters() = runTest {
        val counter = Counter()
        val registry = ActionRegistry().apply { register(counter) }
        for ((params, why) in listOf(
            """{}""" to "params.name is required",
            """{"name": 3}""" to "params.name must be string",
            """{"name": "a", "elev": "high"}""" to "params.elev must be number",
            """{"name": "a", "count": 1.5}""" to "params.count must be integer",
            """{"name": "a", "kind": "fire"}""" to "params.kind must be one of [\"rock\",\"water\"]",
            """{"name": "a", "tags": ["x", 1]}""" to "params.tags[1] must be string",
            """{"name": "a", "colour": "red"}""" to "params.colour is not a parameter",
        )) {
            assertEquals(Outcome.Invalid(why), registry.invoke(Caller.USER, "counter.add", json(params)))
        }
        assertEquals(0, counter.value, "an invalid call must not run")
        assertIs<Outcome.Done>(registry.invoke(Caller.USER, "counter.add", json("""{"name": "a", "elev": 12, "count": 3, "kind": "rock", "tags": ["x"]}""")))
        assertIs<Outcome.Done>(registry.invoke(Caller.USER, "counter.add", json("""{"name": "a", "count": 2.0}""")))
    }

    @Test
    fun stabilityLimitsWhoMayCall() = runTest {
        val preview = Counter(Stability.PREVIEW, "preview.add")
        val internal = Counter(Stability.INTERNAL, "internal.add")
        val registry = ActionRegistry().apply { register(preview); register(internal) }
        assertIs<Outcome.Done>(registry.invoke(Caller.AI, "preview.add", ok))
        assertIs<Outcome.Denied>(registry.invoke(Caller.PLUGIN, "preview.add", ok))
        assertIs<Outcome.Denied>(registry.invoke(Caller.MCP, "preview.add", ok))
        assertIs<Outcome.Done>(registry.invoke(Caller.USER, "internal.add", ok))
        assertIs<Outcome.Denied>(registry.invoke(Caller.AI, "internal.add", ok))
        assertEquals(listOf(Caller.AI), preview.callers)
    }

    @Test
    fun theGateDecides() = runTest {
        val counter = Counter()
        val registry = ActionRegistry(gate = { caller, spec, _ ->
            if (caller == Caller.AI && spec.risk == Risk.EDIT) "the leader keeps edits away from the AI" else null
        }).apply { register(counter) }
        assertEquals(Outcome.Denied("the leader keeps edits away from the AI"), registry.invoke(Caller.AI, "counter.add", ok))
        assertEquals(0, counter.value)
        assertIs<Outcome.Done>(registry.invoke(Caller.USER, "counter.add", ok))
    }

    @Test
    fun failuresAreReportedNotThrown() = runTest {
        val registry = ActionRegistry().apply { register(Counter()) }
        assertEquals(Outcome.Failed("refused", "not today"), registry.invoke(Caller.USER, "counter.add", json("""{"name": "no"}""")))
        assertEquals(Outcome.Failed("error", "it broke"), registry.invoke(Caller.USER, "counter.add", json("""{"name": "boom"}""")))
        assertFalse(registry.canUndo, "nothing to take back after failures")
    }

    @Test
    fun undoTakesBackTheLatestRun() = runTest {
        val counter = Counter()
        val registry = ActionRegistry().apply { register(counter) }
        repeat(2) { registry.invoke(Caller.USER, "counter.add", ok) }
        assertTrue(registry.canUndo)
        assertTrue(registry.undo())
        assertEquals(1, counter.value)
        assertTrue(registry.undo())
        assertEquals(0, counter.value)
        assertFalse(registry.undo())
    }

    @Test
    fun undoReachesBackALimitedWay() = runTest {
        val counter = Counter()
        val registry = ActionRegistry().apply { register(counter) }
        repeat(ActionRegistry.MAX_UNDO + 5) { registry.invoke(Caller.USER, "counter.add", ok) }
        var undone = 0
        while (registry.undo()) undone++
        assertEquals(ActionRegistry.MAX_UNDO, undone)
        assertEquals(5, counter.value)
    }

    @Test
    fun cancellingTheCallerIsNotAFailure() = runTest {
        val slow = object : Action {
            override val spec = ActionSpec("slow", "Slow", "", json("""{"type": "object"}"""), Risk.VIEW, Stability.STABLE)
            override suspend fun run(caller: Caller, params: JsonObject): ActionResult {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val log = mutableListOf<LogEntry>()
        val registry = ActionRegistry(log = { log += it }).apply { register(slow) }
        val job = launch { registry.invoke(Caller.AI, "slow") }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(log.isEmpty(), "a cancelled call is not logged as a failure")
    }

    @Test
    fun everyCallIsLogged() = runTest {
        val log = mutableListOf<LogEntry>()
        val registry = ActionRegistry(log = { log += it }).apply { register(Counter(Stability.PREVIEW)) }
        registry.invoke(Caller.USER, "counter.add", ok)
        registry.invoke(Caller.PLUGIN, "counter.add", ok)
        registry.invoke(Caller.USER, "counter.add", json("{}"))
        registry.invoke(Caller.USER, "nope", ok)
        assertEquals(listOf("counter.add", "counter.add", "counter.add", "nope"), log.map { it.action })
        assertIs<Outcome.Done>(log[0].outcome)
        assertIs<Outcome.Denied>(log[1].outcome)
        assertIs<Outcome.Invalid>(log[2].outcome)
        assertEquals(Outcome.Unknown, log[3].outcome)
        assertEquals(Caller.PLUGIN, log[1].caller)
    }

    @Test
    fun paramsMustDescribeAnObject() {
        val bad = object : Action {
            override val spec = ActionSpec("bad", "Bad", "", json("""{"type": "string"}"""), Risk.VIEW, Stability.STABLE)
            override suspend fun run(caller: Caller, params: JsonObject) = ActionResult.Done()
        }
        assertFailsWith<IllegalArgumentException> { ActionRegistry().register(bad) }
        assertNull(Schema.check(pointParams, ok))
    }
}
