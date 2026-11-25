package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SunTimesTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun hm(i: Instant?, zone: ZoneId = shanghai) = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(i)

    private fun near(expected: String, actual: Instant?, zone: ZoneId = shanghai, minutes: Int = 2) {
        val (h, m) = expected.split(":").map(String::toInt)
        val t = actual!!.atZone(zone).toLocalTime()
        val diff = kotlin.math.abs(t.hour * 60 + t.minute + t.second / 60.0 - (h * 60 + m))
        assertTrue("expected ~$expected, got ${hm(actual, zone)}", diff <= minutes)
    }

    @Test
    fun beijingSolstices() {
        // Published times for Beijing (39.90 N, 116.41 E).
        val bj = LngLat(116.4074, 39.9042)
        val june = SunTimes.of(LocalDate.of(2026, 6, 21), bj)
        near("04:46", june.sunrise)
        near("19:46", june.sunset)
        val dec = SunTimes.of(LocalDate.of(2026, 12, 21), bj)
        near("07:33", dec.sunrise)
        near("16:53", dec.sunset)
        assertTrue(june.daylightMinutes in 890..910)
    }

    @Test
    fun twilightBracketsTheSun() {
        val d = SunTimes.of(LocalDate.of(2026, 3, 20), LngLat(104.07, 30.67))
        assertTrue(d.dawn!! < d.sunrise!! && d.sunrise < d.noon && d.noon < d.sunset!! && d.sunset < d.dusk!!)
        // Civil twilight lasts roughly 25 minutes at this latitude near the equinox.
        val evening = (d.dusk!!.epochSecond - d.sunset!!.epochSecond) / 60
        assertTrue("twilight $evening min", evening in 20..32)
    }

    @Test
    fun polarDayAndNight() {
        val tromso = LngLat(18.96, 69.65)
        val summer = SunTimes.of(LocalDate.of(2026, 6, 21), tromso)
        assertNull(summer.sunrise)
        assertEquals(SunDay.Polar.DAY, summer.polar)
        assertEquals(24 * 60L, summer.daylightMinutes)
        val winter = SunTimes.of(LocalDate.of(2026, 12, 21), tromso)
        assertNull(winter.sunset)
        assertEquals(SunDay.Polar.NIGHT, winter.polar)
        assertEquals(0L, winter.daylightMinutes)
    }
}
