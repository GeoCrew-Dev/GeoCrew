package org.geocrew.android

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class GraduatedStyleTest {
    private fun features(vararg depths: Any?): JsonArray = Json.parseToJsonElement(
        depths.joinToString(",", "[", "]") { d -> """{"type": "Feature", "properties": {"depth": ${if (d is String) "\"$d\"" else d.toString()}}}""" },
    ).jsonArray

    private val red = Color(0xFFE53935)
    private val blue = Color(0xFF1E88E5)

    @Test
    fun equalRangesSpanTheValues() {
        val ranges = rangesFor(features(0, 10, 3, null, "x", 7), "depth", 2, quantile = false, red, blue)
        assertEquals(listOf(0.0 to 5.0, 5.0 to 10.0), ranges.map { it.from to it.to })
        assertEquals(red, ranges.first().color, "the ramp starts at its first colour")
        assertEquals(blue, ranges.last().color, "and ends at its last")
    }

    @Test
    fun quantilesHoldAboutAsManyEach() {
        val ranges = rangesFor(features(1, 2, 3, 4, 100, 200), "depth", 2, quantile = true, red, blue)
        assertEquals(listOf(1.0 to 4.0, 4.0 to 200.0), ranges.map { it.from to it.to })
    }

    @Test
    fun oneValueMakesOneRangeAndNoneMakeNone() {
        assertEquals(listOf(5.0 to 5.0), rangesFor(features(5, 5), "depth", 4, quantile = false, red, blue).map { it.from to it.to })
        assertEquals(emptyList(), rangesFor(features("a", null), "depth", 4, quantile = false, red, blue))
    }

    @Test
    fun aValueFallsInItsRangeAndTheLastTakesItsEnd() {
        val ranges = listOf(StyleRange(0.0, 5.0, red), StyleRange(5.0, 10.0, blue))
        assertEquals(0, rangeOf(ranges, 0.0))
        assertEquals(1, rangeOf(ranges, 5.0))
        assertEquals(1, rangeOf(ranges, 10.0))
        assertNull(rangeOf(ranges, 10.5))
        assertNull(rangeOf(ranges, -1.0))
    }

    private fun properties(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun featuresTakeTheirRangesColour() {
        val style = LayerStyle(Color(0xFF757575), field = "depth", ranges = listOf(StyleRange(0.0, 5.0, red), StyleRange(5.0, 10.0, blue)))
        assertEquals(StyleMode.GRADUATED, style.mode)
        assertEquals("#e53935", style.mapColorFor(properties("""{"depth": 2.5}""")))
        assertEquals("#1e88e5", style.mapColorFor(properties("""{"depth": "7"}""")), "numbers kept as text too")
        assertEquals("#757575", style.mapColorFor(properties("""{"depth": 11}""")), "out of every range")
        assertEquals("#757575", style.mapColorFor(properties("""{"depth": "deep"}""")))
    }

    @Test
    fun aGraduatedStyleIsSavedWithItsRanges() {
        val json = LayerStyle(Color.Black, field = "depth", ranges = listOf(StyleRange(0.0, 5.5, red))).json()
        val range = json.getValue("ranges").jsonArray.single().jsonObject
        assertEquals("5.5", range.getValue("to").toString())
        assertNull(json["classes"])
        assertEquals("0 – 5.5", rangeText(StyleRange(0.0, 5.5, red)))
    }
}
