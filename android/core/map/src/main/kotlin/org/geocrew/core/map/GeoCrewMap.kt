package org.geocrew.core.map

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.geocrew.core.model.Bounds
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.tiles.TileKeys
import org.geocrew.core.model.tiles.TileSource
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Camera state shared between the map and the screen: the screen reads the
 * centre (for the crosshair) and asks the map to move.
 */
@Stable
class MapCameraState(center: LngLat = LngLat(105.0, 35.0), zoom: Double = 3.5) {
    var center by mutableStateOf(center)
        internal set
    var zoom by mutableStateOf(zoom)
        internal set
    var bearing by mutableStateOf(0.0)
        internal set

    internal var map: MapLibreMap? = null
    private var pending: ((MapLibreMap) -> Unit)? = null

    internal fun attach(m: MapLibreMap) {
        map = m
        pending?.invoke(m)
        pending = null
    }

    private fun whenReady(block: (MapLibreMap) -> Unit) {
        val m = map
        if (m != null) block(m) else pending = block
    }

    fun animateTo(target: LngLat, zoom: Double? = null) = whenReady { m ->
        val z = zoom ?: m.cameraPosition.zoom
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.lat, target.lng), z), 600)
    }

    fun fit(bounds: Bounds, paddingPx: Int = 120) = whenReady { m ->
        if (bounds.west == bounds.east && bounds.south == bounds.north) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(bounds.south, bounds.west), 16.0), 600)
        } else {
            val b = LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west)
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, paddingPx), 600)
        }
    }

    /** Moves without animation, e.g. to restore where a project was left. */
    fun jumpTo(target: LngLat, zoom: Double, bearing: Double = 0.0) {
        center = target
        this.zoom = zoom
        this.bearing = bearing
        whenReady { m ->
            m.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(target.lat, target.lng)).zoom(zoom).bearing(bearing).build(),
                ),
            )
        }
    }

    /** The area on screen, or null before the map is ready. */
    fun visibleBounds(): org.geocrew.core.model.Bounds? = map?.projection?.visibleRegion?.latLngBounds?.let { b ->
        org.geocrew.core.model.Bounds(b.southWest.longitude, b.southWest.latitude, b.northEast.longitude, b.northEast.latitude)
    }

    /**
     * The map as drawn now, for printing, with the screen position of any
     * place on it; null before the map is ready. Call on the main thread.
     * Text the app draws above the map ([MapLabel]) is not in the picture.
     */
    suspend fun capture(): MapCapture? {
        val m = map ?: return null
        val bounds = visibleBounds() ?: return null
        val bitmap = kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { c ->
            m.snapshot { b -> if (c.isActive) c.resumeWith(Result.success(b)) }
        } ?: return null
        val projection = m.projection
        return MapCapture(bitmap, bounds, m.cameraPosition.bearing) { p ->
            val pt = projection.toScreenLocation(LatLng(p.lat, p.lng))
            pt.x to pt.y
        }
    }

    fun resetNorth() = whenReady { m ->
        m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
    }
}

/** A picture of the map and where places fall on it, in its pixels. */
class MapCapture(
    val bitmap: android.graphics.Bitmap,
    val bounds: org.geocrew.core.model.Bounds,
    val bearing: Double,
    val toPixel: (LngLat) -> Pair<Float, Float>,
)

@Composable
fun rememberMapCameraState(): MapCameraState = remember { MapCameraState() }

/** A short text drawn next to a map position, e.g. a teammate's nickname. */
data class MapLabel(
    val position: LngLat,
    val text: String,
    val faded: Boolean = false,
    /** Hidden while the map is zoomed out further than this. */
    val minZoom: Double = 0.0,
)

/**
 * The map surface. Base map comes from [source]; everything the app draws is
 * in [overlays]. Taps report the position and the id of the feature hit, if any.
 */
@Composable
fun GeoCrewMap(
    source: TileSource,
    keys: TileKeys,
    overlays: MapOverlays,
    camera: MapCameraState,
    modifier: Modifier = Modifier,
    onClick: (position: LngLat, featureId: String?) -> Unit = { _, _ -> },
    onLongClick: (LngLat) -> Unit = {},
    /** Drawn by Compose above the map, so no glyph server is needed for text. */
    labels: List<MapLabel> = emptyList(),
    /** Shade the relief from the public DEM over the base map. */
    hillshade: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val touchSlop = with(LocalDensity.current) { 12f * density }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)

    val holder = remember { Holder() }
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = true
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(camera.center.lat, camera.center.lng))
                    .zoom(camera.zoom)
                    .build()
                map.addOnCameraMoveListener {
                    val p = map.cameraPosition
                    p.target?.let { camera.center = LngLat(it.longitude, it.latitude) }
                    camera.zoom = p.zoom
                    camera.bearing = p.bearing
                }
                map.addOnMapClickListener { latLng ->
                    val pt = map.projection.toScreenLocation(latLng)
                    val box = RectF(pt.x - touchSlop, pt.y - touchSlop, pt.x + touchSlop, pt.y + touchSlop)
                    val hit = map.queryRenderedFeatures(box, *MapLayers.clickable)
                        .firstNotNullOfOrNull { f -> f.getStringProperty("fid") }
                    currentOnClick(LngLat(latLng.longitude, latLng.latitude), hit)
                    true
                }
                map.addOnMapLongClickListener { latLng ->
                    currentOnLongClick(LngLat(latLng.longitude, latLng.latitude))
                    true
                }
                holder.map = map
                camera.attach(map)
                holder.applyStyle()
            }
        }
    }

    // Base map changes rebuild the style; overlays are re-installed on top.
    LaunchedEffect(source, keys, hillshade) {
        holder.styleJson = StyleBuilder.json(source, keys, hillshade)
        holder.applyStyle()
    }
    LaunchedEffect(overlays) {
        holder.overlays = overlays
        holder.applyOverlays()
    }

    DisposableEffect(lifecycle) {
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
            camera.map = null
            mapView.onDestroy()
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        if (labels.isNotEmpty()) Labels(labels, camera)
    }
}

@Composable
private fun Labels(labels: List<MapLabel>, camera: MapCameraState) {
    // Reading the camera state makes the labels follow every camera move.
    camera.center; camera.zoom; camera.bearing
    val projection = camera.map?.projection ?: return
    val density = LocalDensity.current
    // Only what is on screen, and not so many that the map turns into text.
    val view = projection.visibleRegion.latLngBounds
    labels.asSequence()
        .filter { camera.zoom >= it.minZoom && view.contains(LatLng(it.position.lat, it.position.lng)) }
        .take(MAX_LABELS)
        .forEach { l ->
        val pt = projection.toScreenLocation(LatLng(l.position.lat, l.position.lng))
        val x = with(density) { pt.x.toDp() } + 10.dp
        val y = with(density) { pt.y.toDp() } - 10.dp
        Text(
            l.text,
            modifier = Modifier
                .offset(x, y)
                .alpha(if (l.faded) 0.6f else 1f)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val MAX_LABELS = 80

/** Glue between Compose state and the asynchronous MapLibre objects. */
private class Holder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var styleJson: String? = null
    private var appliedJson: String? = null
    var overlays: MapOverlays = MapOverlays()
    private var drawn: MapOverlays? = null

    fun applyStyle() {
        val m = map ?: return
        val json = styleJson ?: return
        if (json == appliedJson) return
        appliedJson = json
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            if (json != appliedJson) return@setStyle
            MapLayers.install(s)
            style = s
            drawn = null
            applyOverlays()
        }
    }

    fun applyOverlays() {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        MapLayers.update(s, overlays, drawn)
        drawn = overlays
    }
}
