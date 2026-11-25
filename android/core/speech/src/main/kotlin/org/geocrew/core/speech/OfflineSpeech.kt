package org.geocrew.core.speech

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Speech recognition that works without a signal: a small Chinese Vosk
 * model (about 40 MB) the user downloads once in settings, used for live
 * dictation and for turning recorded voice notes into text afterwards.
 * Nothing is sent anywhere; the model runs on the phone.
 */
@Singleton
class OfflineSpeech @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    sealed interface ModelState {
        data object Missing : ModelState
        data class Downloading(val bytes: Long, val total: Long) : ModelState
        data object Unpacking : ModelState
        data class Ready(val bytes: Long) : ModelState
        data class Failed(val message: String) : ModelState
    }

    private val prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    private val root: File get() = File(context.filesDir, "speech")
    private val modelDir: File get() = File(root, "model")
    private val marker: File get() = File(modelDir, ".ready")

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** Where the model zip comes from; a mirror can be set when the default host is slow. */
    var modelUrl: String
        get() = prefs.getString("model_url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        set(v) { prefs.edit().putString("model_url", v.trim().takeIf { it != DEFAULT_URL && it.isNotEmpty() }).apply() }

    val ready: Boolean get() = marker.isFile

    private val lock = Mutex()
    private var model: Model? = null

    private fun initialState(): ModelState = if (marker.isFile) ModelState.Ready(size(modelDir)) else ModelState.Missing

    /** Downloads and unpacks the model; safe to call again after a failure. */
    suspend fun download() = withContext(Dispatchers.IO) {
        val url = modelUrl
        require(url.startsWith("https://")) { "模型地址必须是 https" }
        val tmp = File(root, "model.zip.part")
        try {
            root.mkdirs()
            _state.value = ModelState.Downloading(0, -1)
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("下载失败（HTTP ${r.code}）")
                val body = r.body ?: throw IOException("下载失败")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (got - last > 256 * 1024) { _state.value = ModelState.Downloading(got, total); last = got }
                            if (got > MAX_ZIP) throw IOException("文件太大，不像语音模型")
                        }
                    }
                }
            }
            _state.value = ModelState.Unpacking
            lock.withLock {
                release()
                modelDir.deleteRecursively()
                unzip(tmp, modelDir)
                if (!File(modelDir, "am").isDirectory && !File(modelDir, "conf").isDirectory) throw IOException("压缩包里没有 Vosk 模型")
                marker.writeText(url)
            }
            _state.value = ModelState.Ready(size(modelDir))
        } catch (e: CancellationException) {
            _state.value = initialState()
            throw e
        } catch (e: Exception) {
            modelDir.takeIf { !marker.isFile }?.deleteRecursively()
            _state.value = ModelState.Failed(e.message ?: "下载失败")
        } finally {
            tmp.delete()
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        lock.withLock {
            release()
            modelDir.deleteRecursively()
        }
        _state.value = ModelState.Missing
    }

    private fun release() {
        model?.close()
        model = null
    }

    private suspend fun model(): Model = lock.withLock {
        model ?: run {
            check(marker.isFile) { "还没有下载离线语音模型" }
            Model(modelDir.absolutePath).also { model = it }
        }
    }

    /** What is heard so far, then the final text when the speaker stops. */
    sealed interface Heard {
        data class Partial(val text: String) : Heard
        data class Final(val text: String) : Heard
    }

    /**
     * Listens on the microphone until the flow is cancelled, emitting
     * partial text as it comes and a [Heard.Final] after each pause. Needs
     * the RECORD_AUDIO permission.
     */
    fun listen(): Flow<Heard> = callbackFlow {
        val rec = Recognizer(model(), SAMPLE_RATE.toFloat())
        val service = SpeechService(rec, SAMPLE_RATE.toFloat())
        service.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) { text(hypothesis, "partial")?.let { trySend(Heard.Partial(it)) } }
            override fun onResult(hypothesis: String?) { text(hypothesis, "text")?.let { trySend(Heard.Final(it)) } }
            override fun onFinalResult(hypothesis: String?) { text(hypothesis, "text")?.let { trySend(Heard.Final(it)) } }
            override fun onError(exception: Exception?) { close(exception ?: IOException("识别出错")) }
            override fun onTimeout() { close() }
        })
        awaitClose {
            service.stop()
            service.shutdown()
            rec.close()
        }
    }.flowOn(Dispatchers.IO)

    /** Turns a recorded voice note (AAC in MP4, as the app records them) into text. */
    suspend fun transcribe(file: File): String = withContext(Dispatchers.Default) {
        val m = model()
        Recognizer(m, SAMPLE_RATE.toFloat()).use { rec ->
            val parts = mutableListOf<String>()
            decodePcm16k(file) { bytes, len ->
                if (rec.acceptWaveForm(bytes, len)) text(rec.result, "text")?.let { parts += it }
            }
            text(rec.finalResult, "text")?.let { parts += it }
            parts.joinToString(" ")
        }
    }

    private fun text(json: String?, key: String): String? =
        json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val DEFAULT_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
        const val SAMPLE_RATE = 16_000
        private const val MAX_ZIP = 400L * 1024 * 1024

        fun size(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

        /** Unpacks [zip] into [dest], dropping the archive's top folder and refusing paths that leave [dest]. */
        fun unzip(zip: File, dest: File) {
            dest.mkdirs()
            val base = dest.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name.replace('\\', '/').substringAfter('/', "")
                    if (name.isEmpty()) continue
                    val out = File(dest, name)
                    if (!out.canonicalPath.startsWith(base)) throw IOException("压缩包里有不安全的路径")
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }

        /**
         * Decodes the first audio track of [file] to 16-bit mono PCM at 16 kHz,
         * handing it over in chunks. Channels are averaged and the rate is
         * changed by linear interpolation, which is plenty for speech.
         */
        fun decodePcm16k(file: File, sink: (ByteArray, Int) -> Unit) {
            val ex = MediaExtractor()
            ex.setDataSource(file.absolutePath)
            try {
                val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: throw IOException("录音里没有音轨")
                ex.selectTrack(track)
                val fmt = ex.getTrackFormat(track)
                val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(fmt, null, null, 0)
                codec.start()
                var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val resampler = Resampler()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                try {
                    while (true) {
                        if (!inputDone) {
                            val i = codec.dequeueInputBuffer(10_000)
                            if (i >= 0) {
                                val buf = codec.getInputBuffer(i)!!
                                val n = ex.readSampleData(buf, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            }
                        }
                        val o = codec.dequeueOutputBuffer(info, 10_000)
                        when {
                            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            o >= 0 -> {
                                val buf = codec.getOutputBuffer(o)!!
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val shorts = buf.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                                val pcm = ShortArray(shorts.remaining()).also { shorts.get(it) }
                                codec.releaseOutputBuffer(o, false)
                                val out = resampler.push(pcm, channels, rate)
                                if (out.isNotEmpty()) {
                                    val bytes = ByteBuffer.allocate(out.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                                    out.forEach { bytes.putShort(it) }
                                    sink(bytes.array(), out.size * 2)
                                }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            }
                        }
                    }
                } finally {
                    codec.stop()
                    codec.release()
                }
            } finally {
                ex.release()
            }
        }
    }
}

/** Mono 16 kHz from any interleaved PCM, keeping its position across chunks. */
internal class Resampler(private val target: Int = OfflineSpeech.SAMPLE_RATE) {
    private var pos = 0.0
    private var prev = 0.0

    fun push(pcm: ShortArray, channels: Int, rate: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = pcm.size / ch
        if (frames == 0) return ShortArray(0)
        val mono = DoubleArray(frames) { f -> (0 until ch).sumOf { pcm[f * ch + it].toDouble() } / ch }
        val step = rate.toDouble() / target
        val out = ArrayList<Short>((frames / step).toInt() + 2)
        // pos counts input frames from the start of this chunk; -1 is the last frame of the previous one.
        while (pos < frames - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val t = pos - i
            val a = if (i < 0) prev else mono[i]
            val b = mono[i + 1]
            out += (a + (b - a) * t).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos += step
        }
        pos -= frames
        prev = mono[frames - 1]
        return out.toShortArray()
    }
}
