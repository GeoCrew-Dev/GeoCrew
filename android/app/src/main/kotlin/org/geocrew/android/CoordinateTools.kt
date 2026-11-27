package org.geocrew.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.action.Outcome
import org.geocrew.core.project.Degrees
import org.geocrew.core.project.Numbers
import org.geocrew.storage.Crs
import org.geocrew.storage.Workspace

/** Two coordinates as typed: longitude and latitude, or X (north) and Y (east) in metres. */
data class CoordinateTexts(val first: String = "", val second: String = "")

/** Whether [srs] is projected (metres, X and Y) rather than geographic (degrees); null when GDAL does not read it. */
suspend fun isProjected(graph: AppGraph, srs: String): Boolean? {
    val described = graph.invoke(Workspace.CRS_DESCRIBE, buildJsonObject { put("srs", srs) }) as? Outcome.Done ?: return null
    return (described.output.jsonObject["projected"] as? JsonPrimitive)?.contentOrNull == "true"
}

/** [position] from its CRS to [to], through crs.transform; null when PROJ cannot. */
suspend fun convertPosition(graph: AppGraph, position: Position, to: String): Position? {
    if (Crs.same(position.srs, to)) return position.copy(srs = to)
    val moved = graph.invoke(
        Workspace.CRS_TRANSFORM,
        buildJsonObject {
            put("from", position.srs)
            put("to", to)
            putJsonArray("xy") { add(position.x); add(position.y) }
        },
    ) as? Outcome.Done ?: return null
    val xy = (moved.output.jsonObject["xy"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
    val x = xy.getOrNull(0)?.takeIf { it.isFinite() } ?: return null
    val y = xy.getOrNull(1)?.takeIf { it.isFinite() } ?: return null
    return Position(x, y, to)
}

/** The position [texts] give in [srs], or null while one is missing or wrong. */
fun positionOf(texts: CoordinateTexts, projected: Boolean, srs: String): Position? = if (projected) {
    val north = Numbers.double(texts.first)
    val east = Numbers.double(texts.second)
    if (north == null || east == null) null else Position(east, north, srs)
} else {
    val longitude = Degrees.parse(texts.first, Degrees.Axis.LONGITUDE)?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 180 }
    val latitude = Degrees.parse(texts.second, Degrees.Axis.LATITUDE)?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 90 }
    if (longitude == null || latitude == null) null else Position(longitude, latitude, srs)
}

/** [position] as texts to type over: degrees to the seventh decimal, metres to the millimetre. */
fun textsOf(position: Position, projected: Boolean): CoordinateTexts =
    if (projected) CoordinateTexts(millimetres(position.y), millimetres(position.x)) else CoordinateTexts(sevenths(position.x), sevenths(position.y))

/** Longitude and latitude (DMS too) for a geographic CRS, X (north) and Y (east) for a projected one, checked as typed. */
@Composable
fun CoordinateFields(texts: CoordinateTexts, projected: Boolean, onChange: (CoordinateTexts) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (projected) {
            val northProblem = if (texts.first.isEmpty()) null else numberProblem(texts.first)
            val eastProblem = if (texts.second.isEmpty()) null else numberProblem(texts.second)
            CoordinateField(texts.first, { onChange(texts.copy(first = it)) }, stringResource(R.string.northing), northProblem, Modifier.weight(1f))
            CoordinateField(texts.second, { onChange(texts.copy(second = it)) }, stringResource(R.string.easting), eastProblem, Modifier.weight(1f))
        } else {
            val longitudeProblem = if (texts.first.isEmpty()) null else coordinateProblem(texts.first, 180.0, R.string.longitude_range)
            val latitudeProblem = if (texts.second.isEmpty()) null else coordinateProblem(texts.second, 90.0, R.string.latitude_range)
            CoordinateField(texts.first, { onChange(texts.copy(first = it)) }, stringResource(R.string.longitude), longitudeProblem, Modifier.weight(1f), degrees = true)
            CoordinateField(texts.second, { onChange(texts.copy(second = it)) }, stringResource(R.string.latitude), latitudeProblem, Modifier.weight(1f), degrees = true)
        }
    }
}

/**
 * Coordinates to move the map to, in any CRS ([CrsField], CGCS2000 longitude
 * and latitude to start with): degrees for a geographic CRS, 114°18′18.5″ too;
 * X (north) and Y (east) in metres for a projected one.
 */
