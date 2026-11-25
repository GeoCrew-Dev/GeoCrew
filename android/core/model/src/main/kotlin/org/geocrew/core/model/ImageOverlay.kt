package org.geocrew.core.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * A picture laid over the map, such as a scanned geological map or a sketch
 * plan, pinned by its four corners. It belongs to the project (kept in
 * [ProjectConfig.overlays]); the image itself is a file on this device, carried
 * in project packages. A leader can share one with the workspace
 * ([shared], protocol v0 §2.2 key `overlays`); members then get it read-only.
 */
@Serializable
data class ImageOverlay(
    val id: String,
    val name: String,
    /** File name in the app's overlay folder. */
    val file: String,
    /** Size of the stored image in pixels. */
    val width: Int,
    val height: Int,
    /** Corners as [lng, lat]: top left, top right, bottom right, bottom left. */
    val corners: List<List<Double>>,
    val opacity: Float = 0.6f,
    val visible: Boolean = true,
    /** How the corners were set, shown so the user knows how far to trust them. */
    val placement: Placement = Placement.VIEW,
    /** Control points the corners were last fitted to by hand (see [OverlayAlign]); 0 when not aligned since. */
    val controlPoints: Int = 0,
    /** Shared with the workspace: a leader's to publish, a member's from the leader. */
    val shared: Boolean = false,
) {
    @Serializable
    enum class Placement { VIEW, WORLD_FILE }

    /** The image's SHA-256, which is also its attachment hash on the workspace server. */
    val sha256: String get() = file.substringBefore('.')
    val mimeType: String get() = if (file.endsWith(".png")) "image/png" else "image/jpeg"

    val quad: List<LngLat> get() = corners.map { LngLat(it[0], it[1]) }

    companion object {
        /** Stored overlay images are named by the SHA-256 of their bytes. */
        private val FILE = Regex("^[0-9a-f]{64}\\.(jpg|png)$")

        fun isFileName(name: String) = FILE.matches(name)

        fun corners(quad: List<LngLat>): List<List<Double>> = quad.map { listOf(it.lng, it.lat) }

        /**
         * Corners that show a [width] × [height] image as large as fits in
         * [view] at [fill] of its size, centred and upright on the screen,
         * keeping its proportions on the Web Mercator map.
         */
        fun fit(view: Bounds, width: Int, height: Int, fill: Double = 0.9): List<LngLat> {
            val x0 = mx(view.west); val x1 = mx(view.east)
            val y0 = my(view.south); val y1 = my(view.north)
            val vw = x1 - x0; val vh = y1 - y0
            val aspect = width.toDouble() / height
            var w = vw * fill
            var h = w / aspect
            if (h > vh * fill) { h = vh * fill; w = h * aspect }
            val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
            fun at(x: Double, y: Double) = LngLat(lng(x), lat(y))
            return listOf(at(cx - w / 2, cy + h / 2), at(cx + w / 2, cy + h / 2), at(cx + w / 2, cy - h / 2), at(cx - w / 2, cy - h / 2))
        }

        // Web Mercator in radians-of-longitude units; only ratios matter here.
        private fun mx(lng: Double) = lng * PI / 180
        private fun my(lat: Double) = ln(tan(PI / 4 + lat.coerceIn(-85.0, 85.0) * PI / 360))
        private fun lng(x: Double) = x * 180 / PI
        private fun lat(y: Double) = (2 * atan(exp(y)) - PI / 2) * 180 / PI
    }
}

/**
 * ESRI world files (.jgw, .pgw, .tfw, .wld): six lines A, D, B, E, C, F that
 * map pixel (col, row) to map coordinates X = A·col + B·row + C,
 * Y = D·col + E·row + F, where (C, F) is the centre of the top-left pixel.
 * Coordinates are either degrees or, as on most Chinese scanned maps,
 * Gauss-Krüger metres with the zone number in front of Y (easting).
 */
object WorldFile {
    data class Affine(val a: Double, val d: Double, val b: Double, val e: Double, val c: Double, val f: Double) {
        /** Map coordinates of pixel edge point (col, row): (0, 0) is the image's top-left corner. */
        fun at(col: Double, row: Double): Pair<Double, Double> {
            val c0 = c - (a + b) / 2
            val f0 = f - (d + e) / 2
            return a * col + b * row + c0 to d * col + e * row + f0
        }
    }

    /** What the coordinates are. */
    sealed interface Crs {
        data object Degrees : Crs
        /** Gauss-Krüger with the zone in front of X; [width] is 3 or 6. */
        data class Plane(val width: Int, val ellipsoid: Ellipsoid = Ellipsoid.CGCS2000) : Crs
    }

    fun parse(text: String): Affine? {
        val n = text.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toDoubleOrNull() ?: return null }
        if (n.size != 6) return null
        val a = Affine(n[0], n[1], n[2], n[3], n[4], n[5])
        return if (a.a == 0.0 && a.b == 0.0 || a.d == 0.0 && a.e == 0.0) null else a
    }

    /**
     * Guesses the coordinates from their size: degrees when they look like
     * longitude and latitude, Gauss-Krüger when X carries a zone number that
     * only one zone width uses over China (6° zones 13–23, 3° zones 24–45).
     * Null when it can't tell, e.g. plane coordinates without a zone number.
     */
    fun guess(w: Affine): Crs? {
        if (w.c in -180.0..180.0 && w.f in -90.0..90.0 && kotlin.math.abs(w.a) < 1 && kotlin.math.abs(w.e) < 1) return Crs.Degrees
        if (w.c < 1_000_000) return null
        return when (floor(w.c / 1_000_000).toInt()) {
            in 13..23 -> Crs.Plane(6)
            in 24..45 -> Crs.Plane(3)
            else -> null
        }
    }

    /** The four corners (top left, top right, bottom right, bottom left) of a [width] × [height] image. */
    fun corners(w: Affine, width: Int, height: Int, crs: Crs): List<LngLat>? = runCatching {
        listOf(0.0 to 0.0, width.toDouble() to 0.0, width.toDouble() to height.toDouble(), 0.0 to height.toDouble()).map { (col, row) ->
            val (x, y) = w.at(col, row)
            when (crs) {
                Crs.Degrees -> LngLat(x, y)
                is Crs.Plane -> GaussKruger.unproject(x = y, y = x, width = crs.width, ellipsoid = crs.ellipsoid)
            }
        }
    }.getOrNull()
}
