package org.geocrew.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing.Companion.ADD
import org.geocrew.core.edit.Editing.Companion.DELETE
import org.geocrew.core.edit.Editing.Companion.DISCARD
import org.geocrew.core.edit.Editing.Companion.GEOMETRY
import org.geocrew.core.edit.Editing.Companion.SAVE
import org.geocrew.core.edit.Editing.Companion.START
import org.geocrew.core.edit.Editing.Companion.STOP
import org.geocrew.core.edit.Editing.Companion.VALUES

class EditActionsTest {
    private val stores = mapOf("points" to FakeStore(), "lines" to FakeStore())
    private val journals = stores.mapValues { MemoryJournal() }
    private val editing = Editing { layer -> EditTarget(stores.getValue(layer), journals.getValue(layer)) }
    private val registry = ActionRegistry().apply { editing.actions.forEach(::register) }

    private suspend fun run(action: String, params: String = """{"layer": "points"}""") = registry.invoke(Caller.USER, action, json(params))

    private suspend fun undo(layer: String = "points") = registry.invoke(Caller.USER, ActionRegistry.UNDO, json("""{"scope": "${Editing.scope(layer)}"}"""))

    private suspend fun redo(layer: String = "points") = registry.invoke(Caller.USER, ActionRegistry.REDO, json("""{"scope": "${Editing.scope(layer)}"}"""))

    private fun output(o: Outcome): JsonObject = (o as Outcome.Done).output.jsonObject

    @Test
    fun editThenSaveTheQgisWay() = runTest {
        val a = stores.getValue("points").put("a")
        assertEquals(Outcome.Failed("not-editing", "layer points is not being edited"), run(ADD, """{"layer": "points"}"""))
        assertEquals("nothing", (output(run(START))["recovered"] as JsonPrimitive).content)
        assertIs<Outcome.Failed>(run(START), "already being edited")

        val added = output(run(ADD, """{"layer": "points", "geometry": {"type": "Point", "coordinates": [1, 2]}, "properties": {"rock": "new"}}"""))
        assertEquals(JsonPrimitive(-1), added["fid"])
        assertIs<Outcome.Done>(run(VALUES, """{"layer": "points", "fid": $a, "values": {"rock": "a2"}}"""))
        assertIs<Outcome.Done>(run(GEOMETRY, """{"layer": "points", "fid": $a, "geometry": null}"""))
        assertEquals(1, stores.getValue("points").rows.size, "nothing written before saving")
        assertEquals(Outcome.Failed("unsaved", "save or discard the edits first"), run(STOP))

        val saved = output(run(SAVE))
        assertEquals(json("""{"-1": 2}"""), saved["fids"])
        assertNull(stores.getValue("points").rows.getValue(a).geometry)
        assertFalse(registry.canUndo(Editing.scope("points")), "saving ends the layer's undo history")
        assertIs<Outcome.Done>(run(STOP))
        assertNull(editing.buffer("points"))
    }

    @Test
    fun undoAndRedoStayWithTheirLayer() = runTest {
        run(START)
        run(START, """{"layer": "lines"}""")
        run(ADD, """{"layer": "points", "properties": {"rock": "p1"}}""")
        run(ADD, """{"layer": "lines", "properties": {"rock": "l1"}}""")
        run(ADD, """{"layer": "points", "properties": {"rock": "p2"}}""")
        assertIs<Outcome.Done>(undo("points"))
        assertEquals(listOf("p1"), editing.buffer("points")!!.features().map { (it.properties["rock"] as JsonPrimitive).content })
        assertEquals(1, editing.buffer("lines")!!.features().size, "the other layer is untouched")
        assertIs<Outcome.Done>(redo("points"))
        assertEquals(2, editing.buffer("points")!!.features().size)
        assertIs<Outcome.Done>(run(DISCARD))
        assertFalse(editing.buffer("points")!!.isModified)
        assertIs<Outcome.Failed>(undo("points"), "discarding ends the history too")
        assertTrue(registry.canUndo(Editing.scope("lines")))
    }

    @Test
    fun startingBringsBackEditsFromBeforeACrash() = runTest {
        run(START)
        run(ADD, """{"layer": "points", "properties": {"rock": "kept"}}""")
        // The app is killed; a new registry and a new Editing find the journal.
        val again = Editing { layer -> EditTarget(stores.getValue(layer), journals.getValue(layer)) }
        val registry2 = ActionRegistry().apply { again.actions.forEach(::register) }
        val started = registry2.invoke(Caller.USER, START, json("""{"layer": "points"}"""))
        assertEquals("restored", (output(started)["recovered"] as JsonPrimitive).content)
        assertEquals(1, again.buffer("points")!!.features().size)
    }

    @Test
    fun examplesFitTheirParameters() {
        for (action in editing.actions) {
            assertTrue(action.spec.examples.isNotEmpty(), action.spec.id)
            for (example in action.spec.examples) assertNull(org.geocrew.core.action.Schema.check(action.spec.params, example), action.spec.id)
        }
    }

    @Test
    fun badEditsFailWithoutHarm() = runTest {
        val a = stores.getValue("points").put("a")
        run(START)
        assertIs<Outcome.Failed>(run(VALUES, """{"layer": "points", "fid": $a, "values": {"dip": "steep"}}"""))
        assertIs<Outcome.Failed>(run(GEOMETRY, """{"layer": "points", "fid": 99, "geometry": null}"""))
        assertIs<Outcome.Failed>(run(GEOMETRY, """{"layer": "points", "fid": $a, "geometry": "here"}"""))
        assertIs<Outcome.Invalid>(run(DELETE, """{"layer": "points"}"""))
        assertFalse(editing.buffer("points")!!.isModified)
        assertFalse(registry.canUndo(Editing.scope("points")))
        assertIs<Outcome.Done>(run(DELETE, """{"layer": "points", "fid": $a}"""))
        assertTrue(registry.canUndo(Editing.scope("points")))
    }
}
