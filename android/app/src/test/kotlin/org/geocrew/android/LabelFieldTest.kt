package org.geocrew.android

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class LabelFieldTest {
    // A layer with the template's name field and a rock type field.
    private val collection: JsonObject = Json.parseToJsonElement(
        """{"type": "FeatureCollection", "fields": [{"name": "点名", "type": "string"}, {"name": "岩性", "type": "string"}], "features": []}""",
    ).jsonObject

    @Test
    fun theNameFieldLabelsUntilAnotherIsChosen() {
        assertEquals("点名", labelField(LayerStyle(Color.Black), collection))
        assertEquals("岩性", labelField(LayerStyle(Color.Black, label = "岩性"), collection))
    }

    @Test
    fun labelsCanBeOff() {
        assertNull(labelField(LayerStyle(Color.Black, label = ""), collection))
    }

    @Test
    fun aFieldThatIsGoneLabelsNothing() {
        assertNull(labelField(LayerStyle(Color.Black, label = "产状"), collection))
    }

    @Test
    fun labelsShowTheFirstLineCutShort() {
        assertEquals("D001", MapLabels.shown("D001"))
        assertEquals("砂岩", MapLabels.shown("\n  砂岩  \n灰岩"))
        assertNull(MapLabels.shown(" \n "))
        val long = MapLabels.shown("x".repeat(100))!!
        assertEquals(40, long.length)
        assertEquals('…', long.last())
    }
}
