package org.geocrew.android

import android.location.Location
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.PinDrop
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import org.geocrew.core.project.Degrees
import org.maplibre.android.geometry.LatLng

/** What the measuring tool measures: a line's length, an area, the azimuth from one point to another, or a point's coordinates. */
enum class MeasureMode { DISTANCE, AREA, AZIMUTH, PICK }

/** The points after a tap at [point]: an azimuth takes two, then starts again; a picked point replaces the last. */
fun withPoint(mode: MeasureMode, points: List<LatLng>, point: LatLng): List<LatLng> = when (mode) {
    MeasureMode.AZIMUTH -> if (points.size >= 2) listOf(point) else points + point
    MeasureMode.PICK -> listOf(point)
    else -> points + point
}

/** The azimuth from [from] to [to], in degrees clockwise from north (0 to 360), along the WGS84 ellipsoid. */
fun azimuthOf(from: LatLng, to: LatLng): Double {
    val results = FloatArray(2)
    Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results)
    return (results[1].toDouble() + 360) % 360
}

/** 123.4°. */
fun azimuthText(degrees: Double): String = String.format(Locale.ROOT, "%.1f°", degrees)

/** A coordinate in decimal degrees to about a centimetre. */
fun degreesText(degrees: Double): String = String.format(Locale.ROOT, "%.7f°", degrees)

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

/** The measurement so far, with undoing the last point, clearing (or copying a picked point), and closing the tool. */
@Composable
fun MeasurePanel(mode: MeasureMode, points: List<LatLng>, onUndo: () -> Unit, onClear: () -> Unit, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when (mode) {
                MeasureMode.DISTANCE -> Icons.Rounded.Straighten
                MeasureMode.AREA -> Icons.Rounded.SquareFoot
                MeasureMode.AZIMUTH -> Icons.Rounded.Explore
                MeasureMode.PICK -> Icons.Rounded.PinDrop
            }
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            val picked = points.lastOrNull()?.takeIf { mode == MeasureMode.PICK }
            val pickedText = picked?.let { stringResource(R.string.pick_value, degreesText(it.longitude), degreesText(it.latitude)) }
            Column(Modifier.weight(1f)) {
                when (mode) {
                    MeasureMode.DISTANCE -> Text(stringResource(R.string.measure_distance_value, distanceText(lengthOf(points))), style = MaterialTheme.typography.titleMedium)
                    MeasureMode.AREA -> Text(stringResource(R.string.measure_area_value, areaText(areaOf(points))), style = MaterialTheme.typography.titleMedium)
                    MeasureMode.AZIMUTH -> if (points.size == 2) {
                        Text(stringResource(R.string.measure_azimuth_value, azimuthText(azimuthOf(points[0], points[1]))), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.measure_distance_value, distanceText(lengthOf(points))), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.tool_azimuth), style = MaterialTheme.typography.titleMedium)
                    }
                    MeasureMode.PICK -> if (picked != null) {
                        Text(pickedText.orEmpty(), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.pick_value, Degrees.dms(picked.longitude), Degrees.dms(picked.latitude)), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.tool_pick), style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (points.isEmpty()) Text(stringResource(R.string.measure_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (mode == MeasureMode.PICK) {
                IconButton(onClick = { pickedText?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = picked != null) {
                    Icon(Icons.Rounded.ContentCopy, stringResource(R.string.copy))
                }
            } else {
                IconButton(onClick = onUndo, enabled = points.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.measure_undo)) }
                IconButton(onClick = onClear, enabled = points.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.measure_clear)) }
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
        }
    }
}
