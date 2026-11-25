package org.geocrew.core.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class OutingTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun choicesRoundUpAndEndAtSunset() {
        val now = Instant.parse("2026-06-01T02:03:00Z") // 10:03 in Beijing
        val c = Outing.choices(now, zone, LngLat(116.4, 39.9))
        assertEquals(Instant.parse("2026-06-01T03:05:00Z"), c.first().at)
        assertEquals("日落前", c.last().label) // sunset near 19:40, after four hours
        assertTrue(c.zipWithNext().all { (a, b) -> a.at < b.at })
        val evening = Outing.choices(Instant.parse("2026-06-01T11:30:00Z"), zone, LngLat(116.4, 39.9))
        assertTrue(evening.none { it.label == "日落前" }) // 19:30, sunset is less than half an hour away
        assertEquals(4, Outing.choices(now, zone, null).size)
    }

    @Test
    fun typedTimeIsTheNextOne() {
        val now = Instant.parse("2026-06-01T08:00:00Z") // 16:00
        assertEquals(Instant.parse("2026-06-01T10:30:00Z"), Outing.typed("18:30", now, zone))
        assertEquals(Instant.parse("2026-06-01T10:00:00Z"), Outing.typed("18点", now, zone))
        assertEquals(Instant.parse("2026-06-02T01:00:00Z"), Outing.typed("9:00", now, zone))
        assertNull(Outing.typed("明天", now, zone))
        assertNull(Outing.typed("25:00", now, zone))
    }

    @Test
    fun dueSaysWhenLate() {
        val at = Instant.parse("2026-06-01T08:00:00Z")
        assertEquals("还有 1 小时 20 分", Outing.due(at, at.minusSeconds(80 * 60)))
        assertEquals("已超时 15 分钟", Outing.due(at, at.plusSeconds(15 * 60)))
    }
}
