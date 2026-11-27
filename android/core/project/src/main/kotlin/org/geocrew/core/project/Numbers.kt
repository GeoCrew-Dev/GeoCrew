package org.geocrew.core.project

/**
 * Numbers as people type them on a phone. A Chinese keyboard gives full-width
 * digits and marks (１２３．５), an ideographic full stop for the decimal point
 * (114。3) and full-width signs (－5), none of which Kotlin reads as a number.
 */
object Numbers {
    /**
     * [text] trimmed, with full-width digits, letters, signs and marks made
     * plain, and the ideographic and full-width full stops and the full-width
     * comma read as the decimal point. A plain comma stays as it is.
     */
    fun plain(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    '。', '｡', '．', '，' -> '.'
                    '－', '−', '﹣' -> '-'
                    '＋', '﹢' -> '+'
                    '　' -> ' '
                    in '！'..'～' -> c - FULL_WIDTH
                    else -> c
                },
            )
        }
    }.trim()

    /** The finite number [text] says, typed either way; null for anything else. */
    fun double(text: String): Double? = plain(text).toDoubleOrNull()?.takeIf { it.isFinite() }

    /** The whole number [text] says, typed either way; null for anything else. */
    fun long(text: String): Long? = plain(text).toLongOrNull()

    /** The whole number [text] says, typed either way, when it fits an Int; null for anything else. */
    fun int(text: String): Int? = plain(text).toIntOrNull()

    // How far the full-width forms of ASCII's ! to ~ (U+FF01 to U+FF5E) are from their plain ones.
    private const val FULL_WIDTH = 0xFEE0
}
