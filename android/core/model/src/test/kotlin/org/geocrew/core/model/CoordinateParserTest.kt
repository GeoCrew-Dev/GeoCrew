package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateParserTest {
    private fun check(text: String, lng: Double, lat: Double) {
        val p = CoordinateParser.parse(text) ?: throw AssertionError("not parsed: $text")
        assertEquals(text, lng, p.lng, 1e-5)
        assertEquals(text, lat, p.lat, 1e-5)
    }

    @Test
    fun decimal() {
        check("116.391, 39.907", 116.391, 39.907)
        check("39.907 116.391", 116.391, 39.907)
        check("-70.5;-33.4", -70.5, -33.4)
        check("85.2 30.1", 85.2, 30.1)
    }

    @Test
    fun hemispheresAndDms() {
        val lng = 116 + 23 / 60.0 + 28 / 3600.0
        val lat = 39 + 54 / 60.0 + 25 / 3600.0
        check("N39°54'25\" E116°23'28\"", lng, lat)
        check("39°54′25″N, 116°23′28″E", lng, lat)
        check("东经116度23分28秒 北纬39度54分25秒", lng, lat)
        check("39.907n 116.391e", 116.391, 39.907)
        check("33.5S 70.6W", -70.6, -33.5)
        check("北纬 39°54.5′ 东经 116°23′", 116 + 23 / 60.0, 39 + 54.5 / 60.0)
    }

    @Test
    fun labels() {
        check("经度：116.391，纬度：39.907", 116.391, 39.907)
        check("纬度 39.907 经度 116.391", 116.391, 39.907)
    }

    @Test
    fun formatterOutputReadsBack() {
        val p = LngLat(116.391234, 39.907654)
        for (f in CoordinateFormat.entries) {
            val back = CoordinateParser.parse(CoordinateFormatter.format(p, f))!!
            assertEquals(p.lng, back.lng, 1e-4)
            assertEquals(p.lat, back.lat, 1e-4)
        }
    }

    @Test
    fun rejectsNonsense() {
        assertNull(CoordinateParser.parse(""))
        assertNull(CoordinateParser.parse("116.4"))
        assertNull(CoordinateParser.parse("1 2 3"))
        assertNull(CoordinateParser.parse("N39 N40"))
        assertNull(CoordinateParser.parse("200 100"))
        assertNull(CoordinateParser.parse("39°75′ 116°"))
    }
}
