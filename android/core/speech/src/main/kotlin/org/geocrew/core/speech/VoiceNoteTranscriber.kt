package org.geocrew.core.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.PhotoStore
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.AttachmentKind
import org.geocrew.core.model.AudioInfo
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Props
import org.geocrew.core.model.SpeechText
import org.geocrew.core.model.audio
import org.geocrew.core.model.audioInfo
import org.geocrew.core.model.string
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Record first, write later: goes through the current project's voice notes
 * that have no transcript yet and writes one down with the offline model.
 * The recording stays; the text is kept beside it and never edits the
 * description by itself.
 */
@Singleton
class VoiceNoteTranscriber @Inject constructor(
    private val speech: OfflineSpeech,
    private val features: FeatureRepository,
    private val projects: ProjectRepository,
    private val files: PhotoStore,
) {
    data class Progress(val done: Int, val total: Int, val failed: Int = 0, val finished: Boolean = false)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Voice notes in [records] still waiting for words, with their file on this phone. */
    fun pending(records: List<GeoFeature>): List<Pair<GeoFeature, String>> = records.filter { !it.deleted }.flatMap { f ->
        val info = f.audioInfo
        f.audio.filter { info[it]?.transcript == null && files.exists(it, AttachmentKind.AUDIO) }.map { f to it }
    }

    suspend fun pendingInCurrent(): Int = pending(features.observeProject(projects.current.first().id).first()).size

    /** Transcribes every pending voice note of the current project; returns how many got text. */
    suspend fun transcribeCurrent(): Int {
        val project = projects.current.first()
        val records = features.observeProject(project.id).first()
        val work = pending(records)
        val vocab = SpeechText.vocabulary(project.config.phrases, records.mapNotNull { it.properties.string(Props.LITHOLOGY) })
        var done = 0
        var failed = 0
        _progress.value = Progress(0, work.size)
        try {
            for ((f, sha) in work) {
                try {
                    val heard = speech.transcribe(files.file(sha, AttachmentKind.AUDIO))
                    val text = SpeechText.fix(SpeechText.clean(heard), vocab, Pinyin::of).text
                    // Re-read the record: it may have changed while the model worked.
                    val now = features.observe(f.id).first() ?: continue
                    val info = now.audioInfo[sha] ?: AudioInfo()
                    if (info.transcript == null) {
                        features.edit(f.id) { audioInfo(sha, info.copy(transcript = text.ifBlank { NOTHING_HEARD })) }
                    }
                    done++
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed++
                }
                _progress.value = Progress(done + failed, work.size, failed)
            }
        } finally {
            _progress.value = Progress(done + failed, work.size, failed, finished = true)
        }
        return done
    }

    fun clearProgress() { _progress.value = null }

    companion object {
        const val NOTHING_HEARD = "（没有听出内容）"
    }
}
