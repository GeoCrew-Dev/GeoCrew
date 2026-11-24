package org.geocrew.feature.record

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a voice note as mono AAC in an MP4 container (protocol v0 §6.3):
 * about 0.5 MB a minute, playable on any phone or computer.
 */
internal class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null

    val active: Boolean get() = recorder != null

    /** Starts recording into [target]; throws if the microphone is unavailable. */
    fun start(target: File) {
        check(recorder == null) { "already recording" }
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(target.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    /** Input level 0–1 since the last call, for a simple meter. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32_767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** Stops and closes the file; false when nothing usable was recorded. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // MediaRecorder throws when stopped before any audio was written.
            false
        } finally {
            r.release()
        }
    }
}

/** Plays one voice note at a time. */
internal class VoicePlayer {
    private var player: MediaPlayer? = null

    val positionMillis: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    /** Starts [file] and returns its length in milliseconds; [onDone] runs on the main thread at the end. */
    fun play(file: File, onDone: () -> Unit): Int {
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            p.setDataSource(file.path)
            p.setOnCompletionListener { onDone() }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            p.release()
            throw e
        }
        player = p
        return p.duration.coerceAtLeast(0)
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }
}
