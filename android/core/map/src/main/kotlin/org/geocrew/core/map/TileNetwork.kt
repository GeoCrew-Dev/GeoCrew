package org.geocrew.core.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.maplibre.android.module.http.HttpRequestUtil

/** A base map provider refusing tiles, e.g. an invalid key or an exhausted daily quota. */
data class TileProblem(val host: String, val httpCode: Int, val atMillis: Long)

/**
 * Installs the HTTP client MapLibre uses for tiles: a clear User-Agent and a
 * watcher that notices when a provider starts refusing requests, so the UI
 * can tell the user instead of showing a silently blank map.
 */
object TileNetwork {
    private val _problem = MutableStateFlow<TileProblem?>(null)
    val problem: StateFlow<TileProblem?> = _problem.asStateFlow()

    @Volatile private var installed = false

    fun install(userAgent: String) {
        if (installed) return
        installed = true
        val client = OkHttpClient.Builder()
            .addInterceptor(Watcher(userAgent))
            .build()
        HttpRequestUtil.setOkHttpClient(client)
    }

    /** Forget the last problem, e.g. after the user changed key or source. */
    fun clear() {
        _problem.value = null
    }

    private class Watcher(private val userAgent: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request().newBuilder().header("User-Agent", userAgent).build()
            val resp = chain.proceed(req)
            if (isRefusal(resp)) {
                _problem.value = TileProblem(req.url.host, resp.code, System.currentTimeMillis())
            }
            return resp
        }

        /** 401/403/418/429 or a 200 that is not an image (providers often answer errors as XML/JSON). */
        private fun isRefusal(resp: Response): Boolean {
            if (resp.code in setOf(401, 403, 418, 429)) return true
            if (!resp.isSuccessful) return false
            val type = resp.header("Content-Type").orEmpty().lowercase()
            val path = resp.request.url.encodedPath
            val isGlyphOrStyle = path.endsWith(".json") || path.endsWith(".pbf")
            return !isGlyphOrStyle && (type.contains("xml") || type.contains("json") || type.startsWith("text/"))
        }
    }
}
