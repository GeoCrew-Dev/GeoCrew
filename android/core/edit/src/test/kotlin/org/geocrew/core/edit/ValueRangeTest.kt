package org.geocrew.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome

class ValueRangeTest {
    private val dip = ValueRange(0.0, 90.0)

    @Test
    fun endsAreInTheRangeOrNot() {
        assertTrue(0.0 in dip && 90.0 in dip && 45.5 in dip)
        assertFalse(-0.1 in dip || 90.1 in dip)
        val azimuth = ValueRange(0.0, 360.0, maxIncluded = false)
        assertTrue(0.0 in azimuth)
        assertFalse(360.0 in azimuth)
        val depth = ValueRange(min = 0.0)
        assertTrue(1e9 in depth, "an open end")
        assertFalse(-1.0 in depth)
        assertFalse(0.0 in ValueRange(min = 0.0, minIncluded = false))
    }

    @Test
    fun onlyNumbersAreChecked() {
        assertTrue(dip.accepts(JsonNull), "no value")
        assertTrue(dip.accepts(JsonPrimitive("95")), "text is the type's to refuse")
        assertTrue(dip.accepts(JsonPrimitive(true)))
        assertTrue(dip.accepts(JsonPrimitive(35)))
        assertFalse(dip.accepts(JsonPrimitive(95)))
        val dipField = Field("倾角", FieldType.REAL, dip)
        assertTrue(dipField.accepts(JsonPrimitive(90)))
        assertFalse(dipField.accepts(JsonPrimitive(91)))
        assertFalse(dipField.accepts(JsonPrimitive("steep")), "nor a number at all")
    }

    @Test
    fun aRangeHasAnEndAndTheRightOrder() {
        assertFailsWith<IllegalArgumentException> { ValueRange() }
        assertFailsWith<IllegalArgumentException> { ValueRange(90.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { ValueRange(max = Double.POSITIVE_INFINITY) }
        assertEquals(ValueRange(5.0, 5.0), ValueRange(5.0, 5.0), "one number")
    }

    @Test
    fun itGoesToAndFromJson() {
        assertEquals(json("""{"min": 0.0, "max": 90.0}"""), dip.toJson())
        val azimuth = ValueRange(0.0, 360.0, maxIncluded = false)
        assertEquals(json("""{"min": 0.0, "max": 360.0, "maxIncluded": false}"""), azimuth.toJson())
        for (range in listOf(dip, azimuth, ValueRange(min = -5.5), ValueRange(max = 14.0))) assertEquals(range, ValueRange.of(range.toJson()))
        assertNull(ValueRange.of(null))
        assertNull(ValueRange.of(JsonObject(emptyMap())), "no ends: no range")
        assertNull(ValueRange.of(json("""{"min": 90, "max": 0}""")))
        assertEquals("at least 0 and at most 90", dip.describe())
        assertEquals("more than 0.5", ValueRange(min = 0.5, minIncluded = false).describe())
    }

    // A range set while the layer is being edited counts from the next change on.
    @Test
    fun anEditKeepsToTheRangeTheStoreHasNow() {
        val store = Ranged(FakeStore())
        val buffer = EditBuffer(store, MemoryJournal())
        val a = buffer.add(point(0, 0), json("""{"dip": 95}""")).fid
        store.fields = FIELDS.map { if (it.name == "dip") it.copy(range = dip) else it }
        val refused = assertFailsWith<IllegalArgumentException> { buffer.changeValues(a, json("""{"dip": 91}""")) }
        assertEquals("field dip takes numbers at least 0 and at most 90, not 91", refused.message)
        assertFailsWith<IllegalArgumentException> { buffer.add(point(0, 0), json("""{"dip": -1}""")) }
        buffer.changeValues(a, json("""{"dip": 90, "n": 3}"""))
        buffer.changeValues(a, json("""{"dip": null}"""))
        assertEquals(JsonNull, buffer.feature(a)!!.properties["dip"])
    }

    // A value out of the other layer's range is dropped from a move, as one of the wrong type is.
    @Test
    fun aMoveDropsAValueOutOfRange() = runTest {
        val points = FakeStore()
        val planes = FakeStore(fields = listOf(Field("rock", FieldType.STRING), Field("dip", FieldType.REAL, dip)))
        val stores = mapOf("points" to points, "planes" to planes)
        val project = Editing(sharedScope = "project") { layer -> EditTarget(stores.getValue(layer), MemoryJournal(), "EPSG:4490") }
        val registry = ActionRegistry().apply { project.actions.forEach(::register) }
        suspend fun run(action: String, params: String) = registry.invoke(Caller.USER, action, json(params))
        run(Editing.START, """{"layer": "points"}""")
        run(Editing.START, """{"layer": "planes"}""")
        val stored = points.put("granite")
        run(Editing.VALUES, """{"layer": "points", "fid": $stored, "values": {"dip": 120}}""")
        val moved = run(Editing.MOVE, """{"layer": "points", "fid": $stored, "to": "planes"}""")
        val dropped = (moved as Outcome.Done).output.jsonObject.getValue("dropped").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("dip"), dropped)
        assertEquals(JsonPrimitive("granite"), project.buffer("planes")!!.features().single().properties["rock"])
    }

    // A store whose fields can change, as a field's range can.
    private class Ranged(inner: FakeStore) : LayerStore by inner {
        override var fields: List<Field> = inner.fields
    }
}
