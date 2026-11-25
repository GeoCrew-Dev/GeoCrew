package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LegendTest {
    private fun f(id: String, props: PropertiesBuilder.() -> Unit) =
        GeoFeature(id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(116.0, 40.0)), buildProperties(props), Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH))

    @Test fun coloursByValueIgnoringCaseAndSpace() {
        val l = Legend(Props.LITHOLOGY, listOf(LegendEntry("花岗岩", "#F48FB1"), LegendEntry("Sandstone", "#FFEE58")))
        assertEquals("#F48FB1", l.colorOf(f("a") { this[Props.LITHOLOGY] = " 花岗岩 " }))
        assertEquals("#FFEE58", l.colorOf(f("b") { this[Props.LITHOLOGY] = "sandstone" }))
        assertNull(l.colorOf(f("c") { this[Props.LITHOLOGY] = "页岩" }))
        assertNull(l.colorOf(f("d") {}))
        assertNull(Legend().colorOf(f("e") { this[Props.LITHOLOGY] = "花岗岩" }))
        val used = l.used(listOf(f("b") { this[Props.LITHOLOGY] = "SANDSTONE" }, f("c") { this[Props.LITHOLOGY] = "页岩" }))
        assertEquals(listOf("Sandstone"), used.map { it.value })
    }

    @Test fun editsAndSuggestions() {
        var l = Legend("formation").withValues(listOf("花岗闪长岩", "粉砂岩", "泥灰岩", "某某组", "某某组 ", ""))
        assertEquals(listOf("花岗闪长岩", "粉砂岩", "泥灰岩", "某某组"), l.entries.map { it.value })
        assertEquals(listOf("#F48FB1", "#FFF59D", "#90CAF9", Legend.palette[0]), l.entries.map { it.color })
        l = l.put(LegendEntry("某某组", "#000000")).remove("粉砂岩")
        assertEquals(listOf("花岗闪长岩" to "#F48FB1", "泥灰岩" to "#90CAF9", "某某组" to "#000000"), l.entries.map { it.value to it.color })
        // A second unknown value skips the palette colour already taken.
        assertEquals(Legend.palette[1], Legend.suggestColor("另一组", listOf(Legend.palette[0])))
    }

    @Test fun cleanDropsBadEntries() {
        val l = Legend.clean(Legend(" lithology ", listOf(LegendEntry(" 砂岩", "#FFEE58"), LegendEntry("砂岩", "#000000"), LegendEntry("", "#111111"), LegendEntry("页岩", "red"))))
        assertEquals(Legend("lithology", listOf(LegendEntry("砂岩", "#FFEE58"))), l)
    }
}
