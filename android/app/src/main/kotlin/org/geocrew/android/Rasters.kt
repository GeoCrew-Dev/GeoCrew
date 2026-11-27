package org.geocrew.android

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.geocrew.nativelib.GeoCrewNative
import org.geocrew.storage.NativeData
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * A raster of the open project as project.current lists it: its id and name,
 * its file, whether that file has gone, how it shows, and what the probe made
 * of it ([info]: bands, palette, CRS, pixel size, WGS84 extent). [render] says
 * how a single band is drawn: as it is, its hillshade, or coloured by height.
 */
data class RasterInfo(
    val id: String,
    val name: String,
    val file: File,
    val missing: Boolean,
    val visible: Boolean,
    val opacity: Float,
    val info: JsonObject,
    val render: String = RENDER_DEFAULT,
) {
    /** Where it is: west, south, east, north in WGS84 degrees; null when the probe did not say. */
    val extent: List<Double>? get() = (info["extent"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 4 }

    /** Whether it has a single band of values (a DEM, say), which can be shaded or coloured by height. */
    val single: Boolean
        get() = (info["bands"] as? JsonArray)?.size == 1 && (info["palette"] as? JsonPrimitive)?.booleanOrNull != true
}

/** How a single-band raster can be drawn: as it is (grey), its hillshade, or coloured by height. */
const val RENDER_DEFAULT = "default"
const val RENDER_HILLSHADE = "hillshade"
const val RENDER_RELIEF = "relief"

fun listedRasters(about: JsonObject): List<RasterInfo> = about["rasters"]?.jsonArray.orEmpty().mapNotNull { r ->
    val o = r as? JsonObject ?: return@mapNotNull null
    fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
    RasterInfo(
        id = text("id") ?: return@mapNotNull null,
        name = text("name").orEmpty(),
        file = File(text("path").orEmpty()),
        missing = (o["missing"] as? JsonPrimitive)?.booleanOrNull == true,
        visible = text("visible") != "false",
        opacity = text("opacity")?.toFloatOrNull() ?: 1f,
        info = o["info"] as? JsonObject ?: JsonObject(emptyMap()),
        render = text("render") ?: RENDER_DEFAULT,
    )
}

/**
 * The project's rasters as Web Mercator tiles, made by GDAL from the file
 * itself as MapLibre asks for them (ADR 0006): an OkHttp interceptor answers
 * MapLibre's requests for geocrew.tiles and lets every other request through.
 * A raster's tiles name the version of its file, so MapLibre's cache does not
 * show an old one.
 */
object RasterTiles : Interceptor {
    private const val HOST = "geocrew.tiles"

    /** The client MapLibre makes all its requests with: one for the app, answering raster tiles itself. */
    val client: OkHttpClient by lazy { OkHttpClient.Builder().addInterceptor(this).build() }

    // What each raster draws, by raster id: the file, or the pipeline that makes it 8-bit grey, RGB or RGBA.
    private val drawn = ConcurrentHashMap<String, File>()

    /** The tile address of raster [id] at [version], as MapLibre's sources take it. */
    fun url(id: String, version: String) = "https://$HOST/raster/$id/$version/{z}/{x}/{y}.png"

    /** Raster [id]'s tiles come from [file] from now on. */
    fun draw(id: String, file: File) {
        drawn[id] = file
    }

    // A failure is an answer, never an exception: one thrown here would stop OkHttp's thread (M1 spike).
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)
        fun answer(code: Int, png: ByteArray? = null) = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "No tile")
            .header("Cache-Control", "no-store")
            .body((png ?: ByteArray(0)).toResponseBody("image/png".toMediaType()))
            .build()
        return try {
            // raster/<id>/<version>/<z>/<x>/<y>.png
            val parts = request.url.pathSegments
            val file = drawn[parts.getOrNull(1)] ?: return answer(404)
            val z = parts.getOrNull(3)?.toIntOrNull() ?: return answer(404)
            val x = parts.getOrNull(4)?.toIntOrNull() ?: return answer(404)
            val y = parts.getOrNull(5)?.removeSuffix(".png")?.toIntOrNull() ?: return answer(404)
            if (NativeData.dir == null) return answer(503) // GDAL is not set up yet
            val png = GeoCrewNative.renderTile(file.path, z, x, y)
            if (png.isEmpty()) answer(204) else answer(200, png)
        } catch (e: Throwable) {
            answer(500)
        }
    }

    /**
     * What the map draws for [raster]: the file itself when GDAL's tiles of
     * it come out right (8-bit grey, RGB or RGBA), else a file made once in
     * the app's own folder that turns it so: a palette into RGB, other types
     * (16-bit, floats) stretched to 8 bits; a single band shaded (a GDAL
     * pipeline that shades each tile as it is drawn) or coloured by height
     * when its [RasterInfo.render] says so. Blocks: call it off the main thread.
     */
    fun fileFor(context: Context, raster: RasterInfo): File {
        val bands = (raster.info["bands"] as? JsonArray).orEmpty().map { it.jsonObject }
        val palette = (raster.info["palette"] as? JsonPrimitive)?.booleanOrNull == true
        val types = bands.mapNotNull { (it["type"] as? JsonPrimitive)?.contentOrNull }
        val how = when {
            raster.single && raster.render == RENDER_HILLSHADE -> "hillshade"
            raster.single && raster.render == RENDER_RELIEF -> "relief"
            palette -> "color-map"
            types.any { it != "Byte" } -> "scale"
            else -> return raster.file
        }
        val dir = File(context.noBackupFilesDir, "raster-display").apply { mkdirs() }
        val current = version(raster.file)
        // Each a GDAL pipeline (GDALG) that GDAL runs as the tiles are drawn: a VRT made in a running app was
        // sometimes not drawn until the app started again, a pipeline always is.
        val made = File(dir, "${raster.id}-$how-$current.gdalg.json")
        if (!made.exists()) {
            // What was made for an earlier version of the file goes. What was made for another way of drawing this
            // version stays: the native library keeps what it opened by path, so a path is never made twice.
            dir.listFiles { f -> f.name.startsWith("${raster.id}-") && !(f.name.contains("-$current.") && f.name.endsWith(".gdalg.json")) }
                ?.forEach { it.delete() }
            val args = when (how) {
                "color-map" -> arrayOf("raster", "color-map", "--of", "GDALG", raster.file.path, made.path)
                // Lit from several directions, so no slope is lost in a shadow; GDAL scales degrees to metres itself.
                "hillshade" -> arrayOf("raster", "hillshade", "--of", "GDALG", "--variant", "multidirectional", raster.file.path, made.path)
                "relief" -> {
                    val ramp = File(dir, "relief.txt").apply { writeText(RELIEF_RAMP) }
                    arrayOf("raster", "color-map", "--color-map", ramp.path, "--add-alpha", "--of", "GDALG", raster.file.path, made.path)
                }
                else -> arrayOf("raster", "scale", "--of", "GDALG", "--ot", "Byte", raster.file.path, made.path)
            }
            // GDAL may raise a warning (a colour for no data in a raster without it) and still write the file: the file is what counts.
            val failure = runCatching { GeoCrewNative.run(args) }.exceptionOrNull()
            if (!made.exists()) {
                // Shading or colouring that GDAL could not do falls back to the plain way of drawing it.
                if (raster.render != RENDER_DEFAULT) return fileFor(context, raster.copy(render = RENDER_DEFAULT))
                throw failure ?: IllegalStateException("GDAL made no ${made.name}")
            }
        }
        return made
    }

    // Heights from the lowest to the highest of the raster: green lowlands, yellow, brown, and white at the top.
    private val RELIEF_RAMP = listOf(
        "0% 0 97 71", "20% 16 122 47", "40% 232 215 125", "60% 161 67 0", "80% 130 30 30", "100% 255 255 255", "nv 0 0 0 0",
    ).joinToString("\n", postfix = "\n")

    /** The version of [file] the map shows: its size and when it last changed. */
    fun version(file: File): String = "${file.length().toString(36)}-${file.lastModified().toString(36)}"
}

