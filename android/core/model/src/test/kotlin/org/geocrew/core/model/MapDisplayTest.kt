package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

class MapDisplayTest {
    private fun f(id: String, kind: FeatureKind, task: TaskInfo? = null) = GeoFeature(
        id, "p", kind, Geometry.Point(LngLat(1.0, 2.0)),
        buildProperties { task?.writeTo(this) }, Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH),
    )

    private val list = listOf(
        f("a", FeatureKind.POINT),
        f("b", FeatureKind.TRACK),
        f("c", FeatureKind.POINT, TaskInfo("me", "我")),
        f("d", FeatureKind.LINE, TaskInfo("me", "我", TaskStatus.DONE)),
        f("e", FeatureKind.LINE, TaskInfo("other", "他")),
    )

    @Test
    fun defaultShowsEverythingUnchanged() {
        assertFalse(MapDisplay().filtering)
        assertSame(list, MapDisplay().filter(list, "me"))
    }

    @Test
    fun hidesKindsButKeepsTheSelection() {
        val d = MapDisplay().toggle(FeatureKind.TRACK).toggle(FeatureKind.POINT)
        assertEquals(listOf("d", "e"), d.filter(list, "me").map { it.id })
        assertEquals(listOf("a", "d", "e"), d.filter(list, "me", keep = "a").map { it.id })
        assertEquals(MapDisplay(hiddenKinds = listOf("track")), d.toggle(FeatureKind.POINT))
    }

    @Test
    fun onlyMyOpenTasks() {
        val d = MapDisplay(onlyMyTasks = true)
        assertEquals(listOf("c"), d.filter(list, "me").map { it.id })
        assertEquals(emptyList<String>(), d.filter(list, null).map { it.id })
    }

    @Test
    fun anchors() {
        assertNull(MapDisplay.anchor(null))
        val line = Geometry.LineString(listOf(LngLat(0.0, 0.0), LngLat(1.0, 1.0), LngLat(2.0, 2.0)))
        assertEquals(LngLat(1.0, 1.0), MapDisplay.anchor(line))
        val square = Geometry.Polygon(listOf(listOf(LngLat(0.0, 0.0), LngLat(2.0, 0.0), LngLat(2.0, 2.0), LngLat(0.0, 2.0), LngLat(0.0, 0.0))))
        assertEquals(LngLat(1.0, 1.0), MapDisplay.anchor(square))
    }
}
