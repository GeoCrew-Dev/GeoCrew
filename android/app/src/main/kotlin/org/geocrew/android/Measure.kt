package org.geocrew.android

import android.location.Location
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
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
import org.maplibre.android.geometry.LatLng

/** What the measuring tool measures. */
enum class MeasureMode { DISTANCE, AREA }

/** The length of the line through [points], in metres, along the WGS84 ellipsoid. */
fun lengthOf(points: List<LatLng>): Double {
    val leg = FloatArray(1)
    return points.zipWithNext().sumOf { (a, b) ->
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, leg)
        leg[0].toDouble()
    }
}

/**
 * The area inside the polygon through [points], in square metres, on a sphere
 * of the Earth's mean radius: within a few parts in a thousand of the
 * ellipsoid, plenty for a field measurement.
 */
fun areaOf(points: List<LatLng>): Double {
    if (points.size < 3) return 0.0
    val ring = points + points.first()
    val sum = ring.zipWithNext().sumOf { (a, b) ->
        Math.toRadians(b.longitude - a.longitude) * (2 + sin(Math.toRadians(a.latitude)) + sin(Math.toRadians(b.latitude)))
    }
    return abs(sum * EARTH_RADIUS * EARTH_RADIUS / 2)
}

private const val EARTH_RADIUS = 6_371_008.8

/** 856.2 m, 12.345 km. */
fun distanceText(metres: Double): String =
    if (metres < 1_000) String.format(Locale.ROOT, "%.1f m", metres) else String.format(Locale.ROOT, "%.3f km", metres / 1_000)

/** 950 m², 2.35 ha, 12.3 km². */
fun areaText(squareMetres: Double): String = when {
    squareMetres < 10_000 -> String.format(Locale.ROOT, "%.0f m²", squareMetres)
    squareMetres < 1_000_000 -> String.format(Locale.ROOT, "%.2f ha", squareMetres / 10_000)
    else -> String.format(Locale.ROOT, "%.3f km²", squareMetres / 1_000_000)
}

/** The measurement as GeoJSON for the map: the line or polygon, and its points. */
fun measureGeoJson(mode: MeasureMode, points: List<LatLng>): JsonObject = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        fun position(p: LatLng) = buildJsonArray { add(p.longitude); add(p.latitude) }
        if (mode == MeasureMode.AREA && points.size >= 3) {
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {}
                putJsonObject("geometry") {
                    put("type", "Polygon")
                    putJsonArray("coordinates") { addJsonArray { for (p in points + points.first()) add(position(p)) } }
                }
            }
        }
        if (points.size >= 2) {
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {}
                putJsonObject("geometry") {
                    put("type", "LineString")
                    val line = if (mode == MeasureMode.AREA && points.size >= 3) points + points.first() else points
                    putJsonArray("coordinates") { for (p in line) add(position(p)) }
                }
            }
        }
        for (p in points) {
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {}
                putJsonObject("geometry") {
                    put("type", "Point")
                    put("coordinates", position(p))
                }
            }
        }
    }
}

/** The measurement so far, with undoing the last point, clearing, and closing the tool. */
@Composable
fun MeasurePanel(mode: MeasureMode, points: List<LatLng>, onUndo: () -> Unit, onClear: () -> Unit, onClose: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (mode == MeasureMode.DISTANCE) Icons.Rounded.Straighten else Icons.Rounded.SquareFoot, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val value = when (mode) {
                    MeasureMode.DISTANCE -> stringResource(R.string.measure_distance_value, distanceText(lengthOf(points)))
                    MeasureMode.AREA -> stringResource(R.string.measure_area_value, areaText(areaOf(points)))
                }
                Text(value, style = MaterialTheme.typography.titleMedium)
                if (points.isEmpty()) Text(stringResource(R.string.measure_hint), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onUndo, enabled = points.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.measure_undo)) }
            IconButton(onClick = onClear, enabled = points.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.measure_clear)) }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
        }
    }
}
