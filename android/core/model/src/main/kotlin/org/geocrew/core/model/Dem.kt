package org.geocrew.core.model

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Ground heights by position, from a DEM. Blocking: call it off the main thread. */
fun interface Elevation {
    /** Metres above sea level at each point, in order; null where there's no data. */
    fun at(points: List<LngLat>): List<Double?>
}

/**
 * Terrarium-encoded elevation tiles, as served by the public AWS Terrain
 * Tiles (Mapzen): 256-pixel web-mercator PNGs whose colour holds the height,
 * h = R·256 + G + B/256 − 32768 metres.
 */
object Terrarium {
    const val SIZE = 256

    /** Public tiles on AWS open data; about 30 m ground resolution at zoom 12 in China. */
    const val DEFAULT_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    /** Height of an ARGB pixel; null when it is transparent (no data). */
    fun decode(argb: Int): Double? {
        if ((argb ushr 24) == 0) return null
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r * 256.0 + g + b / 256.0 - 32768.0
    }

    /** The colour that encodes [h], for tests and tools that write tiles. */
    fun encode(h: Double): Int {
        val v = h + 32768.0
        val r = floor(v / 256).toInt().coerceIn(0, 255)
        val g = floor(v - r * 256).toInt().coerceIn(0, 255)
        val b = ((v - r * 256 - g) * 256).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Global pixel coordinates of [p] at zoom [z]: tile = floor(x / 256). */
    fun pixel(p: LngLat, z: Int): Pair<Double, Double> {
        val n = SIZE * (1 shl z).toDouble()
        val lat = p.lat.coerceIn(-85.05112878, 85.05112878) * PI / 180
        val x = (p.lng + 180) / 360 * n
        val y = (1 - ln(tan(lat) + 1 / kotlin.math.cos(lat)) / PI) / 2 * n
        return x to y
    }

    fun url(template: String, z: Int, x: Int, y: Int) =
        template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
}

/**
 * Bilinear heights from Terrarium tiles at one zoom. [tile] returns a tile's
 * 256×256 ARGB pixels row by row, or null when it can't be had; each tile is
 * asked for once. More than [maxTiles] distinct tiles in one call is refused,
 * so a slip of the finger can't start a download of half a province.
 */
class DemSampler(
    private val zoom: Int = 12,
    private val maxTiles: Int = 64,
    private val tile: (z: Int, x: Int, y: Int) -> IntArray?,
) : Elevation {
    private val tiles = HashMap<Long, IntArray?>()

    override fun at(points: List<LngLat>): List<Double?> {
        val px = points.map { Terrarium.pixel(it, zoom) }
        val needed = px.flatMap { (x, y) ->
            val i = floor(x - 0.5).toLong()
            val j = floor(y - 0.5).toLong()
            listOf(key(i, j), key(i + 1, j), key(i, j + 1), key(i + 1, j + 1))
        }.toSet()
        require(needed.size - tiles.keys.count { it in needed } <= maxTiles) { "范围太大，要下载的地形瓦片超过 $maxTiles 张" }
        return px.map { (x, y) -> bilinear(x - 0.5, y - 0.5) }
    }

    private fun key(px: Long, py: Long): Long {
        val tx = Math.floorDiv(px, Terrarium.SIZE.toLong())
        val ty = Math.floorDiv(py, Terrarium.SIZE.toLong())
        return (tx shl 32) or (ty and 0xFFFFFFFFL)
    }

    private fun value(px: Long, py: Long): Double? {
        val n = (1L shl zoom) * Terrarium.SIZE
        if (py < 0 || py >= n) return null
        val wx = Math.floorMod(px, n) // wraps across the antimeridian
        val tx = (wx / Terrarium.SIZE).toInt()
        val ty = (py / Terrarium.SIZE).toInt()
        val k = (tx.toLong() shl 32) or ty.toLong()
        val t = tiles.getOrPut(k) { tile(zoom, tx, ty)?.takeIf { it.size == Terrarium.SIZE * Terrarium.SIZE } } ?: return null
        return Terrarium.decode(t[(py % Terrarium.SIZE).toInt() * Terrarium.SIZE + (wx % Terrarium.SIZE).toInt()])
    }

    private fun bilinear(x: Double, y: Double): Double? {
        val i = floor(x).toLong()
        val j = floor(y).toLong()
        val fx = x - i
        val fy = y - j
        val v00 = value(i, j)
        val v10 = value(i + 1, j)
        val v01 = value(i, j + 1)
        val v11 = value(i + 1, j + 1)
        val all = listOf(v00 to (1 - fx) * (1 - fy), v10 to fx * (1 - fy), v01 to (1 - fx) * fy, v11 to fx * fy)
        val have = all.filter { it.first != null }
        val w = have.sumOf { it.second }
        if (have.isEmpty() || w < 1e-9) return have.firstOrNull()?.first
        return have.sumOf { it.first!! * it.second } / w
    }
}
