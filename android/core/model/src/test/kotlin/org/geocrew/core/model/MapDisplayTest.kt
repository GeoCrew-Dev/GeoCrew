package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

class MapDisplayTest {
    private fun f(id: String, kind: FeatureKind, task: TaskInfo? = null) = GeoFeature(
        id, "p", kind, Geometry.Point(LngLat(1.0, 2.0)),
        buildProperties { task?.writeTo(this) }, Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH),
    )

    private val list = listOf(
        f("a", FeatureKind.POINT),
        f("b", FeatureKind.TRACK),
        f("c", FeatureKind.POINT, TaskInfo("me", "我")),
        f("d", FeatureKind.LINE, TaskInfo("me", "我", TaskStatus.DONE)),
        f("e", FeatureKind.LINE, TaskInfo("other", "他")),
    )

    @Test
    fun defaultShowsEverythingUnchanged() {
        assertFalse(MapDisplay().filtering)
        assertSame(list, MapDisplay().filter(list, "me"))
    }

    @Test
    fun hidesKindsButKeepsTheSelection() {
        val d = MapDisplay().toggle(FeatureKind.TRACK).toggle(FeatureKind.POINT)
        assertEquals(listOf("d", "e"), d.filter(list, "me").map { it.id })
        assertEquals(listOf("a", "d", "e"), d.filter(list, "me", keep = "a").map { it.id })
        assertEquals(MapDisplay(hiddenKinds = listOf("track")), d.toggle(FeatureKind.POINT))
    }

    @Test
    fun onlyMyOpenTasks() {
        val d = MapDisplay(onlyMyTasks = true)
        assertEquals(listOf("c"), d.filter(list, "me").map { it.id })
        assertEquals(emptyList<String>(), d.filter(list, null).map { it.id })
    }

    @Test
    fun anchors() {
        assertNull(MapDisplay.anchor(null))
        val line = Geometry.LineString(listOf(LngLat(0.0, 0.0), LngLat(1.0, 1.0), LngLat(2.0, 2.0)))
        assertEquals(LngLat(1.0, 1.0), MapDisplay.anchor(line))
        val square = Geometry.Polygon(listOf(listOf(LngLat(0.0, 0.0), LngLat(2.0, 0.0), LngLat(2.0, 2.0), LngLat(0.0, 2.0), LngLat(0.0, 0.0))))
        assertEquals(LngLat(1.0, 1.0), MapDisplay.anchor(square))
    }

    private fun made(id: String, at: String, title: String? = null, lith: String? = null) = GeoFeature(
        id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(1.0, 2.0)),
        buildProperties {
            title?.let { this[Props.TITLE] = it }
            lith?.let { this[Props.LITHOLOGY] = it }
        },
        Instant.parse(at), SyncInfo(updatedAt = Instant.parse(at)),
    )

    @Test
    fun recentDaysCountTodayAsOneInTheLocalZone() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-11-25T02:00:00Z") // 10:00 in Beijing
        val l = listOf(
            made("today", "2026-11-24T16:30:00Z"), // 00:30 on the 25th, Beijing
            made("yesterday", "2026-11-24T15:30:00Z"),
            made("old", "2026-11-20T04:00:00Z"),
        )
        assertEquals(listOf("today"), MapDisplay(days = 1).filter(l, null, now = now, zone = zone).map { it.id })
        assertEquals(listOf("today", "yesterday"), MapDisplay(days = 3).filter(l, null, now = now, zone = zone).map { it.id })
        assertEquals(listOf("today", "yesterday", "old"), MapDisplay(days = 0).filter(l, null, now = now, zone = zone).map { it.id })
    }

    @Test
    fun queryMatchesEveryWordInNameOrLithology() {
        val l = listOf(made("a", "2026-01-01T00:00:00Z", "D012", "灰岩"), made("b", "2026-01-01T00:00:00Z", "D013", "砂岩"))
        assertEquals(listOf("a"), MapDisplay(query = " 灰岩 ").filter(l, null).map { it.id })
        assertEquals(listOf("b"), MapDisplay(query = "d013 砂").filter(l, null).map { it.id })
        assertEquals(emptyList<String>(), MapDisplay(query = "D012 砂岩").filter(l, null).map { it.id })
        assertEquals(listOf("a"), MapDisplay(query = "页岩").filter(l, null, keep = "a").map { it.id })
    }
}
