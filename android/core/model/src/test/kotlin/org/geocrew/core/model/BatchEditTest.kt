package org.geocrew.core.model

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class BatchEditTest {
    private val at = Instant.ofEpochSecond(100)
    private fun rec(id: String, kind: FeatureKind = FeatureKind.GEO_POINT, block: PropertiesBuilder.() -> Unit = {}) =
        GeoFeature(id, "p", kind, null, buildProperties(block), at, SyncInfo(updatedAt = at))

    private val fields = listOf(
        FieldTemplate("formation", "地层"),
        FieldTemplate("weathering", "风化程度", FieldType.CHOICE, options = listOf("微", "弱", "强")),
        FieldTemplate("depth", "深度", FieldType.NUMBER, kinds = listOf(FeatureKind.BOREHOLE.id)),
    )

    @Test
    fun targetsPutLithologyAndTheFormFirstAndSkipManagedKeys() {
        val used = listOf(
            AttributeValues.Key("lithology", "岩性", 3, 2),
            AttributeValues.Key("formation", "地层", 2, 1),
            AttributeValues.Key("color", "color", 1, 1),
            AttributeValues.Key(Props.TITLE, "title", 1, 1),
        )
        val t = BatchEdit.targets(fields, used)
        assertEquals(listOf("lithology", "formation", "weathering", "depth", "color"), t.map { it.key })
        assertEquals(listOf("微", "弱", "强"), t[2].options)
    }

    @Test
    fun encodingFollowsTheField() {
        val t = BatchEdit.targets(fields, emptyList()).associateBy { it.key }
        assertEquals(JsonPrimitive("007"), t.getValue("formation").encode(" 007 "))
        assertEquals(JsonPrimitive("弱"), t.getValue("weathering").encode("弱"))
        assertThrows(IllegalArgumentException::class.java) { t.getValue("weathering").encode("很强") }
        assertEquals(JsonPrimitive(12.5), t.getValue("depth").encode("12,5"))
        assertNull(t.getValue("formation").encode("  "))
        assertEquals(JsonPrimitive("2"), t.getValue("lithology").encode("2"))
        assertEquals(JsonPrimitive(3.0), BatchEdit.Target("color", "color").encode("3"))
    }

    @Test
    fun planSkipsUnchangedDeletedAndOtherKinds() {
        val depth = BatchEdit.targets(fields, emptyList()).first { it.key == "depth" }
        val records = listOf(
            rec("a", FeatureKind.BOREHOLE),
            rec("b", FeatureKind.BOREHOLE) { this["depth"] = 10.0 },
            rec("c"),
            rec("d", FeatureKind.BOREHOLE).markDeleted(at),
        )
        val p = BatchEdit.plan(records, depth, JsonPrimitive(10.0))
        assertEquals(listOf("a"), p.change.map { it.id })
        assertEquals(1, p.skipped)
    }
}
