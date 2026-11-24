package org.geocrew.core.model.tiles

import org.geocrew.core.model.Bounds
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator tile addressing (XYZ, origin top left), as every built-in source uses. */
object TileMath {
    const val MAX_LAT = 85.0511287798

    fun column(lng: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lng.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun row(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val r = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180
        return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
    }

    /** North-west corner of a tile, as (lng, lat). */
    fun corner(zoom: Int, x: Int, y: Int): Pair<Double, Double> {
        val n = (1 shl zoom).toDouble()
        return x / n * 360.0 - 180.0 to atan(sinh(PI * (1 - 2 * y / n))) * 180 / PI
    }
}

/** The tiles of one zoom level that cover an area. */
data class TileRange(val zoom: Int, val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int) {
    val count: Long get() = (xMax - xMin + 1).toLong() * (yMax - yMin + 1)
}

/**
 * What it takes to keep an area of a base map on the device: the tiles of
 * every zoom level from [minZoom] to [maxZoom] covering [bounds], for each
 * of the source's [layers] (imagery plus labels is two requests per tile).
 */
data class TilePlan(val bounds: Bounds, val minZoom: Int, val maxZoom: Int, val layers: Int) {
    val ranges: List<TileRange> = (minZoom..maxZoom).map { z ->
        TileRange(
            z,
            TileMath.column(bounds.west, z), TileMath.column(bounds.east, z),
            TileMath.row(bounds.north, z), TileMath.row(bounds.south, z),
        )
    }

    /** Tile positions, one per (zoom, x, y); each is fetched once per layer. */
    val tiles: Long get() = ranges.sumOf { it.count }

    /** Requests to the provider. */
    val requests: Long get() = tiles * layers.coerceAtLeast(1)

    /** A rough size on disk; map tiles average 10–30 KB. */
    val estimatedBytes: Long get() = requests * AVERAGE_TILE_BYTES

    fun each(block: (zoom: Int, x: Int, y: Int) -> Unit) {
        for (r in ranges) for (x in r.xMin..r.xMax) for (y in r.yMin..r.yMax) block(r.zoom, x, y)
    }

    companion object {
        /** Upper bound for one download, so a slip of the zoom slider can't empty someone's daily quota. */
        const val MAX_REQUESTS = 20_000L
        const val AVERAGE_TILE_BYTES = 20L * 1024

        /** The deepest level from [minZoom] (up to [limit]) that keeps the download within [MAX_REQUESTS]; null if even [minZoom] doesn't fit. */
        fun deepestWithin(bounds: Bounds, minZoom: Int, limit: Int, layers: Int): Int? {
            var best: Int? = null
            for (z in minZoom..limit) {
                if (TilePlan(bounds, minZoom, z, layers).requests > MAX_REQUESTS) break
                best = z
            }
            return best
        }
    }
}

/** Whether a base map may be downloaded for offline use, and what to tell the user first. */
sealed interface BulkDownload {
    data class Allowed(val note: String) : BulkDownload
    data class Refused(val reason: String) : BulkDownload

    companion object {
        fun of(source: TileSource): BulkDownload = when {
            source.offline -> Refused("这已经是离线底图")
            source.layers.isEmpty() -> Refused("空白底图不需要下载")
            // tile.openstreetmap.org's usage policy forbids bulk downloading.
            source.layers.any { "openstreetmap.org" in hostOf(it.url) } ->
                Refused("OpenStreetMap 官方瓦片服务的使用政策不允许批量下载。可以改用天地图，或导入别处制作的 MBTiles 离线包")
            source.keyId == BuiltInSources.TIANDITU_KEY_ID ->
                Allowed("下载会计入你的天地图 Key 当天的调用量，请遵守天地图的服务条款，只下载工作需要的范围")
            else -> Allowed("请确认这个底图的提供方允许离线下载")
        }

        private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
    }
}
