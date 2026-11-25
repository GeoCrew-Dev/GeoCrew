package org.geocrew.core.model

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RecordCarryTest {
    private fun rec(id: String, at: Long, kind: FeatureKind = FeatureKind.GEO_POINT, deleted: Boolean = false, block: PropertiesBuilder.() -> Unit = {}) = GeoFeature(
        id = id, projectId = "p", kind = kind, geometry = Geometry.Point(LngLat(116.0, 40.0)),
        properties = buildProperties(block), createdAt = Instant.ofEpochSecond(at),
        sync = SyncInfo(updatedAt = Instant.ofEpochSecond(at), deleted = deleted),
    )

    private val fields = listOf(
        FieldTemplate("formation", "地层"),
        FieldTemplate("weathering", "风化程度", FieldType.CHOICE, options = listOf("微", "弱", "强")),
        FieldTemplate("track_only", "轨迹用", kinds = listOf(FeatureKind.TRACK.id)),
    )

    @Test
    fun previousIsTheLatestEarlierRecordOfTheSameKind() {
        val cur = rec("c", 300)
        val all = listOf(
            rec("a", 100), rec("b", 200), rec("x", 250, deleted = true), rec("l", 260, kind = FeatureKind.LINE),
            cur, rec("later", 400),
        )
        assertEquals("b", RecordCarry.previous(all, cur)?.id)
        assertNull(RecordCarry.previous(listOf(cur), cur))
    }

    @Test
    fun takesLithologyAndFormAnswersTheRecordLacks() {
        val prev = rec("b", 200) {
            this[Props.TITLE] = "D012"; this[Props.NOTE] = "灰白色"; this[Props.LITHOLOGY] = "花岗岩"
            this[Props.DIP] = 30; this["formation"] = "蓟县系"; this["weathering"] = "弱"; this["track_only"] = "x"
        }
        val fresh = rec("c", 300) { this[Props.TITLE] = "D013" }
        val v = RecordCarry.values(prev, fresh, fields)
        assertEquals(mapOf(Props.LITHOLOGY to JsonPrimitive("花岗岩"), "formation" to JsonPrimitive("蓟县系"), "weathering" to JsonPrimitive("弱")), v)
        assertEquals(listOf("岩性", "地层", "风化程度"), RecordCarry.labels(v, fields))

        // What the record already has stays; blanks on the previous one give nothing.
        val partly = rec("c", 300) { this["formation"] = "长城系" }
        val blankPrev = rec("b", 200) { this[Props.LITHOLOGY] = " "; this["weathering"] = "强" }
        assertEquals(mapOf("weathering" to JsonPrimitive("强")), RecordCarry.values(blankPrev, partly, fields))
        org.junit.Assert.assertTrue(RecordCarry.fresh(fresh, fields))
        org.junit.Assert.assertFalse(RecordCarry.fresh(partly, fields))
    }
}
