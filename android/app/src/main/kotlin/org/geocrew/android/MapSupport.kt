package org.geocrew.android

import android.content.Context
import android.content.Intent
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
import androidx.core.content.FileProvider
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
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

/** The field that names points (D001 and on), under the name the template gave it in the project's language. */
internal fun nameField(collection: JsonObject?): String? {
    val names = fieldNames(collection)
    return Templates.FIELD_POINT.column(Templates.NAME, names) ?: names.firstOrNull { it in EARLIER_NAME_FIELDS }
}

// The number field of the first point template, in projects made before it became the name field.
private val EARLIER_NAME_FIELDS = setOf("点号", "Number")

internal fun formFields(collection: JsonObject?): List<FormField> = collection?.get("fields")?.jsonArray.orEmpty().map {
    val f = it.jsonObject
    FormField((f["name"] as JsonPrimitive).content, (f["type"] as JsonPrimitive).content)
}

/** A point feature's longitude and latitude, or null when it has no point geometry. */
internal fun coordinatesOf(feature: JsonObject): Pair<Double, Double>? {
    val xy = (feature["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return null
    return if (xy.size < 2) null else xy[0].jsonPrimitive.double to xy[1].jsonPrimitive.double
}

/** Moves the camera to show the features: one point close up, several all at once. */
internal fun fit(map: MapLibreMap, features: JsonArray, density: Float) {
    val points = features.mapNotNull { f -> (f as? JsonObject)?.let(::coordinatesOf)?.let { (x, y) -> LatLng(y, x) } }
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

/** Hands an exported file to another app through the system's share sheet. */
internal fun share(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val type = if (file.extension == "gpkg") "application/geopackage+sqlite3" else "application/vnd.google-earth.kml+xml"
    val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title))
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

/** A MapView that follows the screen's lifecycle. */
@Composable
internal fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        HttpRequestUtil.setLogEnabled(false) // MapLibre logs the URLs of failed requests, and Tianditu's carry the key
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
