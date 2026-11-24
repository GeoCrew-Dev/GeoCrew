package org.geocrew.core.data.repo

import android.content.Context
import android.net.Uri
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.geocrew.core.data.di.ApplicationScope
import org.geocrew.core.formats.MbTiles
import org.geocrew.core.model.Bounds
import org.geocrew.core.model.tiles.BulkDownload
import org.geocrew.core.model.tiles.TileLayer
import org.geocrew.core.model.tiles.TilePlan
import org.geocrew.core.model.tiles.TileSource
import org.geocrew.core.model.tiles.TileTemplates
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Progress of downloading an area of a base map for offline use. */
data class BasemapDownload(
    /** Folder name under files/basemaps, also the offline source's id once done. */
    val id: String,
    val name: String,
    val total: Long,
    val done: Long = 0,
    /** Tiles the provider had nothing for (sea, outside its coverage) or that failed twice. */
    val missing: Long = 0,
    val state: State = State.RUNNING,
    /** The new offline source once [State.DONE]. */
    val source: TileSource? = null,
    /** Chinese reason when [State.FAILED] or why it [State.PAUSED] by itself. */
    val message: String? = null,
) {
    enum class State { RUNNING, DONE, FAILED, CANCELLED, PAUSED }
    val finished get() = state != State.RUNNING
}

/** A download that stopped part way (paused, the network went, the app was closed) and can go on. */
data class PausedDownload(val id: String, val name: String, val done: Long, val total: Long)

/** What a download folder needs to go on later; never holds the key. */
@Serializable
internal data class DownloadJob(
    val title: String,
    val source: TileSource,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val done: Long = 0,
) {
    val plan get() = TilePlan(Bounds(west, south, east, north), minZoom, maxZoom, source.layers.size)
}

/**
 * Downloads the tiles of a [TilePlan] from an online base map into
 * `files/basemaps/offline-…/<layer>/{z}/{x}/{y}.ext`, the same place an
 * imported MBTiles package goes, and registers the result as an offline
 * source. Runs in the application scope, kept alive by
 * [BasemapDownloadService] while the screen is off; one download at a time,
 * a few requests in parallel.
 *
 * A download can stop part way: paused by the user, the network gone for a
 * while, the app closed. Its folder keeps a small job file and the tiles
 * fetched so far, it shows in [paused], and [resume] fetches only the rest.
 */