// The zoom levels where a raster of this pixel size shows: from where it is a few pixels across to where
// a tile's pixel is as fine as its own (MapLibre stretches those beyond); 0 to 22 without a pixel size.
private fun zoomsOf(raster: RasterInfo): Pair<Float, Float> {
    val pixel = (raster.info["pixel"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.minOrNull()
    val extent = raster.extent
    if (pixel == null || pixel <= 0 || extent == null) return 0f to 22f
    val latitude = (extent[1] + extent[3]) / 2
    val geographic = (raster.info["crs"] as? JsonObject)?.get("geographic")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
    val metres = if (geographic) pixel * 111_320 * cos(latitude * PI / 180) else pixel
    // At zoom z a Web Mercator pixel is 156543 m × cos(latitude) / 2^z.
    val native = ln(156_543.034 * cos(latitude * PI / 180) / metres) / ln(2.0)
    val max = ceil(native).coerceIn(0.0, 22.0)
    val min = (floor(native) - 8).coerceIn(0.0, max)
    return min.toFloat() to max.toFloat()
}

/**
 * Puts [rasters] on the map under the layer [below] (the project's lines and
 * areas), the first on top, each from the file [files] gives for it, shown or
 * hidden and as opaque as the project says; sources of rasters no longer
 * listed go. A raster shown already only has its display changed.
 */
fun showRasters(style: Style, rasters: List<RasterInfo>, files: Map<String, File>, below: String) {
    val wanted = rasters.filter { files.containsKey(it.id) }.map { it to "$RASTER_PREFIX${it.id}-${RasterTiles.version(files.getValue(it.id))}" }
    val keep = wanted.map { it.second }.toSet()
    for (layer in style.layers.filter { it.id.startsWith(RASTER_PREFIX) && it.id !in keep }) {
        style.removeLayer(layer)
    }
    for (source in style.sources.filter { it.id.startsWith(RASTER_PREFIX) && it.id !in keep }) {
        style.removeSource(source)
    }
    // From the bottom up, each new one just under [below], so the first ends on top.
    for ((raster, id) in wanted.reversed()) {
        val properties = arrayOf(
            PropertyFactory.rasterOpacity(raster.opacity),
            PropertyFactory.visibility(if (raster.visible) Property.VISIBLE else Property.NONE),
        )
        val shown = style.getLayer(id)
        if (shown != null) {
            shown.setProperties(*properties)
            continue
        }
        val file = files.getValue(raster.id)
        RasterTiles.draw(raster.id, file)
        val (min, max) = zoomsOf(raster)
        val tiles = TileSet("2.2.0", RasterTiles.url(raster.id, RasterTiles.version(file))).apply {
            raster.extent?.let { (west, south, east, north) -> setBounds(west.toFloat(), south.toFloat(), east.toFloat(), north.toFloat()) }
            setMinZoom(min)
            setMaxZoom(max)
        }
        style.addSource(RasterSource(id, tiles, 256))
        style.addLayerBelow(RasterLayer(id, id).withProperties(*properties), below)
    }
}

// Map sources and layers of rasters: the prefix, then the raster's id and its file's version.
private const val RASTER_PREFIX = "geocrew-raster-"
