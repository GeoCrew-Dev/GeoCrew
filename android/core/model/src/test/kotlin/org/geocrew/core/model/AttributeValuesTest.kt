package org.geocrew.core.model

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AttributeValuesTest {
    private fun rec(id: String, deleted: Boolean = false, block: PropertiesBuilder.() -> Unit) = GeoFeature(
        id = id,
        projectId = "p",
        kind = FeatureKind.GEO_POINT,
        geometry = Geometry.Point(LngLat(116.0, 40.0)),
        properties = buildProperties(block),
        createdAt = Instant.EPOCH,
        sync = SyncInfo(updatedAt = Instant.EPOCH, deleted = deleted),
    )

    private val records = listOf(
        rec("a") { this[Props.LITHOLOGY] = "花岗岩"; this["formation"] = "蓟县系"; this["grade"] = 2.5; this["stroke"] = "#FF0000" },
        rec("b") { this[Props.LITHOLOGY] = "花岗岩 "; this["formation"] = "蓟县系" },
        rec("c") { this[Props.LITHOLOGY] = "Granite"; this[Props.TITLE] = "D001" },
        rec("d") { this[Props.LITHOLOGY] = "granite。" },
        rec("e") { this[Props.LITHOLOGY] = "片麻岩"; this["formation"] = "长城系" },
        rec("x", deleted = true) { this[Props.LITHOLOGY] = "灰岩" },
    )

    @Test
    fun keysListTextAttributesFormFirst() {
        val form = listOf(FieldTemplate(key = "formation", label = "地层"))
        val keys = AttributeValues.keys(records, form)
        assertEquals(listOf("formation", Props.LITHOLOGY), keys.map { it.key })
        assertEquals(AttributeValues.Key("formation", "地层", 3, 2), keys[0])
        assertEquals(AttributeValues.Key(Props.LITHOLOGY, "岩性", 5, 4), keys[1])
    }

    @Test
    fun valuesAreTrimmedAndCountedOnLiveRecords() {
        val v = AttributeValues.values(records, Props.LITHOLOGY)
        assertEquals(listOf("花岗岩" to 2, "Granite" to 1, "granite。" to 1, "片麻岩" to 1), v.map { it.value to it.count })
        val groups = AttributeValues.similar(v)
        assertEquals(listOf(listOf("Granite", "granite。")), groups.map { g -> g.map { it.value } })
        assertEquals(AttributeValues.normalize("ｇｒａｎｉｔｅ"), AttributeValues.normalize(" Granite."))
    }

    @Test
    fun renameMergesOrRemoves() {
        val b = records[1].properties
        val renamed = AttributeValues.rename(b, Props.LITHOLOGY, setOf("花岗岩"), "二长花岗岩")!!
        assertEquals(JsonPrimitive("二长花岗岩"), renamed[Props.LITHOLOGY])
        assertEquals(JsonPrimitive("蓟县系"), renamed["formation"])
        // The untrimmed spelling is fixed even when the trimmed text stays.
        assertEquals(JsonPrimitive("花岗岩"), AttributeValues.rename(b, Props.LITHOLOGY, setOf("花岗岩"), "花岗岩")!![Props.LITHOLOGY])
        assertNull(AttributeValues.rename(records[0].properties, Props.LITHOLOGY, setOf("花岗岩"), "花岗岩"))
        assertNull(AttributeValues.rename(records[2].properties, Props.LITHOLOGY, setOf("花岗岩"), "x"))
        assertNull(AttributeValues.rename(records[0].properties, "grade", setOf("2.5"), "3"))
        val removed = AttributeValues.rename(records[4].properties, "formation", setOf("长城系"), " ")!!
        assertNull(removed["formation"])
    }
}
