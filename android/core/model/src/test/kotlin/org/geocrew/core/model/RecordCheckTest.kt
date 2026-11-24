package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecordCheckTest {
    private val at = Instant.parse("2026-11-24T02:00:00Z")

    private fun rec(
        id: String,
        title: String?,
        lng: Double? = 116.0 + id.hashCode() % 10 * 0.001,
        kind: FeatureKind = FeatureKind.GEO_POINT,
        deleted: Boolean = false,
        edit: PropertiesBuilder.() -> Unit = {},
    ) = GeoFeature(
        id, "p", kind, lng?.let { Geometry.Point(LngLat(it, 40.0)) },
        buildProperties { this[Props.TITLE] = title; edit() }, at, SyncInfo(updatedAt = at, deleted = deleted),
    )

    @Test fun findsTheUsualSlips() {
        val list = listOf(
            rec("a", "D001") { samples(listOf(Sample("S1"), Sample("S2"))) },
            rec("b", "d001 ") { samples(listOf(Sample("s1"))) },
            rec("c", "D002", lng = null),
            rec("d", "D003") { this[Props.ACCURACY_M] = 65.0 },
            rec("e", "D004", lng = 120.0),
            rec("f", "D005"),
            rec("g", "断层", kind = FeatureKind.LINE),
            rec("h", "断层", kind = FeatureKind.LINE),
            rec("x", "D006", deleted = true) { samples(listOf(Sample("S2"))) },
            rec("r", "L001", lng = null, kind = FeatureKind.ROUTE) {
                put(Props.ROUTE, GeoRoute().plus(listOf("a", "x", "gone")).toJson())
            },
        )
        val issues = RecordCheck.of(list)
        fun codes(id: String) = issues[id].orEmpty().map { it.code }
        assertEquals(listOf(RecordCheck.Code.DUPLICATE_NUMBER, RecordCheck.Code.DUPLICATE_SAMPLE), codes("a"))
        assertEquals(listOf(RecordCheck.Code.DUPLICATE_NUMBER, RecordCheck.Code.DUPLICATE_SAMPLE), codes("b"))
        assertTrue(issues.getValue("a")[1].message.contains("d001"))
        assertEquals(listOf(RecordCheck.Code.NO_POSITION), codes("c"))
        assertEquals(listOf(RecordCheck.Code.POOR_ACCURACY), codes("d"))
        assertEquals(listOf(RecordCheck.Code.FAR_AWAY), codes("e"))
        assertEquals(listOf(RecordCheck.Code.MISSING_STOP), codes("r"))
        assertTrue(issues.getValue("r")[0].message.contains("2 个"))
        // Text names may repeat, deleted records never count, and a route needs no position.
        assertEquals(setOf("a", "b", "c", "d", "e", "r"), issues.keys)
    }
}
