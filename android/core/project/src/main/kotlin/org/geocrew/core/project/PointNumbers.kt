package org.geocrew.core.project

/** Numbers for field points, D001, D002 and so on (the field-point flow, ADR 0004). */
object PointNumbers {
    /**
     * The number after the highest of [existing] that is [prefix] and digits,
     * as wide as the widest of them and at least three digits.
     */
    fun next(existing: Iterable<String?>, prefix: String = "D"): String {
        val pattern = Regex(Regex.escape(prefix) + "(\\d+)")
        var highest = 0L
        var width = 3
        for (number in existing) {
            val digits = pattern.matchEntire(number ?: continue)?.groupValues?.get(1) ?: continue
            val value = digits.toLongOrNull() ?: continue
            highest = maxOf(highest, value)
            width = maxOf(width, digits.length)
        }
        return prefix + (highest + 1).toString().padStart(width, '0')
    }
}
