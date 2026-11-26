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

    /**
     * The Tianditu maps a project can show under its layers, each a base and
     * its labels: imagery (img, cia), the vector map (vec, cva) and terrain
     * (ter, cta). Tianditu's terrain goes to zoom 14.
     */
    enum class Tianditu(val base: String, val labels: String, val maxZoom: Int) {
        IMAGERY("img", "cia", 18),
        VECTOR("vec", "cva", 18),
        TERRAIN("ter", "cta", 14),
        ;

        companion object {
            fun of(id: String?): Tianditu = entries.firstOrNull { it.base == id } ?: IMAGERY
        }
    }

    /** Every Tianditu map with its labels, all hidden: the map shows the chosen one (layers base-img, labels-img, ...). */
    fun tianditu(key: String): String {
        val sources = Tianditu.entries.flatMap { t ->
            listOf(
                """"base-${t.base}":{"type":"raster","tiles":[${tiles(t.base, key)}],"tileSize":256,"maxzoom":${t.maxZoom}}""",
                """"labels-${t.base}":{"type":"raster","tiles":[${tiles(t.labels, key)}],"tileSize":256,"maxzoom":${t.maxZoom}}""",
            )
        }
        val layers = Tianditu.entries.map { t -> """{"id":"base-${t.base}","type":"raster","source":"base-${t.base}","layout":{"visibility":"none"}}""" } +
            Tianditu.entries.map { t -> """{"id":"labels-${t.base}","type":"raster","source":"labels-${t.base}","layout":{"visibility":"none"}}""" }
        return """{"version":8,"sources":{${sources.joinToString(",")}},""" +
            """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#f2efe9"}},${layers.joinToString(",")}]}"""
    }

    /** No basemap: until a key is entered. */
    const val BLANK = """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#f2efe9"}}]}"""
}
