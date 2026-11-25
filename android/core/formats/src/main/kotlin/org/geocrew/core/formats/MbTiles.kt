package org.geocrew.core.formats

import org.geocrew.core.model.tiles.TileLayer
import org.geocrew.core.model.tiles.TileSource

/**
 * Offline raster base maps from MBTiles files (spec 1.3). An imported file is
 * unpacked into a `{z}/{x}/{y}.ext` folder on the device and drawn through a
 * `file://` URL, so the renderer treats it like any other tile source.
 *
 * Only the SQLite-free parts live here (metadata, row flipping, the resulting
 * [TileSource]); reading the database is done by `core:data`.
 */
object MbTiles {
    data class Meta(
        val name: String,
        /** File extension of the tiles: png, jpg or webp. Null when the file does not say. */
        val format: String?,
        val minZoom: Int,
        val maxZoom: Int,
        /** [west, south, east, north] in degrees. */
        val bounds: List<Double>?,
        val attribution: String,
    )

    private val raster = mapOf("png" to "png", "jpg" to "jpg", "jpeg" to "jpg", "webp" to "webp")

    /** Reads the `metadata` table rows. Missing or broken values fall back to safe defaults. */
    fun parseMeta(rows: Map<String, String>, fallbackName: String): Meta {
        val format = rows["format"]?.trim()?.lowercase()
        val min = rows["minzoom"]?.trim()?.toIntOrNull()?.coerceIn(0, 24) ?: 0
        val max = rows["maxzoom"]?.trim()?.toIntOrNull()?.coerceIn(min, 24) ?: 18
        return Meta(
            name = rows["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName,
            format = format?.let { raster[it] ?: it },
            minZoom = min,
            maxZoom = max,
            bounds = parseBounds(rows["bounds"]),
            attribution = rows["attribution"]?.let(::stripTags).orEmpty(),
        )
    }

    /** "w,s,e,n" in degrees, clamped to Web Mercator limits; null when absent or nonsense. */
    fun parseBounds(text: String?): List<Double>? {
        val v = text?.split(',')?.map { it.trim().toDoubleOrNull() ?: return null } ?: return null
        if (v.size != 4) return null
        val w = v[0].coerceIn(-180.0, 180.0)
        val e = v[2].coerceIn(-180.0, 180.0)
        val s = v[1].coerceIn(-85.0511, 85.0511)
        val n = v[3].coerceIn(-85.0511, 85.0511)
        if (w >= e || s >= n) return null
        return listOf(w, s, e, n)
    }

    /** Chinese reason the package cannot be used as a raster base map, or null when it can. */
    fun problem(meta: Meta): String? = when (meta.format) {
        null, "png", "jpg", "webp" -> null
        "pbf", "mvt" -> "这是矢量瓦片包，目前只支持栅格瓦片（png、jpg、webp）"
        else -> "不认识的瓦片格式：${meta.format}"
    }

    /** Recognises a tile by its first bytes, for files whose metadata omits the format. */
    fun sniff(tile: ByteArray): String? = when {
        tile.size >= 8 && tile[0] == 0x89.toByte() && tile[1] == 'P'.code.toByte() && tile[2] == 'N'.code.toByte() -> "png"
        tile.size >= 3 && tile[0] == 0xFF.toByte() && tile[1] == 0xD8.toByte() && tile[2] == 0xFF.toByte() -> "jpg"
        tile.size >= 12 && String(tile, 0, 4, Charsets.US_ASCII) == "RIFF" && String(tile, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        tile.size >= 2 && tile[0] == 0x1F.toByte() && tile[1] == 0x8B.toByte() -> "pbf" // gzipped vector tile
        else -> null
    }

    /** MBTiles stores TMS rows (origin at the bottom); XYZ counts from the top. */
    fun xyzRow(zoom: Int, tmsRow: Int): Int = (1 shl zoom) - 1 - tmsRow

    /** True when (zoom, column, row) is a valid tile address. */
    fun valid(zoom: Int, column: Int, row: Int): Boolean =
        zoom in 0..24 && column >= 0 && row >= 0 && column < (1 shl zoom) && row < (1 shl zoom)

    fun relativePath(zoom: Int, x: Int, y: Int, ext: String) = "$zoom/$x/$y.$ext"

    /** The tile source for a package unpacked into [dirUrl] (a `file://` URL without trailing slash). */
    fun source(id: String, meta: Meta, ext: String, dirUrl: String) = TileSource(
        id = id,
        name = meta.name,
        layers = listOf(TileLayer(url = "$dirUrl/{z}/{x}/{y}.$ext", minZoom = meta.minZoom, maxZoom = meta.maxZoom)),
        attribution = meta.attribution,
        minZoom = 0,
        // Let the map zoom past the package's last level; tiles are stretched.
        maxZoom = (meta.maxZoom + 3).coerceAtMost(22),
        custom = true,
        group = "离线",
        offline = true,
        bounds = meta.bounds,
    )

    private fun stripTags(s: String) = s.replace(Regex("<[^>]*>"), "").replace("&copy;", "©").trim()
}
