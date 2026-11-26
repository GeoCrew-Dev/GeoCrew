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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.android.geometry.LatLng

/**
 * A line or an area being drawn into [layer]: its vertices so far, in CGCS2000
 * degrees. With [fid] it is a feature of the layer being reshaped.
 */
data class Drawing(val layer: String, val area: Boolean, val vertices: List<LatLng> = emptyList(), val fid: Long? = null) {
    /** Whether it has enough vertices to be saved: two for a line, three for an area. */
    val complete: Boolean get() = vertices.size >= if (area) 3 else 2
}

/**
 * A feature's shape to reshape: a line's vertices, or an area's outer ring
 * without its closing vertex; null for points, multi-part shapes and areas
 * with holes, which drawing does not keep.
 */
fun reshapeVertices(feature: JsonObject): Pair<Boolean, List<LatLng>>? {
    val geometry = feature["geometry"] as? JsonObject ?: return null
    val coordinates = geometry["coordinates"] as? JsonArray ?: return null
    fun vertex(e: JsonElement): LatLng? {
        val xy = e as? JsonArray ?: return null
        val x = (xy.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val y = (xy.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        return LatLng(y, x)
    }
    return when ((geometry["type"] as? JsonPrimitive)?.contentOrNull) {
        "LineString" -> false to coordinates.mapNotNull(::vertex)
        "Polygon" -> if (coordinates.size != 1) null else true to (coordinates.single() as? JsonArray).orEmpty().mapNotNull(::vertex).dropLast(1)
        else -> null
    }
}

/**
 * Whether layers of geometry types [a] and [b] (OGC names) hold the same kind
 * of feature: points, lines or areas, single or multi-part alike, as GDAL does
 * not put a multi-part shape into a single-part layer.
 */
fun sameKind(a: String?, b: String?): Boolean {
    fun kind(g: String?) = when {
        g == null -> null
        g.contains("POLYGON") -> 2
        g.contains("LINE") -> 1
        g.contains("POINT") -> 0
        else -> null
    }
    return kind(a) != null && kind(a) == kind(b) && a!!.startsWith("MULTI") == b!!.startsWith("MULTI")
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