@Singleton
class BasemapDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val client: OkHttpClient,
    private val defaults: BuildDefaults,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<BasemapDownload?>(null)
    val current: StateFlow<BasemapDownload?> = _current.asStateFlow()
    private val _paused = MutableStateFlow<List<PausedDownload>>(emptyList())

    /** Downloads that stopped part way, newest first, not counting the one running. */
    val paused: StateFlow<List<PausedDownload>> = _paused.asStateFlow()
    private var job: Job? = null
    @Volatile private var pausing = false
    private val root get() = File(context.filesDir, "basemaps")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        scope.launch(Dispatchers.IO) { refreshPaused() }
    }

    /** Starts downloading; returns a Chinese reason instead when it can't start. */
    fun start(source: TileSource, key: String?, plan: TilePlan, name: String): String? {
        if (_current.value?.finished == false) return "已有一个离线底图在下载"
        (BulkDownload.of(source) as? BulkDownload.Refused)?.let { return it.reason }
        if (source.keyId != null && key == null) return "这个底图需要 Key，请先在设置里填写"
        if (plan.requests > TilePlan.MAX_REQUESTS) return "范围太大：需要 ${plan.requests} 次请求，一次最多 ${TilePlan.MAX_REQUESTS}，请缩小范围或层级"
        if (StatFs(context.filesDir.path).availableBytes < plan.estimatedBytes + SPARE) {
            return "手机存储空间不足：约需 ${plan.estimatedBytes / MB + 1} MB"
        }
        val title = name.trim().ifEmpty { source.name + "（离线）" }
        val id = "offline-" + UUID.randomUUID().toString().take(8)
        val b = plan.bounds
        val dir = File(root, id).apply { mkdirs() }
        val descriptor = DownloadJob(title, source, b.west, b.south, b.east, b.north, plan.minZoom, plan.maxZoom)
        try {
            writeJob(dir, descriptor)
        } catch (e: IOException) {
            dir.deleteRecursively()
            return "无法写入手机存储"
        }
        launchDownload(id, descriptor, key)
        return null
    }

    /** Goes on with a download from [paused]; returns a Chinese reason instead when it can't. */
    suspend fun resume(id: String): String? {
        if (_current.value?.finished == false) return "已有一个离线底图在下载"
        val dir = File(root, id)
        val descriptor = withContext(Dispatchers.IO) { readJob(dir) } ?: run {
            refreshPaused()
            return "这个下载的记录找不到了"
        }
        val source = descriptor.source
        val key = source.keyId?.let { settings.keysOf(settings.current())[it] }
        if (source.keyId != null && key == null) return "这个底图需要 Key，请先在设置里填写"
        val plan = descriptor.plan
        val left = plan.estimatedBytes - descriptor.done * TilePlan.AVERAGE_TILE_BYTES
        if (StatFs(context.filesDir.path).availableBytes < left.coerceAtLeast(0) + SPARE) {
            return "手机存储空间不足：约需 ${left / MB + 1} MB"
        }
        launchDownload(id, descriptor, key)
        return null
    }

    private fun launchDownload(id: String, descriptor: DownloadJob, key: String?) {
        val plan = descriptor.plan
        val dir = File(root, id)
        pausing = false
        _current.value = BasemapDownload(id, descriptor.title, plan.requests)
        _paused.update { list -> list.filterNot { it.id == id } }
        BasemapDownloadService.start(context)
        job = scope.launch(Dispatchers.IO) {
            try {
                pruneOrphans()
                val made = fetchAll(descriptor, key, plan, dir)
                val result = offlineSource(id, descriptor.title, descriptor.source, plan, made, Uri.fromFile(dir).toString())
                settings.saveCustomSource(result)
                File(dir, JOB).delete()
                _current.update { it?.copy(state = BasemapDownload.State.DONE, source = result) }
            } catch (e: CancellationException) {
                if (pausing) stopPartWay(dir, descriptor, null) else {
                    dir.deleteRecursively()
                    _current.update { it?.copy(state = BasemapDownload.State.CANCELLED) }
                }
            } catch (e: NetworkGone) {
                stopPartWay(dir, descriptor, "网络断了，已暂停；有信号后点「继续」")
            } catch (e: IOException) {
                val kept = hasTiles(dir)
                if (kept) saveProgress(dir, descriptor) else dir.deleteRecursively()
                _current.update {
                    it?.copy(state = BasemapDownload.State.FAILED, message = (e.message ?: "下载失败") + if (kept) "；已下载的部分保留着，可稍后继续" else "")
                }
            } finally {
                withContext(NonCancellable) { refreshPaused() }
            }
        }
    }

    private fun stopPartWay(dir: File, descriptor: DownloadJob, message: String?) {
        saveProgress(dir, descriptor)
        _current.update { it?.copy(state = BasemapDownload.State.PAUSED, message = message) }
    }

    /** Stops the running download but keeps what it fetched, to [resume] later. */
    fun pause() {
        pausing = true
        job?.cancel()
    }

    /** Stops the running download and deletes what it fetched. */
    fun cancel() {
        pausing = false
        job?.cancel()
    }

    /** Deletes a download from [paused] and the tiles it fetched. */
    fun discard(id: String) {
        if (_current.value?.let { it.id == id && !it.finished } == true) return
        _paused.update { list -> list.filterNot { it.id == id } }
        scope.launch(Dispatchers.IO) {
            File(root, id).takeIf { it.name.startsWith(PREFIX) }?.deleteRecursively()
            refreshPaused()
        }
    }

    /** Forget a finished download so its notice goes away. */
    fun dismiss() { if (_current.value?.finished == true) _current.value = null }

    private suspend fun refreshPaused() {
        val known = settings.current().customSources.filter { it.offline }.map { it.id }.toSet()
        val running = _current.value?.takeIf { !it.finished }?.id
        _paused.value = withContext(Dispatchers.IO) {
            root.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith(PREFIX) && it.name !in known && it.name != running }
                .mapNotNull { d -> readJob(d)?.let { j -> d.lastModified() to PausedDownload(d.name, j.title, j.done, j.plan.requests) } }
                .sortedByDescending { it.first }
                .map { it.second }
        }
    }

    /**
     * Removes folders a download left without a job file (an older version
     * cut short, or a failure before anything was written) that no saved
     * source points to. An hour's margin keeps an MBTiles import that is
     * unpacking right now safe.
     */
    private suspend fun pruneOrphans() {
        val known = settings.current().customSources.filter { it.offline }.map { it.id }.toSet()
        val before = System.currentTimeMillis() - 3_600_000
        root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(PREFIX) && it.name !in known && !File(it, JOB).exists() && it.lastModified() < before }
            ?.forEach { it.deleteRecursively() }
    }

    private fun readJob(dir: File): DownloadJob? = try {
        File(dir, JOB).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(DownloadJob.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun writeJob(dir: File, job: DownloadJob) {
        val tmp = File(dir, "$JOB.tmp")
        tmp.writeText(json.encodeToString(DownloadJob.serializer(), job))
        if (!tmp.renameTo(File(dir, JOB))) throw IOException("无法写入下载记录")
    }

    private fun saveProgress(dir: File, descriptor: DownloadJob) {
        val done = _current.value?.done ?: return
        try {
            writeJob(dir, descriptor.copy(done = done))
            dir.setLastModified(System.currentTimeMillis())
        } catch (e: IOException) {
            // The tiles are still there; only the progress shown before resuming is off.
        }
    }

    private fun hasTiles(dir: File) = dir.listFiles().orEmpty().any { d -> d.isDirectory && d.walkTopDown().any { it.isFile } }

    /** The extension a layer's tiles were saved with on an earlier run, if any. */
    private fun savedExtension(dir: File, layer: Int) =
        File(dir, layer.toString()).takeIf { it.isDirectory }?.walkTopDown()?.firstOrNull { it.isFile }?.extension

    /** Per layer, the tile extension used, or null when the layer got no tile at all. */
    private suspend fun fetchAll(descriptor: DownloadJob, key: String?, plan: TilePlan, dir: File): List<String?> = coroutineScope {
        val layers = descriptor.source.layers.map { TileTemplates.resolve(it, key) }
        val ext = AtomicReferenceArray<String?>(layers.size)
        for (i in layers.indices) ext.set(i, savedExtension(dir, i))
        val ok = AtomicLong()
        val unreachable = AtomicInteger()
        val refused = AtomicLong()
        val lastRefusal = AtomicInteger()
        val queue = Channel<Triple<Int, Int, Int>>(capacity = 64)
        launch {
            for (r in plan.ranges) for (x in r.xMin..r.xMax) for (y in r.yMin..r.yMax) queue.send(Triple(r.zoom, x, y))
            queue.close()
        }
        val workers = List(PARALLEL) {
            launch {
                for ((z, x, y) in queue) {
                    for ((i, layer) in layers.withIndex()) {
                        if (z < layer.minZoom || z > layer.maxZoom) { tick(missing = true, dir, descriptor); continue }
                        val row = if (layer.tms) (1 shl z) - 1 - y else y
                        val known = ext.get(i)
                        if (known != null && File(dir, "$i/" + MbTiles.relativePath(z, x, y, known)).isFile) {
                            // Fetched on an earlier run.
                            ok.incrementAndGet()
                            tick(missing = false, dir, descriptor)
                            continue
                        }
                        val url = layer.urls[(x + y).mod(layer.urls.size)]
                            .replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", row.toString())
                        val r = fetch(url)
                        if (r == Fetch.Unreachable) {
                            // Many in a row: the network is gone, so stop and keep what we have.
                            if (unreachable.incrementAndGet() >= UNREACHABLE_IN_A_ROW) throw NetworkGone()
                        } else {
                            unreachable.set(0)
                        }
                        when (r) {
                            is Fetch.Tile -> {
                                val e = ext.get(i) ?: (MbTiles.sniff(r.bytes)?.takeIf { it != "pbf" } ?: "png").also { ext.compareAndSet(i, null, it) }
                                val f = File(dir, "$i/" + MbTiles.relativePath(z, x, y, ext.get(i) ?: e))
                                f.parentFile?.mkdirs()
                                f.writeBytes(r.bytes)
                                ok.incrementAndGet()
                                tick(missing = false, dir, descriptor)
                            }
                            is Fetch.Refused -> {
                                lastRefusal.set(r.code)
                                refused.incrementAndGet()
                                // A provider refusing the first requests outright (bad key, quota spent): stop early.
                                if (ok.get() == 0L && refused.get() >= 12) {
                                    throw IOException("底图服务拒绝了请求（HTTP ${r.code}），请检查 Key 是否有效、当天调用量是否用完")
                                }
                                tick(missing = true, dir, descriptor)
                            }
                            Fetch.Missing, Fetch.Unreachable -> tick(missing = true, dir, descriptor)
                        }
                    }
                }
            }
        }
        workers.forEach { it.join() }
        if (ok.get() == 0L) {
            throw IOException(if (refused.get() > 0) "底图服务拒绝了请求（HTTP ${lastRefusal.get()}），没有下载到瓦片" else "这个范围没有下载到任何瓦片")
        }
        List(layers.size) { ext.get(it) }
    }

    private fun tick(missing: Boolean, dir: File, descriptor: DownloadJob) {
        val now = _current.updateAndGet { it?.copy(done = it.done + 1, missing = it.missing + if (missing) 1 else 0) }
        // Now and then, so a download the system cuts short shows about where it got to.
        if (now != null && now.done % SAVE_EVERY == 0L) saveProgress(dir, descriptor)
    }

    private class NetworkGone : IOException("网络断了")

    private sealed interface Fetch {
        class Tile(val bytes: ByteArray) : Fetch
        class Refused(val code: Int) : Fetch
        data object Missing : Fetch
        /** No answer at all, twice: no network, or the server is down. */
        data object Unreachable : Fetch
    }

    /** One tile, with a single retry for network errors and server hiccups. */
    private suspend fun fetch(url: String): Fetch {
        var reached = false
        for (attempt in 0..1) {
            coroutineContext.ensureActive()
            val result = try {
                val req = Request.Builder().url(url).header("User-Agent", defaults.userAgent).build()
                client.newCall(req).execute().use { resp ->
                    reached = true
                    val type = resp.header("Content-Type").orEmpty().lowercase()
                    when {
                        resp.code in REFUSALS -> Fetch.Refused(resp.code)
                        resp.code == 404 || resp.code == 204 -> Fetch.Missing
                        !resp.isSuccessful -> null
                        // Providers often answer errors as a 200 with XML or JSON.
                        type.contains("xml") || type.contains("json") || type.startsWith("text/") -> Fetch.Refused(resp.code)
                        else -> resp.body?.bytes()?.takeIf { it.isNotEmpty() }?.let { Fetch.Tile(it) } ?: Fetch.Missing
                    }
                }
            } catch (e: IOException) {
                null
            }
            if (result != null) return result
        }
        return if (reached) Fetch.Missing else Fetch.Unreachable
    }

    private fun offlineSource(id: String, name: String, source: TileSource, plan: TilePlan, ext: List<String?>, dirUrl: String) = TileSource(
        id = id,
        name = name,
        layers = source.layers.mapIndexedNotNull { i, l: TileLayer ->
            val e = ext[i] ?: return@mapIndexedNotNull null
            TileLayer(url = "$dirUrl/$i/{z}/{x}/{y}.$e", tileSize = l.tileSize, minZoom = plan.minZoom, maxZoom = plan.maxZoom, opacity = l.opacity)
        },
        attribution = source.attribution,
        maxZoom = (plan.maxZoom + 3).coerceAtMost(22),
        custom = true,
        group = "离线",
        offline = true,
        bounds = listOf(plan.bounds.west, plan.bounds.south, plan.bounds.east, plan.bounds.north),
    )

    private companion object {
        const val PARALLEL = 4
        const val PREFIX = "offline-"
        const val JOB = "download.json"
        const val SAVE_EVERY = 250L
        const val UNREACHABLE_IN_A_ROW = 16
        const val MB = 1024L * 1024
        const val SPARE = 50 * MB
        val REFUSALS = setOf(401, 403, 418, 429)
    }
}
