package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneGridTest {
    private val beijing = Bounds(116.30, 39.90, 116.40, 39.96)

    @Test fun spacingKeepsAboutSixLines() {
        assertEquals(1_000, PlaneGrid.spacing(5_500.0))
        assertEquals(2_000, PlaneGrid.spacing(8_000.0))
        assertEquals(100, PlaneGrid.spacing(300.0))
        assertEquals(100_000, PlaneGrid.spacing(5_000_000.0))
    }

    @Test fun linesAreRoundKilometresOfTheZone() {
        val g = PlaneGrid.of(beijing, 3)!!
        assertEquals(39, g.zone)
        assertEquals(2_000, g.spacing)
        val tm = TransverseMercator(Ellipsoid.CGCS2000, 117.0)
        g.lines.forEach { l ->
            val values = l.positions.map { p -> tm.forward(p).let { (e, n) -> if (l.northing) n else e } }
            values.forEach { v -> assertEquals(Math.round(v / 1000.0) * 1000.0, v, 0.01) }
            assertTrue(values.first() % g.spacing < 0.01 || g.spacing - values.first() % g.spacing < 0.01)
        }
        val east = g.lines.filterNot { it.northing }
        assertTrue(east.isNotEmpty() && east.all { it.label.startsWith("39 ") })
        val north = g.lines.filter { it.northing }
        assertTrue(north.map { it.label }.toString(), north.any { it.label == "4422" })
        // Labels sit inside the view.
        g.lines.mapNotNull { it.anchor }.forEach { a ->
            assertTrue(a.toString(), a.lng in beijing.west..beijing.east && a.lat in beijing.south..beijing.north)
        }
    }

    @Test fun finerViewsLabelTenthsAndWideViewsGetNone() {
        val g = PlaneGrid.of(Bounds(116.300, 39.900, 116.305, 39.903), 6)!!
        assertEquals(20, g.zone)
        assertTrue(g.lines.all { it.label.contains('.') })
        assertNull(PlaneGrid.of(Bounds(100.0, 30.0, 110.0, 35.0), 6))
    }
}
