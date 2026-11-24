package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DailyActivityTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 24)

    private fun f(id: String, kind: FeatureKind, by: String?, at: String, g: Geometry? = null, deleted: Boolean = false, props: PropertiesBuilder.() -> Unit = {}) =
        GeoFeature(id, "p", kind, g, buildProperties(props), Instant.parse(at), SyncInfo(updatedAt = Instant.parse(at), updatedBy = by, deleted = deleted))

    @Test fun countsPerMemberForTheLocalDay() {
        val line = Geometry.LineString(listOf(LngLat(116.0, 40.0), LngLat(116.0, 40.01)))
        val list = listOf(
            f("a", FeatureKind.GEO_POINT, "m1", "2026-11-24T01:00:00Z") {
                samples(listOf(Sample("D1-1", takenAt = Instant.parse("2026-11-24T01:05:00Z")), Sample("D1-2", takenAt = Instant.parse("2026-11-20T01:00:00Z"))))
            },
            f("b", FeatureKind.POINT, "m1", "2026-11-24T15:30:00Z"), // 23:30 in Beijing: still today
            f("c", FeatureKind.POINT, "m1", "2026-11-24T16:30:00Z"), // already tomorrow in Beijing
            f("d", FeatureKind.POINT, "m2", "2026-11-24T02:00:00Z", deleted = true),
            f("t", FeatureKind.TRACK, "m2", "2026-11-24T09:00:00Z", line) { this[Props.STARTED_AT] = "2026-11-24T00:30:00Z" },
            f("u", FeatureKind.POINT, null, "2026-11-24T02:00:00Z"),
        )
        val r = DailyActivity.byMember(list, day, zone)
        assertEquals(setOf("m1", "m2"), r.keys)
        assertEquals(DailyActivity(records = 2, samples = 1), r["m1"])
        assertEquals(0, r["m2"]!!.records)
        assertEquals(1112.0, r["m2"]!!.trackMeters, 5.0)
        assertEquals("2 条记录、1 件样品", r["m1"]!!.label())
        assertEquals("轨迹 1.11 km", r["m2"]!!.label())
        assertNull(DailyActivity().label())
    }
}
