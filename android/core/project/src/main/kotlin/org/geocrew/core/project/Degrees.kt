package org.geocrew.core.project

import kotlin.math.abs

/**
 * Longitudes and latitudes as people write them in the field: decimal degrees
 * (114.30514), degrees and minutes (114°18.308′), or degrees, minutes and
 * seconds (114°18′18.5″, or 114 18 18.5), with or without a hemisphere (E, W,
 * N, S, or 东, 西, 北, 南) before or after. West and south are negative.
 */
object Degrees {
    private val HEMISPHERES = mapOf('E' to 1, 'N' to 1, 'W' to -1, 'S' to -1, '东' to 1, '北' to 1, '西' to -1, '南' to -1)

    // Marks people put after degrees, minutes and seconds; all are separators here.
    private val MARKS = Regex("""[°º˚度′'’分″"”秒:\s]+""")

    /** Which coordinate a text is: a hemisphere letter of the other one is refused. */
    enum class Axis { LONGITUDE, LATITUDE }

    private val LETTERS = mapOf(Axis.LONGITUDE to "EW东西", Axis.LATITUDE to "NS北南")

    /**
     * The degrees [text] says, or null when it says none; with [axis], hemisphere letters must fit it.
     * Full-width digits and marks are read too (Numbers.plain), as a Chinese keyboard types them.
     */
    fun parse(text: String, axis: Axis? = null): Double? {
        val plain = Numbers.plain(text).uppercase()
        if (axis != null) {
            val wrong = LETTERS.getValue(if (axis == Axis.LONGITUDE) Axis.LATITUDE else Axis.LONGITUDE)
            if (plain.any { it in wrong }) return null
        }
        var t = plain.replace("''", "\"")
        if (t.isEmpty()) return null
        var sign = 1
        HEMISPHERES[t.first()]?.let { sign = it; t = t.drop(1) }
        HEMISPHERES[t.lastOrNull() ?: ' ']?.let { sign *= it; t = t.dropLast(1) }
        t = t.trim()
        if (t.startsWith("-")) {
            sign = -sign
            t = t.drop(1)
        } else if (t.startsWith("+")) {
            t = t.drop(1)
        }
        val parts = t.split(MARKS).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.toDoubleOrNull() ?: return null }
        if (numbers.any { it < 0 || !it.isFinite() }) return null
        // Only the last part may have decimals, and minutes and seconds stay under 60.
        if (numbers.dropLast(1).any { it % 1.0 != 0.0 }) return null
        if (numbers.drop(1).any { it >= 60 }) return null
        val degrees = numbers[0] + numbers.getOrElse(1) { 0.0 } / 60 + numbers.getOrElse(2) { 0.0 } / 3600
        return sign * degrees
    }

    /** Whether [text] is written as more than plain decimal degrees: it then deserves its decimal value shown. */
    fun isSexagesimal(text: String): Boolean = parse(text) != null && Numbers.double(text) == null

    /** [degrees] as degrees, minutes and seconds to a tenth of a second, such as 114°18′18.5″. */
    fun dms(degrees: Double): String {
        val tenths = Math.round(abs(degrees) * 36_000)
        val d = tenths / 36_000
        val m = tenths % 36_000 / 600
        val s = tenths % 600 / 10.0
        return (if (degrees < 0) "-" else "") + "$d°$m′" + String.format(java.util.Locale.ROOT, "%.1f", s) + "″"
    }
}
