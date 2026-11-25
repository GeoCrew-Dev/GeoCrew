package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ScanMatchTest {
    private val t0 = Instant.parse("2026-11-24T00:00:00Z")

    private fun f(id: String, title: String?, sample: String? = null, deleted: Boolean = false) = GeoFeature(
        id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(116.0, 40.0)),
        buildProperties {
            title?.let { this[Props.TITLE] = it }
            sample?.let { samples(listOf(Sample(it))) }
        },
        t0, SyncInfo(updatedAt = t0, deleted = deleted),
    )

    private val list = listOf(f("x", "D001"), f("y", "D002", sample = "Hs-07"), f("z", "Hs-07"), f("w", "D009", sample = "S-1", deleted = true))

    @Test fun sampleNumberFirst() = assertEquals("y", ScanMatch.find(list, " hs-07 ")?.id)
    @Test fun thenTitle() = assertEquals("x", ScanMatch.find(list, "d001")?.id)
    @Test fun thenId() = assertEquals("z", ScanMatch.find(list, "z")?.id)
    @Test fun skipsDeleted() = assertNull(ScanMatch.find(list, "S-1"))
    @Test fun blank() = assertNull(ScanMatch.find(list, "  "))
}
