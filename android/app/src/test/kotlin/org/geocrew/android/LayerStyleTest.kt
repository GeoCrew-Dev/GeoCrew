package org.geocrew.android

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LayerStyleTest {
    private fun features(vararg rocks: String?): JsonArray = Json.parseToJsonElement(
        rocks.joinToString(",", "[", "]") { r -> """{"type": "Feature", "properties": {"rock": ${r?.let { "\"$it\"" } ?: "null"}}}""" },
    ).jsonArray

    private fun properties(rock: String): JsonObject = Json.parseToJsonElement("""{"rock": "$rock"}""").jsonObject

    @Test
    fun eachValueGetsAClassInNaturalOrder() {
        val classes = classesFor(features("砂岩", "灰岩", "砂岩", null, " ", "层10", "层2"), "rock", emptyList())
        val values = classes.map { it.value }
        assertEquals(setOf("层2", "层10", "灰岩", "砂岩"), values.toSet(), "blank and missing values are left to the layer's colour")
        assertEquals(4, values.size, "each value once")
        assert(values.indexOf("层2") < values.indexOf("层10")) { "numbers in natural order" }
        assertEquals(4, classes.map { it.color }.toSet().size, "each its own colour")
    }

    @Test
    fun keptClassesStayAsTheyWereAndNewValuesFollow() {
        val kept = listOf(StyleClass("灰岩", Color.Black))
        val classes = classesFor(features("砂岩", "灰岩"), "rock", kept)
        assertEquals(listOf("灰岩", "砂岩"), classes.map { it.value })
        assertEquals(Color.Black, classes.first().color)
    }

    @Test
    fun featuresTakeTheirValuesColourOrTheLayers() {
        val style = LayerStyle(Color(0xFF757575), field = "rock", classes = listOf(StyleClass("砂岩", Color(0xFFE53935))))
        assertEquals("#e53935", style.hexFor(properties("砂岩")))
        assertEquals("#757575", style.hexFor(properties("灰岩")))
        assertEquals("#757575", style.hexFor(null))
        assertEquals("#757575", LayerStyle(Color(0xFF757575)).hexFor(properties("砂岩")))
    }

    @Test
    fun aCategorizedStyleIsSavedWithItsClasses() {
        val json = LayerStyle(Color(0xFF757575), field = "rock", classes = listOf(StyleClass("砂岩", Color(0xFFE53935)))).json()
        assertEquals("rock", json.getValue("field").jsonPrimitive.content)
        assertEquals("#e53935", json.getValue("classes").jsonArray.single().jsonObject.getValue("color").jsonPrimitive.content)
        assertEquals(null, LayerStyle(Color.Black).json()["field"])
    }
}
