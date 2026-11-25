package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class RendezvousTest {
    private val now = Instant.parse("2026-11-24T06:00:00Z") // 14:00 in Beijing
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun point(id: String, at: Instant?, g: Geometry = Geometry.Point(LngLat(117.0, 31.0)), deleted: Boolean = false) = GeoFeature(
        id, "p", FeatureKind.POINT, g,
        buildProperties { at?.let { this[Props.MEET_AT] = it.toString() } },
        now, SyncInfo(updatedAt = now, deleted = deleted),
    )

    @Test
    fun picksTheMeetingClosestInTimeWithinTheWindow() {
        val list = listOf(
            point("old", now.minus(Duration.ofHours(3))),
            point("late", now.minus(Duration.ofMinutes(20))),
            point("soon", now.plus(Duration.ofHours(2))),
            point("far", now.plus(Duration.ofDays(3))),
            point("gone", now.plus(Duration.ofMinutes(5)), deleted = true),
            point("plain", null),
        )
        assertEquals("late", Rendezvous.next(list, now)?.feature?.id)
        assertEquals("soon", Rendezvous.next(list.filter { it.id != "late" }, now)?.feature?.id)
        assertNull(Rendezvous.next(list.filter { it.id in setOf("old", "far", "gone", "plain") }, now))
    }

    @Test
    fun onlyPointsAreMeetingPoints() {
        val line = point("l", now, Geometry.LineString(listOf(LngLat(117.0, 31.0), LngLat(117.1, 31.0))))
        assertNull(line.meetAt)
        assertNull(Rendezvous.next(listOf(line), now))
    }

    @Test
    fun readsTypedTimesTodayOrTomorrow() {
        assertEquals(Instant.parse("2026-11-24T09:30:00Z"), Rendezvous.timeToday("17:30", now, zone))
        assertEquals(Instant.parse("2026-11-24T09:30:00Z"), Rendezvous.timeToday("17：30", now, zone))
        // Half an hour ago is still today, for someone setting it late.
        assertEquals(Instant.parse("2026-11-24T05:30:00Z"), Rendezvous.timeToday("13:30", now, zone))
        // Breakfast time means tomorrow.
        assertEquals(Instant.parse("2026-11-24T23:30:00Z"), Rendezvous.timeToday("7:30", now, zone))
        assertNull(Rendezvous.timeToday("25:00", now, zone))
        assertNull(Rendezvous.timeToday("下午", now, zone))
    }

    @Test
    fun countsDown() {
        assertEquals("还有 1 小时 20 分", Rendezvous.countdown(now.plus(Duration.ofMinutes(80)), now))
        assertEquals("还有 2 小时", Rendezvous.countdown(now.plus(Duration.ofMinutes(120)), now))
        assertEquals("已过 10 分钟", Rendezvous.countdown(now.minus(Duration.ofMinutes(10)), now))
        assertEquals("就是现在", Rendezvous.countdown(now.plusSeconds(20), now))
    }
}
