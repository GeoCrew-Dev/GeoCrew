package org.geocrew.core.gis

import org.geocrew.core.model.Attitude
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

class FabricTest {
    private fun angle(a: Lineation, b: Lineation): Double {
        val x = a.vector()
        val y = b.vector()
        val d = abs(x[0] * y[0] + x[1] * y[1] + x[2] * y[2]).coerceAtMost(1.0)
        return acos(d) * 180 / PI
    }

    @Test fun findsTheAxisOfACylindricalFold() {
        val axis = Lineation(30.0, 20.0)
        // Two unit vectors normal to the axis span the plane the bedding poles lie in.
        val a = axis.vector()
        val u = Lineation(120.0, 0.0).vector() // horizontal and normal to a 030° trend
        val w = doubleArrayOf(a[1] * u[2] - a[2] * u[1], a[2] * u[0] - a[0] * u[2], a[0] * u[1] - a[1] * u[0])
        val poles = (-60..60 step 8).map { d ->
            val t = d * PI / 180 + PI / 2
            // Fold limbs: poles from about 30° to 150° around the girdle, with a little noise.
            val n = 0.02 * sin(d * 1.7)
            Lineation.of(cos(t) * u[0] + sin(t) * w[0] + n * a[0], cos(t) * u[1] + sin(t) * w[1] + n * a[1], cos(t) * u[2] + sin(t) * w[2] + n * a[2])
        }
        val f = Stereonet.fabric(poles)!!
        assertEquals(Fabric.Shape.GIRDLE, f.shape)
        assertEquals(0.0, angle(f.foldAxis, axis), 1.5)
        assertEquals(1.0, f.s1 + f.s2 + f.s3, 1e-9)
        // The π circle is the plane normal to the axis.
        assertEquals(0.0, angle(Stereonet.pole(f.girdle), axis), 1.5)
    }

    @Test fun parallelBedsCluster() {
        val poles = listOf(Attitude.of(120.0, 30.0), Attitude.of(125.0, 32.0), Attitude.of(118.0, 28.0), Attitude.of(122.0, 35.0)).map(Stereonet::pole)
        val f = Stereonet.fabric(poles)!!
        assertEquals(Fabric.Shape.CLUSTER, f.shape)
        assertEquals(0.0, angle(f.v1, Stereonet.pole(Attitude.of(121.0, 31.0))), 3.0)
        assertNull(Stereonet.fabric(poles.take(2)))
    }

    @Test fun eigenOfADiagonalMatrix() {
        val (values, vectors) = symmetricEigen(arrayOf(doubleArrayOf(2.0, 0.0, 0.0), doubleArrayOf(0.0, 5.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)))
        assertEquals(listOf(2.0, 5.0, 1.0), values.toList())
        assertEquals(1.0, abs(vectors[1][1]), 1e-12)
    }
}
