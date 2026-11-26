package org.geocrew.android

import android.content.Context
import java.io.File

/**
 * The user's own Tianditu key (ADR 0015: users bring their keys). It lives in
 * the app's own files, out of backups; it is never logged, and goes only to
 * tianditu.gov.cn, as the `tk` of each tile request.
 */
object TiandituKey {
    private fun file(context: Context) = File(context.noBackupFilesDir, "keys/tianditu")

    fun read(context: Context): String? =
        file(context).takeIf { it.isFile }?.readText()?.removePrefix("\uFEFF")?.trim()?.takeIf(::isValid)

    fun write(context: Context, key: String) {
        require(isValid(key)) { "not a Tianditu key" }
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(key)
    }

    /** Tianditu keys are 32 hexadecimal digits. */
    fun isValid(key: String): Boolean = KEY.matches(key)

    private val KEY = Regex("[0-9a-fA-F]{32}")
}

/** MapLibre styles for the basemaps (ADR 0006, 0015). */
object MapStyles {
    /** One Tianditu Web Mercator tile host; tiles are spread over t0 to t7. */
    private const val TILE =
        "https://t@N@.tianditu.gov.cn/@L@_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=@L@&STYLE=default" +
            "&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=@TK@"

    private fun tiles(layer: String, key: String) =
        (0..7).joinToString(",") { "\"" + TILE.replace("@N@", "$it").replace("@L@", layer).replace("@TK@", key) + "\"" }

    /** Tianditu imagery with its labels. */
    fun tiandituImagery(key: String): String =
        """{"version":8,"sources":{""" +
            """"base":{"type":"raster","tiles":[${tiles("img", key)}],"tileSize":256,"maxzoom":18},""" +
            """"labels":{"type":"raster","tiles":[${tiles("cia", key)}],"tileSize":256,"maxzoom":18}},""" +
            """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#f2efe9"}},""" +
            """{"id":"base","type":"raster","source":"base"},{"id":"labels","type":"raster","source":"labels"}]}"""

    /** No basemap: until a key is entered. */
    const val BLANK = """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#f2efe9"}}]}"""
}
