package org.geocrew.core.model

/**
 * Tidies what a speech recogniser returns and pulls it towards the words
 * a geologist uses. Recognisers often hear a rock name as a same-sounding
 * everyday word (花岗眼 for 花岗岩, 片马岩 for 片麻岩); [fix] replaces a run
 * of characters with a known term when the two sound the same, judged by
 * their toneless pinyin, so the project's phrase list and lithology names
 * act as a vocabulary without retraining anything.
 *
 * The pinyin lookup is passed in, so this stays plain Kotlin: the app uses
 * the platform's ICU transliterator, tests use a small table.
 */
object SpeechText {
    /** Removes the spaces offline recognisers put between Chinese words, keeping those between Latin words and numbers. */
    fun clean(raw: String): String {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (c == ' ') {
                val before = s.getOrNull(i - 1)
                val after = s.getOrNull(i + 1)
                if (before != null && after != null && (cjk(before) || cjk(after))) continue
            }
            out.append(c)
        }
        return out.toString()
    }

    /** A fix made by [fix]: [heard] at [at] became [term]. */
    data class Fix(val at: Int, val heard: String, val term: String)

    data class Result(val text: String, val fixes: List<Fix>)

    /**
     * Replaces same-sounding runs with [terms]. Longer terms win, a run is
     * fixed once, and runs that are already a term, or terms shorter than
     * two characters, are left alone. [pinyin] gives a character's toneless
     * syllable, or null for anything that is not a Chinese character.
     */
    fun fix(text: String, terms: Collection<String>, pinyin: (Char) -> String?): Result {
        val usable = terms.asSequence().map { it.trim() }.filter { it.length in 2..12 && it.all(::cjk) }.distinct()
            .sortedByDescending { it.length }.toList()
        if (usable.isEmpty() || text.isEmpty()) return Result(text, emptyList())
        val sounds = HashMap<String, List<String>?>()
        fun soundOf(s: String) = sounds.getOrPut(s) { s.map { pinyin(it) ?: return@getOrPut null } }
        val taken = BooleanArray(text.length)
        val fixes = mutableListOf<Fix>()
        for (term in usable) {
            val want = soundOf(term) ?: continue
            var i = 0
            while (i + term.length <= text.length) {
                val run = text.substring(i, i + term.length)
                if ((i until i + term.length).any { taken[it] }) { i++; continue }
                if (run == term) {
                    (i until i + term.length).forEach { taken[it] = true }
                    i += term.length; continue
                }
                if (run.all(::cjk) && soundOf(run) == want) {
                    fixes += Fix(i, run, term)
                    (i until i + term.length).forEach { taken[it] = true }
                    i += term.length; continue
                }
                i++
            }
        }
        if (fixes.isEmpty()) return Result(text, emptyList())
        val sb = StringBuilder(text)
        for (f in fixes.sortedByDescending { it.at }) sb.replace(f.at, f.at + f.heard.length, f.term)
        return Result(sb.toString(), fixes.sortedBy { it.at })
    }

    /** The vocabulary for [fix]: the project's and built-in description words plus the built-in and used lithology names. */
    fun vocabulary(phrases: List<PhraseGroup>, lithologies: Collection<String>): Set<String> =
        (FieldTerms.all(phrases).flatMap { it.items } + Lithology.common + lithologies).toSet()

    fun cjk(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿'
}
