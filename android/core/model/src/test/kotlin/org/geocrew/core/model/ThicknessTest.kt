package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ThicknessTest {
    private fun rad(d: Double) = d * Math.PI / 180

    @Test
    fun flatGroundAcrossStrike() {
        val r = ThicknessResult.of(Attitude.of(90.0, 30.0), listOf(TraverseLeg(90.0, 0.0, 100.0)))
        assertEquals(50.0, r.trueThickness, 1e-9)
        assertEquals(50.0 / cos(rad(30.0)), r.verticalThickness!!, 1e-9)
        assertEquals(100.0, r.horizontalWidth!!, 1e-9)
        assertTrue(r.towardsDip)
    }

    @Test
    fun slopeWithAndAgainstTheDipMatchTextbook() {
        // Slope dipping the same way as the bed, gentler than it: h = L·sin(α − β).
        val same = ThicknessResult.of(Attitude.of(90.0, 40.0), listOf(TraverseLeg(90.0, -15.0, 80.0)))
        assertEquals(80 * sin(rad(25.0)), same.trueThickness, 1e-9)
        // Ground rising towards the dip side (walking up-dip goes downhill): h = L·sin(α + β).
        val against = ThicknessResult.of(Attitude.of(90.0, 40.0), listOf(TraverseLeg(270.0, -15.0, 80.0)))
        assertEquals(80 * sin(rad(55.0)), against.trueThickness, 1e-9)
        assertFalse(against.towardsDip)
    }

    @Test
    fun obliqueTraverseUsesTheAngleToStrike() {
        // Flat ground, traverse 60° off the dip direction: h = L·sinα·cos60°.
        val r = ThicknessResult.of(Attitude.of(0.0, 50.0), listOf(TraverseLeg(60.0, 0.0, 120.0)))
        assertEquals(120 * sin(rad(50.0)) * cos(rad(60.0)), r.trueThickness, 1e-9)
    }

    @Test
    fun legsAddUpAndBacktrackingIsFlagged() {
        val att = Attitude.of(180.0, 20.0)
        val one = ThicknessResult.of(att, listOf(TraverseLeg(180.0, 0.0, 100.0)))
        val split = ThicknessResult.of(att, listOf(TraverseLeg(180.0, 0.0, 130.0), TraverseLeg(0.0, 0.0, 30.0)))
        assertEquals(one.trueThickness, split.trueThickness, 1e-9)
        assertTrue(split.mixedDirections)
        assertFalse(one.mixedDirections)
    }

    @Test
    fun verticalAndHorizontalBeds() {
        val vertical = ThicknessResult.of(Attitude.of(90.0, 90.0), listOf(TraverseLeg(90.0, 0.0, 10.0)))
        assertEquals(10.0, vertical.trueThickness, 1e-9)
        assertNull(vertical.verticalThickness)
        val flat = ThicknessResult.of(Attitude.of(0.0, 0.0), listOf(TraverseLeg(0.0, 30.0, 10.0)))
        assertEquals(5.0, flat.trueThickness, 1e-9)
        assertNull(flat.horizontalWidth)
    }

    @Test
    fun legBetweenTwoPointsUsesElevation() {
        val a = LngLat(110.0, 30.0, 500.0)
        val b = Geodesy.destination(LngLat(110.0, 30.0), 90.0, 100.0).let { LngLat(it.lng, it.lat, 450.0) }
        val leg = TraverseLeg.between(a, b)
        assertEquals(90.0, leg.azimuth, 0.01)
        assertEquals(Math.toDegrees(Math.atan2(-50.0, 100.0)), leg.slopeAngle, 0.01)
        assertEquals(Math.hypot(100.0, 50.0), leg.slopeDistance, 0.05)
        val flat = TraverseLeg.between(LngLat(110.0, 30.0), Geodesy.destination(LngLat(110.0, 30.0), 0.0, 10.0))
        assertEquals(0.0, flat.slopeAngle, 1e-9)
    }
}
