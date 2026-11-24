package org.geocrew.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.geocrew.core.model.tiles.BuiltInSources
import org.geocrew.core.model.tiles.TileTemplates
import java.net.HttpURLConnection
import java.net.URL

/** Result of trying one tile with a key. */
sealed interface KeyCheck {
    data object Ok : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

/** Fetches a single low-zoom Tianditu tile to see whether a key works. */
internal object TileKeyChecker {
    suspend fun checkTianditu(key: String, userAgent: String): KeyCheck = withContext(Dispatchers.IO) {
        if (!BuiltInSources.isPlausibleTiandituKey(key)) {
            return@withContext KeyCheck.Failed("Key 格式不对：天地图 Key 是 32 位字母和数字。")
        }
        val layer = BuiltInSources.TIANDITU_VECTOR.layers.first()
        val url = TileTemplates.resolve(layer, key.trim()).urls.first()
            .replace("{z}", "1").replace("{x}", "1").replace("{y}", "0")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val code = conn.responseCode
            val type = conn.contentType.orEmpty()
            when {
                code == 200 && type.startsWith("image") -> KeyCheck.Ok
                code == 403 || code == 401 || code == 418 ->
                    KeyCheck.Failed("天地图拒绝了这个 Key（HTTP $code）。请确认 Key 类型为「服务端」、未被停用，且当日调用未超限。")
                code == 429 -> KeyCheck.Failed("调用太频繁或今日已达上限（HTTP 429），稍后再试。")
                else -> KeyCheck.Failed("天地图返回了意外的结果（HTTP $code，$type）。Key 可能无效。")
            }
        } catch (e: java.io.IOException) {
            KeyCheck.Failed("连不上天地图：${e.message ?: e.javaClass.simpleName}。请检查网络。")
        } finally {
            conn.disconnect()
        }
    }
}
