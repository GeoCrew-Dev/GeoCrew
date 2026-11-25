package org.geocrew.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReferenceLookupTest {
    private val t = Instant.parse("2026-11-25T00:00:00Z")

    private fun f(g: Geometry, vararg props: Pair<String, String>) = GeoFeature(
        "x", "p", FeatureKind.POLYGON, g, JsonObject(props.toMap().mapValues { JsonPrimitive(it.value) }), t, SyncInfo(updatedAt = t),
    )

    private fun square(w: Double, s: Double, e: Double, n: Double) =
        listOf(LngLat(w, s), LngLat(e, s), LngLat(e, n), LngLat(w, n), LngLat(w, s))

    private val units = ReferenceLayer("u", "1:5万地质图", colorBy = "代号")
    private val map = listOf(
        f(Geometry.Polygon(listOf(square(116.0, 40.0, 116.01, 40.01))), "代号" to "J3d", "岩性" to "安山岩"),
        f(Geometry.Polygon(listOf(square(116.01, 40.0, 116.02, 40.01))), "代号" to "K1b"),
        // A fault running north-south along 116.012.
        f(Geometry.LineString(listOf(LngLat(116.012, 39.99), LngLat(116.012, 40.02))), "代号" to "F3"),
    )

    @Test
    fun findsTheUnitAndTheNearbyFault() {
        val r = ReferenceLookup.at(LngLat(116.0105, 40.005), listOf(units to map))
        assertEquals(listOf("K1b"), r.areas.map { it.value })
        assertEquals("F3", r.lines.single().value)
        assertEquals(128.0, r.lines.single().meters, 3.0)
        assertEquals("这里在「1:5万地质图」的「K1b」里；离「1:5万地质图」的「F3」约 127 米。", r.text())
    }

    @Test
    fun holesAndFarLinesDoNotCount() {
        val ring = Geometry.Polygon(listOf(square(0.0, 0.0, 1.0, 1.0), square(0.4, 0.4, 0.6, 0.6)))
        val r = ReferenceLookup.at(LngLat(0.5, 0.5), listOf(units to listOf(f(ring, "代号" to "A"))))
        assertTrue(r.empty)
        assertEquals("这里不在任何参考图层的图形里。", r.text())
        val far = ReferenceLookup.at(LngLat(116.005, 40.005), listOf(units to map))
        assertEquals(listOf("J3d"), far.areas.map { it.value })
        assertTrue(far.lines.isEmpty())
        assertEquals("安山岩", far.areas.single().attributes["岩性"])
    }
}
