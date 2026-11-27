package org.geocrew.android

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColorCodeTest {
    private val translucent = Color(0x801E88E5)

    @Test
    fun opaqueColoursHaveSixDigits() {
        assertEquals("1E88E5", hexCode(Color(0xFF1E88E5), alpha = true))
        assertEquals("#1e88e5", colorHex(Color(0xFF1E88E5)))
        assertEquals("#1e88e5", mapColor(Color(0xFF1E88E5)))
    }

    @Test
    fun theOpacityComesLast() {
        assertEquals("1E88E580", hexCode(translucent, alpha = true))
        assertEquals("1E88E5", hexCode(translucent, alpha = false), "without the opacity when the picker has none")
        assertEquals("#1e88e580", colorHex(translucent))
        assertEquals("rgba(30, 136, 229, 0.502)", mapColor(translucent))
    }

    @Test
    fun codesAreReadBack() {
        assertEquals(Color(0xFF1E88E5), colorOfCode("1e88e5"))
        assertEquals(translucent, colorOfCode("1E88E580"))
        assertNull(colorOfCode("1E88E"))
        assertNull(colorOfCode("1E88E5800"))
        assertNull(colorOfCode("GGGGGG"))
    }

    @Test
    fun aStyleKeepsItsColoursOpacity() {
        val style = LayerStyle(translucent, field = "rock", classes = listOf(StyleClass("砂岩", Color(0x40E53935))))
        assertEquals("#1e88e580", style.json()["color"].toString().trim('"'))
        assertEquals("rgba(229, 57, 53, 0.251)", style.mapColorFor(kotlinx.serialization.json.Json.parseToJsonElement("""{"rock": "砂岩"}""") as kotlinx.serialization.json.JsonObject))
    }
}
