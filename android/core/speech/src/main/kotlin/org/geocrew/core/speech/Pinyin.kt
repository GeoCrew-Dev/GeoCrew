package org.geocrew.core.speech

import android.icu.text.Transliterator

/** Toneless pinyin of a Chinese character from the platform's ICU tables, for [org.geocrew.core.model.SpeechText.fix]. */
object Pinyin {
    private val tr: Transliterator by lazy { Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower") }
    private val cache = HashMap<Char, String?>()

    @Synchronized
    fun of(c: Char): String? {
        if (!org.geocrew.core.model.SpeechText.cjk(c)) return null
        return cache.getOrPut(c) { tr.transliterate(c.toString()).trim().takeIf { s -> s.isNotEmpty() && s.all { it in 'a'..'z' } } }
    }
}
