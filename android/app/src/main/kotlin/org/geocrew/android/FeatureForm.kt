package org.geocrew.android

import org.geocrew.storage.Crs
import org.geocrew.storage.Workspace
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import kotlinx.serialization.json.jsonObject
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.geocrew.core.edit.FieldType
import org.geocrew.core.project.Degrees

/** A field of a layer, as layer.features describes it. */
data class FormField(val name: String, val type: String)

/** A point's position: [x] is the longitude or easting, [y] the latitude or northing, in CRS [srs]. */
data class Position(val x: Double, val y: Double, val srs: String)

/**
 * A layer's own projected CRS, for entering a point's X and Y in it, and how to
 * convert a position between two CRSs (null when it cannot be converted).
 */
class FormCrs(val srs: String, val convert: suspend (from: String, to: String, x: Double, y: Double) -> Pair<Double, Double>?)

private const val DEGREES_CRS = "EPSG:4490"

/**
 * A feature's fields to read and change, like QGIS's attribute form: full
 * screen, with close and save in the top bar, where the keyboard never covers
 * them. A point's coordinates come first, in CGCS2000 degrees, or for a layer
 * in a projected CRS ([crs]) also as X and Y in it. Text fields take text,
 * number fields numbers; dates are shown, in local time, but not changed here yet.
 *
 * A new point ([isNew]) is added only by its save, which gets every field that
 * has a value and the position. For a feature already there [onSave] gets the
 * fields that changed, a cleared one as null, and the position only when it
 * changed; [onDelete] deletes it and [onMove] moves it to another layer.
 * Longitude and latitude also take degrees, minutes and seconds; with [locate]
 * the position can come from the phone's GNSS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureForm(
    title: String,
    fields: List<FormField>,
    properties: JsonObject,
    coordinates: Pair<Double, Double>?,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (values: JsonObject, position: Position?) -> Unit,
    onDelete: (() -> Unit)?,
    onMove: (() -> Unit)? = null,
    crs: FormCrs? = null,
    locate: (suspend () -> Fix?)? = null,
) {
    val original = remember(properties) { fields.associate { it.name to text(properties[it.name]) } }
    val texts = remember(properties) { mutableStateMapOf<String, String>().apply { putAll(original) } }
    val start = remember(coordinates) { coordinates?.let { degrees(it.first) to degrees(it.second) } }
    var longitude by remember(coordinates) { mutableStateOf(start?.first.orEmpty()) }
    var latitude by remember(coordinates) { mutableStateOf(start?.second.orEmpty()) }
    val problems = fields.associate { it.name to problem(it.type, texts[it.name].orEmpty()) }
    // X and Y in the layer's projected CRS, entered there when chosen; X is the northing, as surveyors write it.
    // X/Y comes up once the converted values are there; until then, and when conversion fails, longitude and latitude.
    var byXY by remember(coordinates, crs) { mutableStateOf(false) }
    var startXY by remember(coordinates, crs) { mutableStateOf<Pair<String, String>?>(null) }
    var easting by remember(coordinates, crs) { mutableStateOf("") }
    var northing by remember(coordinates, crs) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(coordinates, crs) {
        if (crs == null || coordinates == null) return@LaunchedEffect
        val xy = crs.convert(DEGREES_CRS, crs.srs, coordinates.first, coordinates.second) ?: return@LaunchedEffect
        val shown = metres(xy.first) to metres(xy.second)
        startXY = shown
        easting = shown.first
        northing = shown.second
        byXY = true
    }
    val xyReady = crs != null && startXY != null
    val eastingProblem = if (byXY) numberProblem(easting) else null
    val northingProblem = if (byXY) numberProblem(northing) else null
    val longitudeProblem = if (start == null || byXY) null else coordinateProblem(longitude, 180.0, R.string.longitude_range)
    val latitudeProblem = if (start == null || byXY) null else coordinateProblem(latitude, 90.0, R.string.latitude_range)
    val positionOk = if (byXY) xyReady && eastingProblem == null && northingProblem == null else longitudeProblem == null && latitudeProblem == null
    val canSave = problems.values.all { it == null } && (start == null || positionOk)
    val locale = LocalConfiguration.current.locales[0]
    var confirmDelete by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var accuracy by remember { mutableStateOf<Float?>(null) }

    // The phone's position goes into the fields shown: longitude and latitude, or X and Y converted.
    fun useMyPosition() {
        val find = locate ?: return
        if (locating) return
        locating = true
        scope.launch {
            val fix = find()
            locating = false
            if (fix == null) return@launch
            accuracy = fix.accuracy
            longitude = degrees(round7(fix.longitude))
            latitude = degrees(round7(fix.latitude))
            if (byXY && crs != null) crs.convert(DEGREES_CRS, crs.srs, fix.longitude, fix.latitude)?.let { (x, y) -> easting = metres(x); northing = metres(y) }
        }
    }

    // Switching shows the same spot in the other system, when what is typed is a position.
    fun switchTo(xy: Boolean) {
        if (xy == byXY || crs == null) return
        scope.launch {
            if (xy) {
                val lon = Degrees.parse(longitude, Degrees.Axis.LONGITUDE)
                val lat = Degrees.parse(latitude, Degrees.Axis.LATITUDE)
                if (lon != null && lat != null) crs.convert(DEGREES_CRS, crs.srs, lon, lat)?.let { (x, y) -> easting = metres(x); northing = metres(y) }
            } else {
                val x = easting.trim().toDoubleOrNull()
                val y = northing.trim().toDoubleOrNull()
                if (x != null && y != null) crs.convert(crs.srs, DEGREES_CRS, x, y)?.let { (lon, lat) -> longitude = degrees(lon); latitude = degrees(lat) }
            }
            byXY = xy
        }
    }

    fun save() {
        val values = fields.filter {
            val text = texts[it.name].orEmpty()
            editable(it.type) && if (isNew) text.isNotBlank() else text != original[it.name]
        }
        // Positions compare as typed: a value read with more decimals than shown is no change.
        val position = when {
            start == null -> null
            byXY && crs != null -> {
                val first = startXY
                val moved = isNew || first == null || easting != first.first || northing != first.second
                if (moved) Position(easting.trim().toDouble(), northing.trim().toDouble(), crs.srs) else null
            }
            else -> {
                val moved = isNew || longitude != start.first || latitude != start.second
                if (moved) Position(Degrees.parse(longitude, Degrees.Axis.LONGITUDE)!!, Degrees.parse(latitude, Degrees.Axis.LATITUDE)!!, DEGREES_CRS) else null
            }
        }
        onSave(JsonObject(values.associate { it.name to value(it.type, texts[it.name].orEmpty()) }), position)
    }

    // Edge to edge like the app's screens: the map does not show above the form.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The form's own window gets status bar icons that show on its surface, whatever the map set.
        val view = LocalView.current
        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = lightSurface }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) } },
                    actions = {
                        FilledIconButton(enabled = canSave, onClick = ::save, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Rounded.Check, stringResource(R.string.save))
                        }
                    },
                )
            },
        ) { padding ->
            // Not too wide to read across, in landscape.
            Box(Modifier.padding(padding).imePadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (start != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                FormHeader(if (byXY && crs != null) stringResource(R.string.coordinates_in, crs.srs) else stringResource(R.string.coordinates))
                            }
                            if (locate != null) {
                                TextButton(onClick = ::useMyPosition, enabled = !locating) {
                                    if (locating) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Rounded.MyLocation, null, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.my_position))
                                }
                            }
                        }
                        if (crs != null) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                SegmentedButton(selected = !byXY, onClick = { switchTo(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                                    Text(stringResource(R.string.coord_degrees))
                                }
                                SegmentedButton(selected = byXY, onClick = { switchTo(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = xyReady) {
                                    Text(stringResource(R.string.coord_xy))
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (byXY) {
                                CoordinateField(northing, { northing = it }, stringResource(R.string.northing), northingProblem, Modifier.weight(1f))
                                CoordinateField(easting, { easting = it }, stringResource(R.string.easting), eastingProblem, Modifier.weight(1f))
                            } else {
                                CoordinateField(longitude, { longitude = it }, stringResource(R.string.longitude), longitudeProblem, Modifier.weight(1f), degrees = true)
                                CoordinateField(latitude, { latitude = it }, stringResource(R.string.latitude), latitudeProblem, Modifier.weight(1f), degrees = true)
                            }
                        }
                        accuracy?.let {
                            Text(
                                stringResource(R.string.position_accuracy, String.format(Locale.ROOT, "%.1f", it)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (fields.isNotEmpty()) FormHeader(stringResource(R.string.attributes))
                    for (field in fields) {
                        val problem = problems[field.name]
                        if (field.type == "boolean") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(field.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = texts[field.name] == "true",
                                    onCheckedChange = { texts[field.name] = it.toString() },
                                )
                            }
                            continue
                        }
                        OutlinedTextField(
                            value = if (editable(field.type)) texts[field.name].orEmpty() else shown(field.type, texts[field.name].orEmpty(), locale),
                            onValueChange = { texts[field.name] = it },
                            label = { Text(field.name) },
                            placeholder = if (field.type == "date") ({ Text("2026-11-26") }) else null,
                            singleLine = field.type != "string",
                            readOnly = !editable(field.type),
                            isError = problem != null,
                            supportingText = problem?.let { { Text(stringResource(it)) } },
                            keyboardOptions = KeyboardOptions(keyboardType = keyboard(field.type)),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (onMove != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Layers, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.move_to_layer))
                        }
                    }
                    if (onDelete != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.feature_delete))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text(stringResource(R.string.feature_delete_title)) },
            text = { Text(stringResource(R.string.feature_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FormHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Coordinates to move the map to, in any CRS ([CrsField], CGCS2000 longitude
 * and latitude to start with): degrees for a geographic CRS, 114°18′18.5″ too;
 * X (north) and Y (east) in metres for a projected one. Checked like the form's.
 */
