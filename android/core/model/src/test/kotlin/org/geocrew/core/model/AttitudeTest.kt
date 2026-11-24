package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AttitudeTest {
    private fun rad(d: Double) = Math.toRadians(d)

    /** Upward normal of a plane with the given dip direction and dip. */
    private fun normal(dd: Double, dip: Double) =
        Triple(sin(rad(dip)) * sin(rad(dd)), sin(rad(dip)) * cos(rad(dd)), cos(rad(dip)))

    @Test
    fun fromDeviceNormalRecoversPlane() {
        for ((dd, dip) in listOf(125.0 to 42.0, 10.0 to 5.0, 270.0 to 80.0, 359.0 to 30.0)) {
            val (e, n, u) = normal(dd, dip)
            val a = Attitude.fromDeviceNormal(e, n, u)
            assertEquals(dip, a.dip, 1e-6)
            assertEquals(dd, a.dipDirection, 1e-6)
            // Phone face down on the same plane gives the same answer.
            val b = Attitude.fromDeviceNormal(-e, -n, -u)
            assertEquals(a.dipDirection, b.dipDirection, 1e-6)
        }
    }

    @Test
    fun declinationShiftsDipDirection() {
        val (e, n, u) = normal(100.0, 20.0)
        assertEquals(94.0, Attitude.fromDeviceNormal(e, n, u, declination = -6.0).dipDirection, 1e-6)
    }

    @Test
    fun strikeFollowsRightHandRule() {
        val a = Attitude.of(125.0, 42.0)
        assertEquals(35.0, a.strike, 1e-9)
        assertEquals("125°∠42°", a.format())
        assertEquals(a, Attitude.fromStrike(35.0, 42.0))
    }

    @Test
    fun propertiesRoundTrip() {
        val p = buildProperties { Attitude.of(125.0, 42.0).writeTo(this) }
        assertEquals(Attitude.of(125.0, 42.0), Attitude.fromProperties(p))
        assertNull(Attitude.fromProperties(buildProperties { this[Props.TITLE] = "x" }))
    }
}
