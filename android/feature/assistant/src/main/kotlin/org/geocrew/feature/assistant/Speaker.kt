package org.geocrew.feature.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Says the assistant's questions and results aloud with the phone's own text-to-speech. */
@Singleton
class Speaker @Inject constructor(@ApplicationContext private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = mutableListOf<Pair<String, () -> Unit>>()
    private val done = HashMap<String, () -> Unit>()

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { synchronized(done) { done.remove(id) }?.invoke() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { synchronized(done) { done.remove(id) }?.invoke() }
                })
                queued.toList().forEach { (t, d) -> say(t, 1f, d) }
            } else {
                queued.toList().forEach { it.second() }
            }
            queued.clear()
        }
    }

    /** Speaks [text] at [rate]; [onDone] runs when it finishes, or at once if speech is unavailable. */
    fun say(text: String, rate: Float = 1f, onDone: () -> Unit = {}) {
        ensure()
        val t = tts
        if (t == null || !ready) { queued += text to onDone; return }
        val id = UUID.randomUUID().toString()
        synchronized(done) { done[id] = onDone }
        t.setSpeechRate(rate)
        // Symbols read badly; say the attitude sign as the word geologists use.
        t.speak(text.replace("∠", "，倾角").replace("°", "度"), TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() { tts?.stop() }
}