@Composable
fun GoToDialog(graph: AppGraph, near: Pair<Double, Double>?, onDismiss: () -> Unit, onGo: (Position) -> Unit) {
    var srs by remember { mutableStateOf(Crs.LONGITUDE_LATITUDE) }
    var projected by remember { mutableStateOf<Boolean?>(false) }
    var longitude by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var north by remember { mutableStateOf("") }
    var east by remember { mutableStateOf("") }
    LaunchedEffect(srs) {
        projected = null
        val described = graph.invoke(Workspace.CRS_DESCRIBE, buildJsonObject { put("srs", srs) })
        projected = ((described as? Outcome.Done)?.output?.jsonObject?.get("projected") as? JsonPrimitive)?.contentOrNull == "true"
    }
    val longitudeProblem = if (longitude.isEmpty()) null else coordinateProblem(longitude, 180.0, R.string.longitude_range)
    val latitudeProblem = if (latitude.isEmpty()) null else coordinateProblem(latitude, 90.0, R.string.latitude_range)
    val northProblem = if (north.isEmpty()) null else numberProblem(north)
    val eastProblem = if (east.isEmpty()) null else numberProblem(east)
    val ready = when (projected) {
        true -> north.isNotBlank() && east.isNotBlank() && northProblem == null && eastProblem == null
        false -> longitude.isNotBlank() && latitude.isNotBlank() && longitudeProblem == null && latitudeProblem == null
        null -> false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_go_to)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CrsField(graph, srs, onPick = { srs = it }, near = near)
                if (projected == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoordinateField(north, { north = it }, stringResource(R.string.northing), northProblem, Modifier.weight(1f))
                        CoordinateField(east, { east = it }, stringResource(R.string.easting), eastProblem, Modifier.weight(1f))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoordinateField(longitude, { longitude = it }, stringResource(R.string.longitude), longitudeProblem, Modifier.weight(1f), degrees = true)
                        CoordinateField(latitude, { latitude = it }, stringResource(R.string.latitude), latitudeProblem, Modifier.weight(1f), degrees = true)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onGo(
                        if (projected == true) {
                            Position(east.trim().toDouble(), north.trim().toDouble(), srs)
                        } else {
                            Position(Degrees.parse(longitude, Degrees.Axis.LONGITUDE)!!, Degrees.parse(latitude, Degrees.Axis.LATITUDE)!!, srs)
                        },
                    )
                },
                enabled = ready,
            ) {
                Text(stringResource(R.string.go))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CoordinateField(value: String, onChange: (String) -> Unit, label: String, problem: Int?, modifier: Modifier, degrees: Boolean = false) {
    // Degrees may come as 114°18′18.5″: the full keyboard has the marks, and the decimal value shows below.
    val decimal = if (degrees && problem == null && Degrees.isSexagesimal(value)) Degrees.parse(value) else null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = problem != null,
        supportingText = when {
            problem != null -> ({ Text(stringResource(problem)) })
            decimal != null -> ({ Text("= " + BigDecimal.valueOf(decimal).setScale(7, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "°") })
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = if (degrees) KeyboardType.Text else KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun editable(type: String) = type in setOf("string", "real", "integer", "integer64", "date", "boolean")

// A date and time stored as UTC, shown in the phone's time zone and the app's language.
private fun shown(type: String, text: String, locale: Locale): String {
    if (type != "datetime" || text.isBlank()) return text
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault()).format(Instant.parse(text))
    }.getOrDefault(text)
}

private fun keyboard(type: String) = when (type) {
    "real" -> KeyboardType.Decimal
    "integer", "integer64" -> KeyboardType.Number
    else -> KeyboardType.Text
}

// Metres as surveyors write them: to the millimetre.
private fun metres(value: Double): String = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).toPlainString()

// What is wrong with a projected coordinate, as a string resource.
private fun numberProblem(text: String): Int? = when {
    text.isBlank() -> R.string.coordinate_missing
    text.trim().toDoubleOrNull()?.isFinite() != true -> R.string.field_not_a_number
    else -> null
}

// Degrees as the user reads them: to the seventh decimal (about a centimetre), no trailing zeros.
private fun degrees(value: Double): String = BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()

// What is wrong with the text for a field of this type, as a string resource.
private fun problem(type: String, text: String): Int? {
    if (text.isBlank()) return null
    return when (type) {
        "real" -> if (text.trim().toDoubleOrNull()?.isFinite() == true) null else R.string.field_not_a_number
        "integer" -> if (text.trim().toIntOrNull() != null) null else R.string.field_not_a_whole_number
        "integer64" -> if (text.trim().toLongOrNull() != null) null else R.string.field_not_a_whole_number
        "date" -> if (FieldType.DATE.accepts(JsonPrimitive(text.trim()))) null else R.string.field_not_a_date
        else -> null
    }
}

// What is wrong with a coordinate, as a string resource: a point always has both.
private fun coordinateProblem(text: String, limit: Double, outOfRange: Int): Int? {
    val degrees = Degrees.parse(text, if (limit > 90) Degrees.Axis.LONGITUDE else Degrees.Axis.LATITUDE)
    return when {
        text.isBlank() -> R.string.coordinate_missing
        degrees == null || !degrees.isFinite() -> R.string.field_not_a_number
        abs(degrees) > limit -> outOfRange
        else -> null
    }
}

private fun value(type: String, text: String): JsonElement = when {
    text.isBlank() -> JsonNull
    type == "real" -> JsonPrimitive(text.trim().toDouble())
    type == "integer" || type == "integer64" -> JsonPrimitive(text.trim().toLong())
    type == "boolean" -> JsonPrimitive(text == "true")
    type == "date" -> JsonPrimitive(text.trim())
    else -> JsonPrimitive(text)
}
