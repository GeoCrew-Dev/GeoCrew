package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecordCopyTest {
    private val t0 = Instant.ofEpochSecond(100)
    private val now = Instant.ofEpochSecond(900)
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val outside = "33333333-3333-4333-8333-333333333333"

    private fun rec(id: String, block: PropertiesBuilder.() -> Unit = {}) = GeoFeature(
        id, "old", FeatureKind.GEO_POINT, Geometry.Point(LngLat(116.0, 40.0)), buildProperties(block), t0,
        SyncInfo(version = 7, updatedAt = t0, updatedBy = "m1", seq = 42, dirty = false),
    )

    @Test
    fun copiesGetNewIdsFreshHistoryAndInnerLinks() {
        val route = rec(b) {
            this[Props.TITLE] = "路线 L1"
            put("stops", JsonArray(listOf(JsonPrimitive(a), JsonPrimitive(outside))))
            this[Props.ASSIGNEE] = "m2"
            this[Props.TASK_STATUS] = "open"
        }
        val gone = rec("44444444-4444-4444-8444-444444444444").markDeleted(t0)
        var n = 0
        val out = RecordCopy.copies(listOf(rec(a) { this[Props.LITHOLOGY] = "灰岩" }, route, gone), "new", now) { "new-${++n}" }

        assertEquals(listOf("new-1", "new-2"), out.map { it.id })
        out.forEach {
            assertEquals("new", it.projectId)
            assertEquals(SyncInfo(updatedAt = now), it.sync)
            assertEquals(t0, it.createdAt)
        }
        assertEquals("灰岩", out[0].properties.string(Props.LITHOLOGY))
        val stops = (out[1].properties["stops"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("new-1", outside), stops)
        assertFalse(Props.ASSIGNEE in out[1].properties)
        assertFalse(Props.TASK_STATUS in out[1].properties)
        assertTrue(out[1].title == "路线 L1")
    }
}
