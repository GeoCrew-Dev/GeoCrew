package org.geocrew.storage

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.nativelib.GeoCrewNative
import org.junit.Before

// Files are read in the probe's own process (ADR 0003): what it makes of them, and of a broken one.
class ProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "probe-test")

    @Before
    fun setUp() {
        NativeData.setup(context)
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @Test
    fun aRasterIsSummedUp() = runBlocking {
        val tif = File(dir, "影像.tif")
        GeoCrewNative.makeTestRaster(tif.path)
        val read = Probe.inspect(context, tif.path)
        assertEquals("raster", read.getValue("kind").jsonPrimitive.content)
        assertEquals("GTiff", read.getValue("driver").jsonPrimitive.content)
        assertEquals(4000L, read.getValue("width").jsonPrimitive.content.toLong())
        assertEquals(3, read.getValue("bands").jsonArray.size)
        assertEquals("Byte", read.getValue("bands").jsonArray[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(read.getValue("crs").toString().contains("4547"), "the CRS: ${read["crs"]}")
        assertEquals(10.0, read.getValue("pixel").jsonArray[0].jsonPrimitive.content.toDouble())
        val (west, south, east, north) = read.getValue("extent").jsonArray.map { it.jsonPrimitive.content.toDouble() }
        assertTrue(west in 113.7..113.9 && east in 114.1..114.3 && south in 29.7..29.9 && north in 30.1..30.3, "around 114E 30N: ${read["extent"]}")
    }

    @Test
    fun vectorDataIsSummedUp() = runBlocking {
        val json = File(dir, "路线.geojson").apply {
            writeText("""{"type": "FeatureCollection", "features": [{"type": "Feature", "geometry": {"type": "Point", "coordinates": [114, 30]}, "properties": {"name": "a"}}]}""")
        }
        val read = Probe.inspect(context, json.path)
        assertEquals("vector", read.getValue("kind").jsonPrimitive.content)
        val layer = read.getValue("layers").jsonArray.single().jsonObject
        assertEquals("Point", layer.getValue("geometry").jsonPrimitive.content)
        assertEquals(1L, layer.getValue("features").jsonPrimitive.content.toLong())
    }

    @Test
    fun aBrokenFileFailsWithoutStoppingTheApp() = runBlocking {
        val broken = File(dir, "坏的.tif").apply { writeText("not a raster") }
        assertFailsWith<ProbeFailed> { Probe.inspect(context, broken.path) }
        Unit
    }
}
