package org.geocrew.core.model

import kotlinx.serialization.Serializable

/**
 * How new records are named in a project: a prefix per kind followed by a
 * running number, e.g. D001, D002 for geological points. The next number
 * follows the largest one already used with that prefix, so deleting a
 * record never makes two records share a name. In a team each member can
 * use their own prefix (D-王001) so offline work never collides.
 */
@Serializable
data class Numbering(
    val geoPoint: String = "D",
    val point: String = "P",
    val line: String = "线",
    val polygon: String = "面",
    /** Digits of the number, padded with zeros; 0 means no padding. */
    val width: Int = 3,
) {
    fun prefixFor(kind: FeatureKind): String? = when (kind) {
        FeatureKind.GEO_POINT -> geoPoint
        FeatureKind.POINT -> point
        FeatureKind.LINE -> line
        FeatureKind.POLYGON -> polygon
        else -> null
    }

    /** Name for the next record of [kind], given the titles already in the project. */
    fun next(kind: FeatureKind, titles: Iterable<String?>): String? {
        val prefix = prefixFor(kind) ?: return null
        return next(prefix, titles)
    }

    fun next(prefix: String, titles: Iterable<String?>): String {
        val pattern = Regex("^" + Regex.escape(prefix) + "(\\d{1,9})$")
        val max = titles.maxOfOrNull { t -> t?.let { pattern.matchEntire(it.trim()) }?.groupValues?.get(1)?.toLong() ?: 0L } ?: 0L
        val w = width.coerceIn(0, 9)
        return prefix + (max + 1).toString().padStart(w, '0')
    }

    companion object {
        /** Why [n] cannot be used, or null when it can. */
        fun problem(n: Numbering): String? {
            val all = listOf(n.geoPoint, n.point, n.line, n.polygon)
            return when {
                all.any { it.length > 12 } -> "前缀最多 12 个字"
                all.any { it.isNotEmpty() && it.last().isDigit() } -> "前缀不能以数字结尾，否则分不清编号从哪开始"
                n.width !in 0..9 -> "编号位数在 0 到 9 之间"
                else -> null
            }
        }
    }
}
