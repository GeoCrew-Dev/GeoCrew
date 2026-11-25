package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DaySummaryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 25)

    private fun f(id: String, kind: FeatureKind, at: String, props: Map<String, Any> = emptyMap(), geometry: Geometry? = null, deleted: Boolean = false) = GeoFeature(
        id, "p", kind, geometry,
        JsonObject(props.mapValues { (_, v) -> if (v is List<*>) JsonArray(v.map { JsonPrimitive(it.toString()) }) else JsonPrimitive(v.toString()) }),
        Instant.parse(at), SyncInfo(updatedAt = Instant.parse(at), deleted = deleted),
    )

    @Test
    fun countsTheDayInLocalTime() {
        val list = listOf(
            // 2026-11-24T17:00Z is 01:00 on the 25th in Beijing.
            f("a", FeatureKind.GEO_POINT, "2026-11-24T17:00:00Z", mapOf("lithology" to "灰岩", "photos" to listOf("x", "y"))),
            f("b", FeatureKind.GEO_POINT, "2026-11-25T03:00:00Z"),
            f("c", FeatureKind.GEO_POINT, "2026-11-24T10:00:00Z"), // the 24th locally
            f("d", FeatureKind.GEO_POINT, "2026-11-25T03:00:00Z", deleted = true),
            f("t", FeatureKind.TRACK, "2026-11-25T01:00:00Z", geometry = Geometry.LineString(listOf(LngLat(116.0, 40.0), LngLat(116.0, 40.01)))),
            f("l", FeatureKind.LINE, "2026-11-25T02:00:00Z"),
        )
        val s = DaySummary.of(list, day, zone, RecordRules(required = listOf("lithology")))
        assertEquals(2, s.points)
        assertEquals(1, s.otherRecords)
        assertEquals(2, s.photos)
        assertEquals(1, s.incomplete)
        assertEquals(1112.0, s.trackMeters, 2.0)
        assertEquals("今天：地质点 2 个，其他记录 1 条，照片 2 张，轨迹 1.1 公里；有 1 个点缺必填项，在「待核对」里。", s.text())
        assertTrue(DaySummary.of(emptyList(), day, zone).empty)
        assertEquals("今天还没有记录。", DaySummary.of(emptyList(), day, zone).text())
    }
}
