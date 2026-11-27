package org.geocrew.android

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/** A plane's attitude: the direction it dips toward, in degrees clockwise from north (0 to 360), and how steeply (0 to 90). */
data class Attitude(val dipDirection: Double, val dip: Double) {
    /** The strike by the right-hand rule: 90 degrees anticlockwise from the dip direction. */
    val strike: Double get() = (dipDirection - 90 + 360) % 360
}

/**
 * The attitude of the plane whose normal is ([east], [north], [up]): the
 * phone's own z axis in the world, while its back lies on the plane.
 * [declination] turns magnetic directions into true ones. A plane is the
 * same whichever side the phone is on, so the normal that points up is used.
 * Null for a zero vector.
 */
fun attitudeOf(east: Double, north: Double, up: Double, declination: Double = 0.0): Attitude? {
    val length = sqrt(east * east + north * north + up * up)
    if (length == 0.0 || length.isNaN()) return null
    val sign = if (up < 0) -1.0 else 1.0
    val e = sign * east / length
    val n = sign * north / length
    val u = sign * up / length
    val dip = Math.toDegrees(acos(u.coerceIn(-1.0, 1.0)))
    val direction = ((Math.toDegrees(atan2(e, n)) + declination) % 360 + 360) % 360
    return Attitude(direction, dip)
}

/**
 * The phone's normal over the last [millis] milliseconds: a measurement takes
 * their mean, steadier than any one reading. Each reading is turned to the
 * side of the one before, so a plane near vertical, whose upward normal
 * flips from side to side, still averages right.
 */
class NormalWindow(private val millis: Long = 800) {
    private val samples = ArrayDeque<Pair<Long, DoubleArray>>()

    fun add(time: Long, east: Double, north: Double, up: Double) {
        val length = sqrt(east * east + north * north + up * up)
        if (length == 0.0 || length.isNaN()) return
        var v = doubleArrayOf(east / length, north / length, up / length)
        samples.lastOrNull()?.second?.let { last -> if (dot(v, last) < 0) v = doubleArrayOf(-v[0], -v[1], -v[2]) }
        samples.addLast(time to v)
        while (samples.isNotEmpty() && samples.first().first < time - millis) samples.removeFirst()
    }

    /** The mean normal, or null before any reading. */
    fun mean(): DoubleArray? {
        if (samples.isEmpty()) return null
        val sum = DoubleArray(3)
        for ((_, v) in samples) for (i in 0..2) sum[i] += v[i]
        return sum
    }

    /** Whether there were [least] readings or more and all kept within [degrees] of their mean. */
    fun steady(degrees: Double = 1.0, least: Int = 5): Boolean {
        val m = mean() ?: return false
        if (samples.size < least) return false
        val length = sqrt(dot(m, m))
        if (length == 0.0) return false
        val limit = kotlin.math.cos(Math.toRadians(degrees))
        return samples.all { (_, v) -> dot(v, m) / length >= limit }
    }

    fun clear() = samples.clear()

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

/** The fields an attitude goes into: number fields called dip direction and dip, and strike when the layer has one. */
data class AttitudeFields(val dipDirection: FormField, val dip: FormField, val strike: FormField?)

/** The layer's attitude fields, found by their names in Chinese or English; null without both dip direction and dip. */
fun attitudeFields(fields: List<FormField>): AttitudeFields? {
    fun find(names: Set<String>) = fields.firstOrNull { it.type in NUMBER_TYPES && it.name.trim().lowercase() in names }
    val direction = find(setOf("倾向", "dip direction", "dipdirection", "dip_direction", "dipdir", "dip dir")) ?: return null
    val dip = find(setOf("倾角", "dip", "dip angle", "dip_angle", "dipangle")) ?: return null
    return AttitudeFields(direction, dip, find(setOf("走向", "strike")))
}

/** Whole degrees, as a compass is read: 0 to 359 for a direction (359.6 is 0). */
fun wholeDirection(degrees: Double): Int = Math.round(degrees).toInt() % 360

/** Whole degrees of dip, 0 to 90. */
fun wholeDip(degrees: Double): Int = Math.round(degrees).toInt().coerceIn(0, 90)
