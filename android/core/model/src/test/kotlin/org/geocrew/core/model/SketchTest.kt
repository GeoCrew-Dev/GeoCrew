package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchTest {
    @Test fun simplifyKeepsCornersAndDropsStraightRuns() {
        val l = (0..50).map { it / 100f to 0.2f } + (1..50).map { 0.5f to 0.2f + it / 100f }
        val s = Sketch.simplify(l)
        assertEquals(listOf(0f to 0.2f, 0.5f to 0.2f, 0.5f to 0.7f), s)
        // Wiggles above the tolerance survive.
        val zig = (0..20).map { i -> i / 20f to if (i % 2 == 0) 0f else 0.01f }
        assertEquals(zig, Sketch.simplify(zig))
        assertEquals(listOf(0f to 0f), Sketch.simplify(listOf(0f to 0f)))
    }

    @Test fun sketchFlagsRoundTrip() {
        val sha = "a".repeat(64)
        val info = PhotoInfo(takenAt = java.time.Instant.parse("2026-11-24T08:00:00Z"), sketch = true, drawnOn = sha)
        val back = PhotoInfo.fromJson(info.toJson())
        assertEquals(info, back)
        assertTrue(back.stamp(null, null, java.time.ZoneOffset.UTC).first().contains("素描"))
        assertEquals(false, PhotoInfo.fromJson(PhotoInfo(azimuth = 10.0).toJson()).sketch)
    }
}
