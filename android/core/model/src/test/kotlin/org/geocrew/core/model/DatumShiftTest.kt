package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class DatumShiftTest {
    private val bursa = DatumShift.Bursa(dx = -14.2, dy = 132.6, dz = 61.9, rx = 1.2, ry = -2.4, rz = 3.1, ppm = 4.5)
    private val grid = LocalGrid("北京 54 第 39 带", Ellipsoid.BEIJING54, 3, 39, bursa)
    private val area = listOf(
        LngLat(116.95, 40.02), LngLat(117.31, 40.05), LngLat(117.02, 40.31), LngLat(117.35, 40.28), LngLat(117.15, 40.17),
    )

    @Test
    fun ecefRoundTrip() {
        val g = Geodetic(LngLat(117.123456, 40.654321), 1234.5)
        for (e in Ellipsoid.entries) {
            val back = e.toGeodetic(e.toEcef(g))
            assertEquals(g.position.lng, back.position.lng, 1e-9)
            assertEquals(g.position.lat, back.position.lat, 1e-9)
            assertEquals(g.height, back.height, 1e-4)
        }
    }

    @Test
    fun bursaUndoes() {
        val c = Ellipsoid.CGCS2000.toEcef(Geodetic(LngLat(117.0, 40.0), 50.0))
        val back = bursa.inverse(bursa.forward(c))
        assertEquals(0.0, hypot(hypot(back.x - c.x, back.y - c.y), back.z - c.z), 1e-6)
    }

    @Test
    fun gridRoundTripsWithEachShift() {
        val plane = DatumShift.Plane(dx = -42.3, dy = 71.8, rotationSeconds = 12.5, ppm = -8.0)
        for (shift in listOf(null, bursa, plane)) {
            val g = LocalGrid("t", Ellipsoid.XIAN80, 3, 39, shift)
            val p = Geodetic(LngLat(117.2, 40.1), 0.0)
            val (x, y) = g.toGrid(p)
            val back = g.fromGrid(x, y + 39_000_000)
            // Heights are not carried through the plane, which costs the seven-parameter way a few millimetres.
            assertEquals(0.0, Geodesy.distance(p.position, back), 0.01)
        }
    }

    @Test
    fun noShiftIsFarOffForBeijing54() {
        val p = Geodetic(LngLat(117.2, 40.1))
        val shifted = grid.toGrid(p)
        val plain = grid.copy(shift = null).toGrid(p)
        assertTrue(hypot(shifted.first - plain.first, shifted.second - plain.second) > 20)
    }

    @Test
    fun sevenParametersFromCommonPoints() {
        val pts = area.mapIndexed { i, ll -> grid.toGrid(Geodetic(ll, 100.0 + 20 * i)).let { (x, y) -> CommonPoint("P$i", Geodetic(ll, 100.0 + 20 * i), x, y + 39_000_000, 100.0 + 20 * i) } }
        val fit = assertNotNullAnd(LocalGrid.fitBursa(pts, Ellipsoid.BEIJING54, 3, 39))
        assertTrue("rms ${fit.rms}", fit.rms < 0.01)
        val fitted = grid.copy(shift = fit.shift)
        val probe = Geodetic(LngLat(117.1, 40.2), 150.0)
        val (x0, y0) = grid.toGrid(probe)
        val (x1, y1) = fitted.toGrid(probe)
        assertEquals(0.0, hypot(x1 - x0, y1 - y0), 0.02)
        assertNull(LocalGrid.fitBursa(pts.take(2), Ellipsoid.BEIJING54, 3, 39))
    }

    @Test
    fun fourParametersFromCommonPoints() {
        val plane = DatumShift.Plane(dx = -42.3, dy = 71.8, rotationSeconds = 12.5, ppm = -8.0)
        val g = LocalGrid("t", Ellipsoid.BEIJING54, 3, 39, plane)
        val pts = area.take(3).mapIndexed { i, ll -> g.toGrid(Geodetic(ll)).let { (x, y) -> CommonPoint("P$i", Geodetic(ll), x, y) } }
        val fit = assertNotNullAnd(LocalGrid.fitPlane(pts, 3, 39))
        assertEquals(plane.dx, fit.shift.dx, 1e-3)
        assertEquals(plane.dy, fit.shift.dy, 1e-3)
        assertEquals(plane.rotationSeconds, fit.shift.rotationSeconds, 1e-4)
        assertEquals(plane.ppm, fit.shift.ppm, 1e-4)
        assertTrue(fit.worst < 1e-3)
        // A Bursa shift seen on the plane is close to a similarity over a small area.
        val real = area.map { ll -> grid.toGrid(Geodetic(ll)).let { (x, y) -> CommonPoint("Q", Geodetic(ll), x, y) } }
        val approx = assertNotNullAnd(LocalGrid.fitPlane(real, 3, 39))
        assertTrue("rms ${approx.rms}", approx.rms < 0.5)
        assertNull(LocalGrid.fitPlane(real.take(1), 3, 39))
    }

    @Test
    fun storedWithTheProject() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        for (g in listOf(grid, grid.copy(shift = DatumShift.Plane(1.0, 2.0, 3.0, 4.0)), grid.copy(shift = null))) {
            val text = json.encodeToString(ProjectConfig.serializer(), ProjectConfig(localGrid = g))
            assertEquals(g, json.decodeFromString(ProjectConfig.serializer(), text).localGrid)
        }
    }

    @Test
    fun commonPointLines() {
        val (pts, problems) = CommonPoint.parse(
            """
            # 名称 经度 纬度 X Y 高程
            G01, 117.1, 40.1, 4440123.45, 39508765.43, 152.3
            G02	117.2	40.2	4451234.5	517654.3
            坏点, 117.3
            G04, 40.2, 117.2, 1, 2
            G05, 117.2, 40.2, 517654.3, 4451234.5
            """.trimIndent(),
        )
        assertEquals(listOf("G01", "G02"), pts.map { it.name })
        assertEquals(152.3, pts[0].h!!, 1e-9)
        assertNull(pts[1].h)
        assertEquals(3, problems.size)
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
