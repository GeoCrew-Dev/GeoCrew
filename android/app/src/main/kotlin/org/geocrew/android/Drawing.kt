package org.geocrew.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pentagon
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.android.geometry.LatLng

/** A line or an area being drawn into [layer]: its vertices so far, in CGCS2000 degrees. */
data class Drawing(val layer: String, val area: Boolean, val vertices: List<LatLng> = emptyList()) {
    /** Whether it has enough vertices to be saved: two for a line, three for an area. */
    val complete: Boolean get() = vertices.size >= if (area) 3 else 2
}

/** Whether a layer of [geometry] (an OGC name such as MULTIPOLYGON) holds areas. */
fun holdsAreas(geometry: String?): Boolean = geometry?.contains("POLYGON") == true

/**
 * [vertices] as a GeoJSON LineString, or a Polygon with its ring closed,
 * from x and y pairs (longitude and latitude, or easting and northing).
 */
fun shapeGeoJson(vertices: List<Pair<Double, Double>>, area: Boolean): JsonObject = buildJsonObject {
    put("type", if (area) "Polygon" else "LineString")
    putJsonArray("coordinates") {
        if (area) {
            addJsonArray { for ((x, y) in vertices + vertices.first()) addJsonArray { add(x); add(y) } }
        } else {
            for ((x, y) in vertices) addJsonArray { add(x); add(y) }
        }
    }
}

/** What is being drawn: the layer, how many vertices, and the length or area so far; undoing a vertex, and stopping. */
@Composable
fun DrawPanel(drawing: Drawing, onUndo: () -> Unit, onClose: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (drawing.area) Icons.Rounded.Pentagon else Icons.Rounded.Timeline, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(drawing.layer, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = pluralStringResource(R.plurals.vertices_count, drawing.vertices.size, drawing.vertices.size)
                val size = when {
                    drawing.area && drawing.vertices.size >= 3 -> stringResource(R.string.measure_area_value, areaText(areaOf(drawing.vertices)))
                    !drawing.area && drawing.vertices.size >= 2 -> stringResource(R.string.measure_distance_value, distanceText(lengthOf(drawing.vertices)))
                    else -> null
                }
                Text(listOfNotNull(count, size).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onUndo, enabled = drawing.vertices.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.measure_undo))
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
        }
    }
}