@Composable
fun GoToDialog(graph: AppGraph, near: Pair<Double, Double>?, onDismiss: () -> Unit, onGo: (Position) -> Unit) {
    var srs by remember { mutableStateOf(Crs.LONGITUDE_LATITUDE) }
    var projected by remember { mutableStateOf<Boolean?>(false) }
    var texts by remember { mutableStateOf(CoordinateTexts()) }
    LaunchedEffect(srs) {
        projected = null
        projected = isProjected(graph, srs) ?: false
    }
    val position = projected?.let { positionOf(texts, it, srs) }
    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        title = { Text(stringResource(R.string.tool_go_to)) },
        text = {
            Column {
                CrsField(graph, srs, onPick = { if (it != srs) texts = CoordinateTexts(); srs = it }, near = near)
                CoordinateFields(texts, projected == true) { texts = it }
            }
        },
        confirmButton = { TextButton(onClick = { position?.let(onGo) }, enabled = position != null) { Text(stringResource(R.string.go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Coordinates from one CRS to another as they are typed, both picked from
 * every CRS PROJ knows: CGCS2000 longitude and latitude to the Gauss-Kruger
 * 3-degree zone where the map is, to start with. The map's centre fills the
 * input; the two can be swapped; the result can be copied (X, Y or latitude,
 * longitude) and shown on the map ([onShow] gets it in CGCS2000 degrees).
 */
@Composable
fun CoordinateConvertDialog(graph: AppGraph, near: Pair<Double, Double>?, onDismiss: () -> Unit, onShow: (longitude: Double, latitude: Double) -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var from by remember { mutableStateOf(Crs.LONGITUDE_LATITUDE) }
    var to by remember { mutableStateOf(near?.let { gaussKruger3(it.first, false) }?.let { "EPSG:${it.first}" } ?: "EPSG:4326") }
    var fromProjected by remember { mutableStateOf<Boolean?>(false) }
    var toProjected by remember { mutableStateOf<Boolean?>(null) }
    var texts by remember { mutableStateOf(CoordinateTexts()) }
    var result by remember { mutableStateOf<Position?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(from) { fromProjected = null; fromProjected = isProjected(graph, from) ?: false }
    LaunchedEffect(to) { toProjected = null; toProjected = isProjected(graph, to) ?: false }
    val input = fromProjected?.let { positionOf(texts, it, from) }
    LaunchedEffect(input, to) {
        if (input == null) {
            result = null
            failed = false
            return@LaunchedEffect
        }
        delay(150) // typing on: convert once it pauses
        result = convertPosition(graph, input, to)
        failed = result == null
    }

    // The map's centre into the input CRS.
    fun fillFromMap() = scope.launch {
        val (lon, lat) = near ?: return@launch
        val projected = fromProjected ?: return@launch
        convertPosition(graph, Position(lon, lat, Crs.LONGITUDE_LATITUDE), from)?.let { texts = textsOf(it, projected) }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        title = { Text(stringResource(R.string.tool_convert)) },
        text = {
            Column {
                CrsField(graph, from, onPick = { if (it != from) texts = CoordinateTexts(); from = it }, label = stringResource(R.string.convert_from), near = near)
                CoordinateFields(texts, fromProjected == true) { texts = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            // The input converted now, not the last result shown, becomes the input in the other CRS.
                            scope.launch {
                                val current = input
                                val back = current?.let { convertPosition(graph, it, to) }
                                val backProjected = toProjected
                                val wasTo = to
                                to = from
                                from = wasTo
                                texts = if (back != null && backProjected != null) textsOf(back, backProjected) else CoordinateTexts()
                            }
                        },
                    ) { Icon(Icons.Rounded.SwapVert, stringResource(R.string.convert_swap)) }
                    if (near != null) {
                        TextButton(onClick = { fillFromMap() }) {
                            Icon(Icons.Rounded.CenterFocusStrong, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.convert_map_centre))
                        }
                    }
                }
                CrsField(graph, to, onPick = { to = it }, label = stringResource(R.string.convert_to), near = near)
                val shown = result
                when {
                    failed -> Text(stringResource(R.string.go_to_outside), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    shown != null && toProjected != null -> {
                        val projected = toProjected == true
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            SelectionContainer(Modifier.weight(1f)) {
                                Column {
                                    if (projected) {
                                        Text(stringResource(R.string.northing) + "  " + millimetres(shown.y), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.easting) + "  " + millimetres(shown.x), style = MaterialTheme.typography.titleMedium)
                                    } else {
                                        Text(stringResource(R.string.longitude) + "  " + sevenths(shown.x) + "°  " + Degrees.dms(shown.x), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.latitude) + "  " + sevenths(shown.y) + "°  " + Degrees.dms(shown.y), style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                            IconButton(
                                onClick = {
                                    // X, Y as surveyors write them; latitude, longitude as map apps take them.
                                    val copied = if (projected) millimetres(shown.y) + ", " + millimetres(shown.x) else sevenths(shown.y) + ", " + sevenths(shown.x)
                                    clipboard.setText(AnnotatedString(copied))
                                },
                            ) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.copy)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val at = input ?: return@TextButton
                    scope.launch {
                        val degrees = convertPosition(graph, at, Crs.LONGITUDE_LATITUDE)
                        if (degrees == null) failed = true else onShow(degrees.x, degrees.y)
                    }
                },
                enabled = input != null && !failed,
            ) { Text(stringResource(R.string.convert_show)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

// Degrees to the seventh decimal (about a centimetre), no trailing zeros.
private fun sevenths(value: Double): String = BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

// Metres as surveyors write them: to the millimetre.
private fun millimetres(value: Double): String = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).toPlainString()
