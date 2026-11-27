package org.geocrew.android

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.action.Outcome
import org.geocrew.core.project.Templates
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource

// Names of our own: the basemap's style has layers of its own (Tianditu's "labels", say).
internal const val POINT_SOURCE = "geocrew-points"
internal const val POINT_LABELS = "geocrew-point-labels"

/** Failures said in the user's language when their code is known. */
internal class FailureTexts(val unsaved: String, val failed: String, val start: String, val empty: String = failed, val crs: String = failed) {
    fun of(outcome: Outcome): String = when (outcome) {
        is Outcome.Failed -> when (outcome.code) {
            "unsaved" -> unsaved
            "crs" -> crs.format(outcome.message)
            "empty" -> empty.format(outcome.message)
            AppGraph.START_FAILED -> start.format(outcome.message)
            else -> failed.format(outcome.message)
        }
        else -> failed.format(outcome.toString())
    }
}

@Composable
internal fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

internal fun fieldNames(collection: JsonObject?): List<String> =
    collection?.get("fields")?.jsonArray.orEmpty().mapNotNull { (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull }

/**
 * The field that names a layer's features (points D001 and on), under the name
 * a template gave it in the project's language: 点名 for points, 名称 for lines,
 * areas and tracks, Name in English.
 */
internal fun nameField(collection: JsonObject?): String? {
    val names = fieldNames(collection)
    return Templates.all.firstNotNullOfOrNull { it.column(Templates.NAME, names) } ?: names.firstOrNull { it in EARLIER_NAME_FIELDS }
}

// The number field of the first point template, in projects made before it became the name field.
private val EARLIER_NAME_FIELDS = setOf("点号", "Number")

internal fun formFields(collection: JsonObject?): List<FormField> = collection?.get("fields")?.jsonArray.orEmpty().map {
    val f = it.jsonObject
    FormField((f["name"] as JsonPrimitive).content, (f["type"] as JsonPrimitive).content)
}

/** A point feature's longitude and latitude, or null when it is not a point (a line, an area, no geometry). */
internal fun coordinatesOf(feature: JsonObject): Pair<Double, Double>? {
    val geometry = feature["geometry"] as? JsonObject ?: return null
    if ((geometry["type"] as? JsonPrimitive)?.contentOrNull != "Point") return null
    val xy = geometry["coordinates"] as? JsonArray ?: return null
    val x = (xy.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
    val y = (xy.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
    return x to y
}

/** Every position of a feature's geometry (a point's one, a line's vertices, an area's rings), as longitude and latitude. */
internal fun positionsOf(feature: JsonObject): List<LatLng> = buildList {
    fun walk(e: JsonElement?) {
        val array = e as? JsonArray ?: return
        val x = (array.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
        val y = (array.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
        if (x != null && y != null) add(LatLng(y, x)) else array.forEach(::walk)
    }
    walk((feature["geometry"] as? JsonObject)?.get("coordinates"))
}

/** Moves the camera to show the features: one point close up, several (or a line, an area) all at once. */
internal fun fit(map: MapLibreMap, features: JsonArray, density: Float) {
    val points = features.flatMap { f -> (f as? JsonObject)?.let(::positionsOf).orEmpty() }
    if (points.isEmpty()) return
    // Points that span no distance (all in one place) are shown like a single one:
    // a box of no size would zoom in past the basemap's last level.
    val spread = points.maxOf { it.latitude } - points.minOf { it.latitude } + points.maxOf { it.longitude } - points.minOf { it.longitude }
    if (spread < 1e-6) {
        map.cameraPosition = CameraPosition.Builder().target(points.first()).zoom(15.0).build()
    } else {
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.fromLatLngs(points), (64 * density).toInt()))
    }
}

/** Hands a file to another app through the system's share sheet. */
internal fun share(context: Context, file: File, title: String) = shareAll(context, listOf(file), title)

/**
 * The map as it is now, at full size, as a PNG in [dir] named after [project]
 * and the time, like the exports, with [attribution] (the basemap's credit) in
 * its corner; null when MapLibre gives no picture or it cannot be written.
 */
internal suspend fun saveScreenshot(map: MapLibreMap, dir: File, project: String, attribution: String?, density: Float): File? {
    val shot = suspendCancellableCoroutine<Bitmap?> { continuation ->
        map.snapshot { if (continuation.isActive) continuation.resume(it) }
    } ?: return null
    return withContext(Dispatchers.IO) { runCatching { writeScreenshot(shot, dir, project, attribution, density) }.getOrNull() }
}

private fun writeScreenshot(snapshot: Bitmap, dir: File, project: String, attribution: String?, density: Float): File? {
    val shot = if (attribution == null) snapshot else snapshot.copy(Bitmap.Config.ARGB_8888, true).also { picture ->
        // The credit as the map shows it: small, at the bottom right, readable on imagery.
        val canvas = android.graphics.Canvas(picture)
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * density; color = android.graphics.Color.WHITE }
        val halo = android.graphics.Paint(fill).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 3 * density; color = android.graphics.Color.BLACK }
        val x = picture.width - fill.measureText(attribution) - 8 * density
        val y = picture.height - 8 * density
        canvas.drawText(attribution, x, y, halo)
        canvas.drawText(attribution, x, y, fill)
    }
    run {
        dir.mkdirs()
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var out = File(dir, "$project-$stamp.png")
        var n = 2
        while (out.exists()) out = File(dir, "$project-$stamp-${n++}.png")
        // Written under another name first: a picture cut short never looks finished.
        val part = File(out.path + ".part")
        part.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return if (part.renameTo(out)) out else null
    }
}

/** Keeps a small picture of the map as it is, for the project's card on the home screen. */
internal suspend fun saveThumbnail(map: MapLibreMap, file: File) {
    val shot = suspendCancellableCoroutine<Bitmap?> { continuation ->
        map.snapshot { if (continuation.isActive) continuation.resume(it) }
    } ?: return
    withContext(Dispatchers.IO) {
        val width = 640
        val small = shot.scale(width, (width.toFloat() * shot.height / shot.width).toInt())
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        part.renameTo(file)
    }
}

/** Where a new point goes: the middle of the map. A white edge keeps it plain on dark imagery. */
@Composable
internal fun Crosshair(modifier: Modifier) {
    Canvas(modifier.size(56.dp)) {
        val red = Color(0xFFE53935)
        val gap = 7.dp.toPx() // the spot itself stays clear
        val arms = listOf(
            Offset(0f, center.y) to Offset(center.x - gap, center.y),
            Offset(center.x + gap, center.y) to Offset(size.width, center.y),
            Offset(center.x, 0f) to Offset(center.x, center.y - gap),
            Offset(center.x, center.y + gap) to Offset(center.x, size.height),
        )
        for ((from, to) in arms) drawLine(Color.White, from, to, 5.dp.toPx(), StrokeCap.Round)
        for ((from, to) in arms) drawLine(red, from, to, 2.5.dp.toPx(), StrokeCap.Round)
        drawCircle(Color.White, 3.5.dp.toPx(), center)
        drawCircle(red, 2.dp.toPx(), center)
    }
}

/**
 * The map's tile cache: MapLibre keeps the tiles it has fetched as their
 * servers' cache headers allow, so maps seen before show again without the
 * network, as in a browser. Only the size is GeoCrew's: [DEFAULT_MB] unless
 * the user picks another in the settings. Nothing is fetched ahead of time
 * and nothing is kept against what a server says (ADR 0015).
 */
object MapCache {
    const val DEFAULT_MB = 500

    /** The sizes the settings offer, in MB. */
    val CHOICES = listOf(100, 500, 1024, 2048)

    /** Sets how much the cache may hold; MapLibre lets older tiles go when it is full. */
    fun limit(context: Context, mb: Int) {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(mb * 1024L * 1024L, quietly)
    }

    /** How much the cache holds now, in bytes. */
    fun used(context: Context): Long = File(FileSource.getResourcesCachePath(context), "mbgl-offline.db").length()

    /** Empties the cache; false when MapLibre could not. */
    suspend fun clear(context: Context): Boolean = suspendCancellableCoroutine { done ->
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context).clearAmbientCache(
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    if (done.isActive) done.resume(true)
                }

                override fun onError(message: String) {
                    if (done.isActive) done.resume(false)
                }
            },
        )
    }

    private val quietly = object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = Unit

        override fun onError(message: String) = Unit
    }
}

/** A MapView that follows the screen's lifecycle, its tile cache as big as [cacheMb]. */
@Composable
internal fun rememberMapView(cacheMb: Int = MapCache.DEFAULT_MB): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapCache.limit(context, cacheMb)
        HttpRequestUtil.setLogEnabled(false) // MapLibre logs the URLs of failed requests, and Tianditu's carry the key
        // The project's rasters are drawn by GDAL as MapLibre asks for their tiles; other requests go out as before.
        HttpRequestUtil.setOkHttpClient(RasterTiles.client)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

internal suspend fun MapView.awaitMap(): MapLibreMap =
    suspendCancellableCoroutine { continuation -> getMapAsync { if (continuation.isActive) continuation.resume(it) } }

/** 1e-7 degrees is about a centimetre. */
internal fun round7(degrees: Double): Double = (degrees * 1e7).roundToLong() / 1e7
