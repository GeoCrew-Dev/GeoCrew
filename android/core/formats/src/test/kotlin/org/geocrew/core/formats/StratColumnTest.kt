package org.geocrew.core.formats

import org.geocrew.core.model.Attitude
import org.geocrew.core.model.MeasuredSection
import org.geocrew.core.model.SectionLayer
import org.geocrew.core.model.TraverseLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StratColumnTest {
    // A level traverse due east across vertical beds striking north: true thickness equals the tape distance.
    private val vertical = Attitude(90.0, 90.0)
    private val section = MeasuredSection(
        legs = listOf(TraverseLeg(90.0, 0.0, 101.0)),
        layers = listOf(
            SectionLayer("1", 10.0, "中粒砂岩", vertical),
            SectionLayer("2", 40.0, "灰绿色页岩"),
            SectionLayer("3", 100.0, "厚层灰岩", note = "含腕足化石"),
            SectionLayer("4", 100.5, "煤线"),
        ),
    )

    @Test fun stacksLayersYoungestOnTopAtAScaleThatFits() {
        val c = StratColumn.build("P1", section)
        assertEquals(listOf("4", "3", "2", "1"), c.rows.map { it.no })
        assertEquals(500, c.scale)
        assertEquals(100.5, c.total, 1e-6)
        val l3 = c.rows[1]
        assertEquals(60.0, l3.thickness!!, 1e-6)
        assertEquals(40.0, l3.base, 1e-6)
        assertEquals(100.0, l3.top, 1e-6)
        assertEquals(120.0, l3.heightMm, 1e-6)
        assertEquals(LithoPattern.LIMESTONE, l3.pattern)
        assertTrue(l3.borrowed)
        assertEquals("含腕足化石", l3.note)
        // Half a metre is 1 mm at 1:500: drawn taller so it stays readable.
        assertTrue(c.rows[0].stretched)
        assertEquals(3.0, c.rows[0].heightMm, 1e-6)
        assertFalse(c.rows[1].stretched)
        assertTrue(c.heightMm <= 230.0)
    }

    @Test fun aTraverseWalkedDownTheSequencePutsLayerOneOnTop() {
        val c = StratColumn.build("P1", section, startIsBase = false)
        assertEquals(listOf("1", "2", "3", "4"), c.rows.map { it.no })
        assertEquals(0.0, c.rows.last().base, 1e-6)
    }

    @Test fun layersWithoutAnyAttitudeHaveNoThickness() {
        val c = StratColumn.build("P2", MeasuredSection(listOf(TraverseLeg(90.0, 0.0, 50.0)), listOf(SectionLayer("1", 20.0, "砂岩"), SectionLayer("2", 50.0, "泥岩"))))
        assertEquals(2, c.unknown)
        assertNull(c.rows[0].thickness)
        assertEquals(0.0, c.total, 0.0)
    }

    @Test fun picksPatternsByTheMostSpecificWord() {
        assertEquals(LithoPattern.SILTSTONE, LithoPattern.of("泥质粉砂岩"))
        assertEquals(LithoPattern.CONGLOMERATE, LithoPattern.of("砂砾岩"))
        assertEquals(LithoPattern.MARL, LithoPattern.of("泥灰岩"))
        assertEquals(LithoPattern.LIMESTONE, LithoPattern.of("白云质灰岩"))
        assertEquals(LithoPattern.DOLOMITE, LithoPattern.of("白云岩"))
        assertEquals(LithoPattern.INTRUSIVE, LithoPattern.of("白云母花岗岩"))
        assertEquals(LithoPattern.SANDSTONE, LithoPattern.of("凝灰质砂岩"))
        assertEquals(LithoPattern.MUDSTONE, LithoPattern.of("炭质页岩"))
        assertEquals(LithoPattern.INTRUSIVE, LithoPattern.of("黑云母花岗岩"))
        assertEquals(LithoPattern.VOLCANIC, LithoPattern.of("流纹质凝灰岩"))
        assertEquals(LithoPattern.METAMORPHIC, LithoPattern.of("二云母片岩"))
        assertEquals(LithoPattern.COAL, LithoPattern.of("煤层"))
        assertEquals(LithoPattern.SANDSTONE, LithoPattern.of("Sandstone"))
        assertEquals(LithoPattern.BLANK, LithoPattern.of("第四系"))
    }
}
