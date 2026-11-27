package org.geocrew.android

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClinometerTest {
    // The upward normal of a plane dipping [dip] degrees toward [direction].
    private fun normal(direction: Double, dip: Double): DoubleArray {
        val d = Math.toRadians(direction)
        val i = Math.toRadians(dip)
        return doubleArrayOf(sin(i) * sin(d), sin(i) * cos(d), cos(i))
    }

    private fun attitude(n: DoubleArray, declination: Double = 0.0) = assertNotNull(attitudeOf(n[0], n[1], n[2], declination))

    @Test
    fun aPlaneDipsTowardWhereItsNormalLeans() {
        for ((direction, dip) in listOf(90.0 to 30.0, 225.0 to 60.0, 10.0 to 85.0, 300.0 to 5.0)) {
            val a = attitude(normal(direction, dip))
            assertEquals(direction, a.dipDirection, 1e-9)
            assertEquals(dip, a.dip, 1e-9)
        }
        assertEquals(0.0, attitude(doubleArrayOf(0.0, 0.0, 2.0)).dip, 1e-9, "flat")
        assertNull(attitudeOf(0.0, 0.0, 0.0))
    }

    @Test
    fun eitherSideOfThePlaneGivesTheSameAttitude() {
        val up = normal(135.0, 40.0)
        val down = attitude(doubleArrayOf(-up[0], -up[1], -up[2]))
        assertEquals(135.0, down.dipDirection, 1e-9, "the phone held under an overhang")
        assertEquals(40.0, down.dip, 1e-9)
    }

    @Test
    fun theDeclinationTurnsMagneticToTrueAndTheStrikeFollows() {
        val a = attitude(normal(2.0, 30.0), declination = -5.0)
        assertEquals(357.0, a.dipDirection, 1e-9)
        assertEquals(267.0, a.strike, 1e-9)
        assertEquals(0, wholeDirection(359.6), "never 360")
        assertEquals(90, wholeDip(90.4))
    }

    @Test
    fun aSteadyHandAveragesEvenOnAVerticalPlane() {
        val window = NormalWindow(millis = 1000)
        // A vertical plane dipping east: the readings lean a hair either side, their up component changing sign.
        for (t in 0L until 20L) {
            val tilt = if (t % 2 == 0L) 0.3 else -0.3
            val n = normal(90.0, 90.0 + tilt)
            window.add(t * 50, n[0], n[1], n[2])
        }
        assertTrue(window.steady())
        val mean = window.mean()!!
        val a = attitude(mean)
        assertEquals(90.0, a.dip, 0.01)
        assertEquals(90.0, a.dipDirection, 0.01)

        window.clear()
        listOf(10.0, 30.0, 10.0, 30.0, 10.0).forEachIndexed { k, dip -> normal(0.0, dip).let { window.add(k * 50L, it[0], it[1], it[2]) } }
        assertFalse(window.steady(), "a shaking hand")
        // Readings older than the window drop out.
        for (k in 0 until 5) normal(0.0, 20.0).let { window.add(2000L + k * 50, it[0], it[1], it[2]) }
        assertTrue(window.steady())
    }

    @Test
    fun theFieldsAreFoundByTheirNames() {
        val fields = listOf(FormField("点名", "string"), FormField("倾向", "integer"), FormField("倾角", "real"), FormField("走向", "integer"))
        val found = assertNotNull(attitudeFields(fields))
        assertEquals(listOf("倾向", "倾角", "走向"), listOf(found.dipDirection.name, found.dip.name, found.strike?.name))
        assertNotNull(attitudeFields(listOf(FormField("Dip direction", "real"), FormField("Dip", "real"))), "English, any case")
        assertNull(attitudeFields(listOf(FormField("倾向", "string"), FormField("倾角", "real"))), "numbers only")
        assertNull(attitudeFields(listOf(FormField("倾角", "real"))), "both needed")
    }
}
