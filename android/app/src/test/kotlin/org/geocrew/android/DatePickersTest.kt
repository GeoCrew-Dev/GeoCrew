package org.geocrew.android

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatePickersTest {
    @Test
    fun datesAreReadAsTyped() {
        assertEquals(LocalDate.of(2026, 11, 27), dateOf("2026-11-27"))
        assertEquals(LocalDate.of(2026, 11, 27), dateOf("２０２６－１１－２７"), "full-width too")
        assertNull(dateOf("27/11/2026"))
        assertNull(dateOf(""))
    }

    @Test
    fun aDateAndTimeIsKeptInUtc() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val stored = storedDateTime(LocalDate.of(2026, 11, 27), LocalTime.of(10, 15), shanghai)
        assertEquals("2026-11-27T02:15:00Z", stored)
        assertEquals(LocalTime.of(10, 15), instantOf(stored)!!.atZone(shanghai).toLocalTime(), "read back where the phone is")
        assertEquals(stored, instantOf("2026-11-27T10:15:00+08:00").toString(), "with an offset")
        assertEquals(stored, instantOf("2026-11-27T10:15", shanghai).toString(), "local, without a zone")
        assertNull(instantOf("2026-11-27 10:15"), "not ISO 8601")
    }
}
