package org.geocrew.android

import kotlin.test.Test
import kotlin.test.assertEquals

class ReceivingTest {
    @Test
    fun aTakenNameIsNumberedBeforeItsExtension() {
        assertEquals("roads.shp", numbered("roads.shp", 1))
        assertEquals("roads (2).shp", numbered("roads.shp", 2))
        assertEquals("README (3)", numbered("README", 3))
        assertEquals("测区.tar (2).gz", numbered("测区.tar.gz", 2), "only the last extension")
    }

    @Test
    fun namesAreMadeSafe() {
        assertEquals("roads.shp", safeName("/storage/emulated/0/Download/roads.shp"))
        assertEquals("a_b.kml", safeName("a:b.kml"))
        assertEquals("hidden.gpx", safeName(".hidden.gpx"), "not hidden")
        assertEquals("file", safeName(null))
        assertEquals("file", safeName("  "))
        assertEquals("测区一号.gpkg", safeName("测区一号.gpkg"))
    }
}
