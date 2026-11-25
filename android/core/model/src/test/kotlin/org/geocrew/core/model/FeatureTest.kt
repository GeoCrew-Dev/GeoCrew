package org.geocrew.core.model

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeatureTest {
    private val t0 = Instant.parse("2026-11-24T08:00:00Z")

    @Test
    fun editKeepsUnknownPropertiesAndBumpsVersion() {
        val f = GeoFeature(
            id = "f1", projectId = "p", kind = FeatureKind.POINT,
            geometry = Geometry.Point(LngLat(116.0, 40.0)),
            properties = buildProperties { this["myteam:sample_no"] = "S-01" },
            createdAt = t0, sync = SyncInfo(updatedAt = t0, dirty = false),
        )
        val e = f.edited(t0.plusSeconds(60)) { this[Props.TITLE] = "D001" }
        assertEquals("D001", e.title)
        assertEquals(JsonPrimitive("S-01"), e.properties["myteam:sample_no"])
        assertEquals(2, e.sync.version)
        assertTrue(e.sync.dirty)
        assertTrue(e.markDeleted(t0.plusSeconds(120)).deleted)
    }

    @Test
    fun conflictRule() {
        val a = SyncInfo(version = 2, updatedAt = t0, updatedBy = "a")
        assertTrue(a.copy(version = 3).isNewerThan(a))
        assertTrue(a.copy(updatedAt = t0.plusSeconds(1)).isNewerThan(a))
        assertTrue(a.copy(updatedBy = "b").isNewerThan(a))
        assertFalse(a.isNewerThan(a))
    }
}
