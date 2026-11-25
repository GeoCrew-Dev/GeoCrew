package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoreholeTest {
    private val log = Borehole(
        layers = listOf(
            BoreLayer("1", 1.2, "杂填土"),
            BoreLayer("2", 4.5, "粉质黏土", "可塑"),
            BoreLayer("3", 4.0, "笔误"),
            BoreLayer("4", 12.0, "强风化花岗岩"),
        ),
        depth = 15.0,
        water = 3.2,
    )

    @Test
    fun intervalsRunFromTheCollarDown() {
        val iv = log.intervals()
        assertEquals(listOf("1", "2", "4"), iv.map { it.layer.no })
        assertEquals(0.0, iv[0].top, 0.0)
        assertEquals(4.5, iv[2].top, 0.0)
        assertEquals(7.5, iv[2].thickness, 1e-9)
        assertEquals(15.0, log.total, 0.0)
        assertEquals(12.0, log.copy(depth = null).total, 0.0)
        assertEquals(1, log.problems().size)
        assertTrue(log.copy(depth = 10.0).problems().any { "终孔" in it })
    }

    @Test
    fun roundTripsAndDropsBadValues() {
        assertEquals(log, Borehole.fromJson(log.toJson()))
        val o = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"depth": -1, "water": "x", "layers": [{"no": "1", "bottom": 0}, {"no": "2", "bottom": 2.5, "future": true}, 7]}""",
        ) as kotlinx.serialization.json.JsonObject
        val b = Borehole.fromJson(o)
        assertNull(b.depth)
        assertNull(b.water)
        assertEquals(listOf(BoreLayer("2", 2.5)), b.layers)
    }
}
