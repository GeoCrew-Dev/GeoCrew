package org.geocrew.android

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The phone's position while [enabled], from its own location service (no
 * Google services): GNSS, with the fused or network provider until GNSS has a
 * fix, as under a roof. Null until there is one.
 */
@SuppressLint("MissingPermission") // enabled only once permission is granted
@Composable
fun rememberPhonePosition(enabled: Boolean): State<Location?> {
    val context = LocalContext.current
    val position = remember { mutableStateOf<Location?>(null) }
    DisposableEffect(enabled) {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!enabled || manager == null) return@DisposableEffect onDispose {}
        val listener = LocationListener { location ->
            val last = position.value
            // GNSS wins; another provider only fills in while GNSS has had nothing for a while.
            val gnss = location.provider == LocationManager.GPS_PROVIDER
            if (gnss || last == null || last.provider != LocationManager.GPS_PROVIDER || location.time - last.time > GNSS_PATIENCE_MS) {
                position.value = location
            }
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            runCatching { manager.requestLocationUpdates(provider, 1_000L, 0f, context.mainExecutor, listener) }
        }
        onDispose { manager.removeUpdates(listener) }
    }
    return position
}

/**
 * Where the phone points while [enabled], in degrees clockwise from magnetic
 * north, as the rotation-vector sensor gives it: the top of the screen when
 * the phone lies flat, its back when it is held up. Null without the sensor.
 */
@Composable
fun rememberHeading(enabled: Boolean): State<Float?> {
    val context = LocalContext.current
    val heading = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || sensor == null) return@DisposableEffect onDispose {}
        val rotation = FloatArray(9)
        val turned = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // The screen's own rotation, so the heading is that of the screen's top in landscape too.
                @Suppress("DEPRECATION")
                val screen = context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
                val (x, y) = when (screen) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotation, x, y, turned)
                SensorManager.getOrientation(turned, orientation)
                // Held up rather than flat, the heading is where the back of the phone looks, as a compass app takes it.
                if (abs(orientation[1]) > Math.toRadians(45.0)) {
                    val (ux, uy) = when (screen) {
                        Surface.ROTATION_90 -> SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X
                        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z
                        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X
                        else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
                    }
                    SensorManager.remapCoordinateSystem(rotation, ux, uy, turned)
                    SensorManager.getOrientation(turned, orientation)
                }
                val degrees = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
                // Small turns are left out, so the map is not redrawn many times a second for nothing.
                val last = heading.value
                if (last == null || abs(((degrees - last + 540) % 360) - 180) >= 2f) heading.value = degrees
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
    return heading
}

/** Adds the layers that show the phone's position to [style]: how far off it may be, its heading, and the dot. */
fun addMyLocationLayers(style: Style) {
    val point = Expression.eq(Expression.geometryType(), Expression.literal("Point"))
    style.addSource(GeoJsonSource(ME_SOURCE))
    // The accuracy circle, in metres on the ground: its radius in pixels doubles with each zoom level.
    style.addLayer(
        CircleLayer("$ME_SOURCE-accuracy", ME_SOURCE).withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.exponential(2), Expression.zoom(),
                    Expression.stop(0, Expression.get(RADIUS_AT_ZOOM_0)),
                    Expression.stop(22, Expression.product(Expression.get(RADIUS_AT_ZOOM_0), Expression.literal(4_194_304))),
                ),
            ),
            PropertyFactory.circleColor(ME_COLOR),
            PropertyFactory.circleOpacity(0.15f),
            PropertyFactory.circleStrokeColor(ME_COLOR),
            PropertyFactory.circleStrokeOpacity(0.4f),
            PropertyFactory.circleStrokeWidth(1f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        ).withFilter(point),
    )
    // The heading as a cone on the ground, sized for the zoom it was made for: symbols did not show reliably.
    style.addLayer(
        FillLayer("$ME_SOURCE-heading", ME_SOURCE).withProperties(
            PropertyFactory.fillColor(ME_COLOR),
            PropertyFactory.fillOpacity(0.9f),
        ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Polygon"))),
    )
    style.addLayer(
        CircleLayer("$ME_SOURCE-dot", ME_SOURCE).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(ME_COLOR),
            PropertyFactory.circleStrokeColor("#ffffff"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ).withFilter(point),
    )
}

/**
 * The phone's position for [addMyLocationLayers]' source: empty without one.
 * [heading] is in degrees from true north; its cone is drawn for [zoom].
 */
fun myLocationGeoJson(position: Location?, heading: Float?, zoom: Double): JsonObject = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        if (position == null) return@putJsonArray
        val latitude = position.latitude
        // Metres per pixel at zoom 0 is the equator's length over 512 pixels, shrunk by cos(latitude).
        val metresPerPixel0 = EQUATOR_METRES * cos(Math.toRadians(latitude)) / 512
        if (heading != null) {
            val metresPerPixel = metresPerPixel0 / 2.0.pow(zoom)
            // A point [pixels] from the phone towards [bearing], on the ground.
            fun away(pixels: Double, bearing: Double) = buildJsonArray {
                val metres = pixels * metresPerPixel
                val b = Math.toRadians(bearing)
                add(position.longitude + metres * sin(b) / (METRES_PER_DEGREE * cos(Math.toRadians(latitude))))
                add(latitude + metres * cos(b) / METRES_PER_DEGREE)
            }
            val h = heading.toDouble()
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {}
                putJsonObject("geometry") {
                    put("type", "Polygon")
                    putJsonArray("coordinates") {
                        addJsonArray {
                            add(away(CONE_TIP, h))
                            add(away(CONE_BASE, h + CONE_HALF_ANGLE))
                            add(away(CONE_BASE, h - CONE_HALF_ANGLE))
                            add(away(CONE_TIP, h))
                        }
                    }
                }
            }
        }
        addJsonObject {
            put("type", "Feature")
            putJsonObject("geometry") {
                put("type", "Point")
                putJsonArray("coordinates") { add(position.longitude); add(latitude) }
            }
            putJsonObject("properties") {
                put(RADIUS_AT_ZOOM_0, if (position.hasAccuracy()) position.accuracy / metresPerPixel0 else 0.0)
            }
        }
    }
}

const val ME_SOURCE = "geocrew-me"
private const val ME_COLOR = "#1a73e8"
private const val RADIUS_AT_ZOOM_0 = "radius0"
private const val EQUATOR_METRES = 40_075_016.686
private const val METRES_PER_DEGREE = 111_320.0
private const val GNSS_PATIENCE_MS = 10_000L

// The cone, in pixels from the phone: its tip, the corners of its base, and how wide it opens.
private const val CONE_TIP = 25.0
private const val CONE_BASE = 12.0
private const val CONE_HALF_ANGLE = 38.0
