package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GeoRouteTest {
    private fun pt(id: String, at: String, lng: Double, kind: FeatureKind = FeatureKind.GEO_POINT, deleted: Boolean = false) =
        GeoFeature(id, "p", kind, Geometry.Point(LngLat(lng, 40.0)), buildProperties { this[Props.TITLE] = id }, Instant.parse(at), SyncInfo(updatedAt = Instant.parse(at), deleted = deleted))

    @Test fun jsonRoundTripAndEdits() {
        var r = GeoRoute(LocalDate.of(2026, 11, 24), "王、李", "追索 K1 底界").plus(listOf("a", "b", "c", "a"))
        r = r.withOnward("a", "沿沟向东，砂岩夹页岩").move(2, 0).copy(summary = "完成")
        assertEquals(listOf("c", "a", "b"), r.stops.map { it.featureId })
        val back = GeoRoute.fromJson(r.toJson())
        assertEquals(r, back)
        assertEquals("沿沟向东，砂岩夹页岩", back.stops[1].onward)
        assertEquals(listOf("c", "b"), back.remove("a").stops.map { it.featureId })
        // Bad and repeated stops are dropped; a bad date is ignored.
        val odd = GeoRoute.fromJson(buildProperties {
            put("date", kotlinx.serialization.json.JsonPrimitive("昨天"))
            put("stops", kotlinx.serialization.json.Json.parseToJsonElement("""[{"feature":"a"},{"feature":"a"},{"x":1},5]"""))
        })
        assertNull(odd.date)
        assertEquals(listOf("a"), odd.stops.map { it.featureId })
    }

    @Test fun pathSkipsMissingAndDeletedRecords() {
        val all = listOf(pt("a", "2026-11-24T01:00:00Z", 116.0), pt("b", "2026-11-24T02:00:00Z", 116.01, deleted = true), pt("c", "2026-11-24T03:00:00Z", 116.02))
        val r = GeoRoute().plus(listOf("a", "b", "gone", "c"))
        val byId = all.associateBy { it.id }::get
        assertEquals(listOf(116.0, 116.02), r.path(byId).map { it.lng })
        assertEquals(listOf(true, false, false, true), r.resolve(byId).map { it.feature != null })
    }

    @Test fun dayStopsInCreationOrder() {
        val zone = ZoneId.of("Asia/Shanghai")
        val all = listOf(
            pt("late", "2026-11-24T08:00:00Z", 116.0),
            pt("early", "2026-11-24T01:00:00Z", 116.0),
            pt("yesterday", "2026-11-23T15:00:00Z", 116.0), // 23:00 local
            pt("route", "2026-11-24T02:00:00Z", 116.0, FeatureKind.ROUTE),
            pt("dead", "2026-11-24T02:00:00Z", 116.0, deleted = true),
        )
        assertEquals(listOf("early", "late"), GeoRoute.dayStops(all, LocalDate.of(2026, 11, 24), zone).map { it.id })
    }
}
