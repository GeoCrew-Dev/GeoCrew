package org.geocrew.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ReferenceLayerTest {
    private val t = Instant.parse("2026-11-25T00:00:00Z")

    private fun f(vararg props: Pair<String, String>) = GeoFeature(
        "x", "p", FeatureKind.POLYGON, null,
        JsonObject(props.toMap().mapValues { JsonPrimitive(it.value) }), t, SyncInfo(updatedAt = t),
    )

    @Test
    fun picksAFamiliarAttributeFirst() {
        val l = listOf(f("OBJECTID" to "1", "代号" to "J3d", "area" to "12.5"), f("OBJECTID" to "2", "代号" to "K1b", "area" to "3.1"))
        assertEquals("代号", ReferenceLayer.guessColorBy(l))
    }

    @Test
    fun elseTheAttributeWithAFewSharedValues() {
        val l = (1..30).map { f("objectid" to "$it", "type" to if (it % 2 == 0) "A" else "B", "len" to "$it") }
        assertEquals("type", ReferenceLayer.guessColorBy(l))
        assertNull(ReferenceLayer.guessColorBy(listOf(f())))
    }

    @Test
    fun coloursAreStableAndFollowTheLegend() {
        assertEquals(ReferenceLayer.colorFor("J3d"), ReferenceLayer.colorFor("j3d"))
        assertNotEquals(ReferenceLayer.DEFAULT_COLOR, ReferenceLayer.colorFor("J3d"))
        assertEquals(ReferenceLayer.DEFAULT_COLOR, ReferenceLayer.colorFor(null))
        val legend = Legend(entries = listOf(LegendEntry("J3d", "#112233")))
        assertEquals("#112233", ReferenceLayer.colorFor("J3d", legend))
    }
}
