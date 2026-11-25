package org.geocrew.core.gis

import org.geocrew.core.model.Attitude
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Eigen analysis of the orientation tensor of axial data (Scheidegger 1965,
 * Woodcock 1977): how a set of poles is shaped. For bedding poles around a
 * cylindrical fold they spread along a great circle (the π circle); its pole,
 * the least eigenvector, is the fold axis.
 */
data class Fabric(
    val count: Int,
    /** Normalised eigenvalues, largest first; they sum to 1. */
    val s1: Double,
    val s2: Double,
    val s3: Double,
    /** Eigenvectors in the same order, in the lower hemisphere. */
    val v1: Lineation,
    val v2: Lineation,
    val v3: Lineation,
) {
    enum class Shape(val label: String) {
        /** Poles bunch around one direction: planes roughly parallel. */
        CLUSTER("集中分布"),
        /** Poles spread along a great circle: planes folded about one axis. */
        GIRDLE("环带分布"),
        /** No clear preferred orientation. */
        WEAK("分散，没有明显优选方位"),
    }

    /** Woodcock's strength C = ln(S1/S3); below about 2 the fabric is weak. */
    val strength: Double get() = if (s3 > 0) ln(s1 / s3) else Double.POSITIVE_INFINITY

    /** Woodcock's shape K = ln(S1/S2) / ln(S2/S3): above 1 clusters, below 1 girdles. */
    val k: Double get() {
        val b = if (s3 > 0) ln(s2 / s3) else Double.POSITIVE_INFINITY
        return if (b == 0.0) Double.POSITIVE_INFINITY else ln(s1 / s2) / b
    }

    val shape: Shape get() = when {
        strength < WEAK_STRENGTH -> Shape.WEAK
        k < 1 -> Shape.GIRDLE
        else -> Shape.CLUSTER
    }

    /** The fold axis (β/π axis) for bedding poles: the pole of the best-fit great circle. */
    val foldAxis: Lineation get() = v3

    /** The best-fit great circle through the poles, the π circle. */
    val girdle: Attitude get() = Stereonet.planeOfPole(v3)

    companion object {
        /** Below this C the eigenvectors mean little. */
        const val WEAK_STRENGTH = 2.0
    }
}

/** Orientation tensor of [lines]; null for fewer than 3. */
fun Stereonet.fabric(lines: List<Lineation>): Fabric? {
    if (lines.size < 3) return null
    val t = Array(3) { DoubleArray(3) }
    lines.forEach { l ->
        val v = l.vector()
        for (i in 0..2) for (j in 0..2) t[i][j] += v[i] * v[j]
    }
    for (i in 0..2) for (j in 0..2) t[i][j] /= lines.size
    val (values, vectors) = symmetricEigen(t)
    val order = values.indices.sortedByDescending { values[it] }
    fun line(i: Int) = vectors[order[i]].let { Lineation.of(it[0], it[1], it[2]) }
    val s = order.map { values[it].coerceAtLeast(0.0) }
    val sum = s.sum().takeIf { it > 0 } ?: 1.0
    return Fabric(lines.size, s[0] / sum, s[1] / sum, s[2] / sum, line(0), line(1), line(2))
}

/** Eigenvalues and unit eigenvectors of a symmetric 3×3 matrix, by cyclic Jacobi rotations. */
internal fun symmetricEigen(m: Array<DoubleArray>): Pair<DoubleArray, List<DoubleArray>> {
    val a = Array(3) { m[it].copyOf() }
    val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
    repeat(50) {
        val off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2]
        if (off < 1e-22) return@repeat
        for ((p, q) in listOf(0 to 1, 0 to 2, 1 to 2)) {
            if (abs(a[p][q]) < 1e-300) continue
            val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
            val tt = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val c = 1 / sqrt(tt * tt + 1)
            val s = tt * c
            for (k in 0..2) {
                val akp = a[k][p]
                val akq = a[k][q]
                a[k][p] = c * akp - s * akq
                a[k][q] = s * akp + c * akq
            }
            for (k in 0..2) {
                val apk = a[p][k]
                val aqk = a[q][k]
                a[p][k] = c * apk - s * aqk
                a[q][k] = s * apk + c * aqk
            }
            for (k in 0..2) {
                val vkp = v[k][p]
                val vkq = v[k][q]
                v[k][p] = c * vkp - s * vkq
                v[k][q] = s * vkp + c * vkq
            }
        }
    }
    return DoubleArray(3) { a[it][it] } to List(3) { j -> DoubleArray(3) { i -> v[i][j] } }
}
