package org.geocrew.core.data.repo

import android.content.Context
import android.net.Uri
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.geocrew.core.data.di.ApplicationScope
import org.geocrew.core.formats.MbTiles
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
    val name: String,
    val total: Long,
    val done: Long = 0,
    /** Tiles the provider had nothing for (sea, outside its coverage) or that failed twice. */
    val missing: Long = 0,
    val state: State = State.RUNNING,
    /** The new offline source once [State.DONE]. */
    val source: TileSource? = null,
    /** Chinese reason when [State.FAILED]. */
    val message: String? = null,
) {
    enum class State { RUNNING, DONE, FAILED, CANCELLED }
    val finished get() = state != State.RUNNING
}

/**
 * Downloads the tiles of a [TilePlan] from an online base map into
 * `files/basemaps/offline-…/<layer>/{z}/{x}/{y}.ext`, the same place an
 * imported MBTiles package goes, and registers the result as an offline
 * source. Runs in the application scope so leaving the screen doesn't stop
 * it; one download at a time, a few requests in parallel.
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
    private var job: Job? = null

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
        _current.value = BasemapDownload(title, plan.requests)
        job = scope.launch(Dispatchers.IO) {
            val id = "offline-" + UUID.randomUUID().toString().take(8)
            val dir = File(File(context.filesDir, "basemaps"), id)
            try {
                val made = fetchAll(source, key, plan, dir)
                val result = offlineSource(id, title, source, plan, made, Uri.fromFile(dir).toString())
                settings.saveCustomSource(result)
                _current.update { it?.copy(state = BasemapDownload.State.DONE, source = result) }
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                _current.update { it?.copy(state = BasemapDownload.State.CANCELLED) }
            } catch (e: IOException) {
                dir.deleteRecursively()
                _current.update { it?.copy(state = BasemapDownload.State.FAILED, message = e.message ?: "下载失败") }
            }
        }
        return null
    }

    fun cancel() { job?.cancel() }

    /** Forget a finished download so its notice goes away. */
    fun dismiss() { if (_current.value?.finished == true) _current.value = null }

    /** Per layer, the tile extension used, or null when the layer got no tile at all. */
    private suspend fun fetchAll(source: TileSource, key: String?, plan: TilePlan, dir: File): List<String?> = coroutineScope {
        val layers = source.layers.map { TileTemplates.resolve(it, key) }
        val ext = AtomicReferenceArray<String?>(layers.size)
        val ok = AtomicLong()
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
                        if (z < layer.minZoom || z > layer.maxZoom) { tick(missing = true); continue }
                        val row = if (layer.tms) (1 shl z) - 1 - y else y
                        val url = layer.urls[(x + y).mod(layer.urls.size)]
                            .replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", row.toString())
                        when (val r = fetch(url)) {
                            is Fetch.Tile -> {
                                val e = ext.get(i) ?: (MbTiles.sniff(r.bytes)?.takeIf { it != "pbf" } ?: "png").also { ext.compareAndSet(i, null, it) }
                                val f = File(dir, "$i/" + MbTiles.relativePath(z, x, y, ext.get(i) ?: e))
                                f.parentFile?.mkdirs()
                                f.writeBytes(r.bytes)
                                ok.incrementAndGet()
                                tick(missing = false)
                            }
                            is Fetch.Refused -> {
                                lastRefusal.set(r.code)
                                refused.incrementAndGet()
                                // A provider refusing the first requests outright (bad key, quota spent): stop early.
                                if (ok.get() == 0L && refused.get() >= 12) {
                                    throw IOException("底图服务拒绝了请求（HTTP ${r.code}），请检查 Key 是否有效、当天调用量是否用完")
                                }
                                tick(missing = true)
                            }
                            Fetch.Missing -> tick(missing = true)
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

    private fun tick(missing: Boolean) = _current.update {
        it?.copy(done = it.done + 1, missing = it.missing + if (missing) 1 else 0)
    }

    private sealed interface Fetch {
        class Tile(val bytes: ByteArray) : Fetch
        class Refused(val code: Int) : Fetch
        data object Missing : Fetch
    }

    /** One tile, with a single retry for network errors and server hiccups. */
    private suspend fun fetch(url: String): Fetch {
        for (attempt in 0..1) {
            coroutineContext.ensureActive()
            val result = try {
                val req = Request.Builder().url(url).header("User-Agent", defaults.userAgent).build()
                client.newCall(req).execute().use { resp ->
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
        return Fetch.Missing
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
        const val MB = 1024L * 1024
        const val SPARE = 50 * MB
        val REFUSALS = setOf(401, 403, 418, 429)
    }
}
