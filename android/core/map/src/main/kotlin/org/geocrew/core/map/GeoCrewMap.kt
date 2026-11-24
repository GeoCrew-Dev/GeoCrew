package org.geocrew.core.map

import android.graphics.RectF
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

    fun resetNorth() = whenReady { m ->
        m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
    }
}

@Composable
fun rememberMapCameraState(): MapCameraState = remember { MapCameraState() }

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
    LaunchedEffect(source, keys) {
        holder.styleJson = StyleBuilder.json(source, keys)
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

    AndroidView(factory = { mapView }, modifier = modifier)
}

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
