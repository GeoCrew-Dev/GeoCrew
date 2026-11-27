package org.geocrew.android

import android.Manifest
import android.hardware.GeomagneticField
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.geocrew.core.edit.Field
import org.geocrew.core.edit.FieldType
import org.geocrew.core.edit.ValueRange
import org.geocrew.core.project.Degrees
import org.geocrew.core.project.Numbers

/** A field of a layer, as layer.features describes it. */
data class FormField(val name: String, val type: String, val range: ValueRange? = null) {
    /** The field as the edit buffer checks values against it. */
    fun field(): Field = Field(name, FieldType.of(type), range)
}

/** What is wrong with a value as typed: a string resource and the numbers that go into it. */
data class ValueProblem(@StringRes val text: Int, val args: List<String> = emptyList())

@Composable
internal fun ValueProblem.message(): String = stringResource(text, *args.toTypedArray())

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
 *
 * With [attachments] (the feature's, as feature.attachments gives them) the
 * form shows its photos and files, takes more through [importFile] and lets
 * them go; [onSave] gets the new list, or null when it did not change.
 *
 * A layer with dip direction and dip fields (attitudeFields) gets a button
 * that measures them with the phone (ClinometerScreen) and fills them in,
 * the strike too when the layer has it.
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
    onSave: (values: JsonObject, position: Position?, attachments: List<JsonObject>?) -> Unit,
    onDelete: (() -> Unit)?,
    onMove: (() -> Unit)? = null,
    onReshape: (() -> Unit)? = null,
    crs: FormCrs? = null,
    locate: (suspend () -> Fix?)? = null,
    area: Boolean? = null,
    attachments: List<JsonObject>? = null,
    importFile: (suspend (Uri) -> JsonObject?)? = null,
) {
    // What is deleted: a point, or with [area] a line (false) or an area (true).
    val deleteText = stringResource(when (area) { null -> R.string.feature_delete; false -> R.string.line_delete; true -> R.string.area_delete })
    val deleteTitle = stringResource(when (area) { null -> R.string.feature_delete_title; false -> R.string.line_delete_title; true -> R.string.area_delete_title })
    val original = remember(properties) { fields.associate { it.name to fieldText(properties[it.name]) } }
    val texts = remember(properties) { mutableStateMapOf<String, String>().apply { putAll(original) } }
    val start = remember(coordinates) { coordinates?.let { degrees(it.first) to degrees(it.second) } }
    var longitude by remember(coordinates) { mutableStateOf(start?.first.orEmpty()) }
    var latitude by remember(coordinates) { mutableStateOf(start?.second.orEmpty()) }
    val problems = fields.associate { it.name to valueProblem(it, texts[it.name].orEmpty()) }
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
    var confirmDelete by remember { mutableStateOf(false) }
    // A date or date-and-time field whose value is being chosen.
    var picking by remember { mutableStateOf<FormField?>(null) }
    var locating by remember { mutableStateOf(false) }
    var accuracy by remember { mutableStateOf<Float?>(null) }
    val files = remember(attachments) { mutableStateListOf<JsonObject>().apply { attachments?.let(::addAll) } }
    var adding by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<JsonObject?>(null) }
    var addFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val openText = stringResource(R.string.attachment_open_with)
    val attitude = remember(fields) { attitudeFields(fields) }
    var measuring by remember { mutableStateOf(false) }
    // The declination where the feature is, else where the phone was in the last ten minutes; none when neither is known.
    fun declinationHere(): Float {
        val lon = Degrees.parse(longitude, Degrees.Axis.LONGITUDE) ?: coordinates?.first
        val lat = Degrees.parse(latitude, Degrees.Axis.LATITUDE) ?: coordinates?.second
        val (x, y) = if (lon != null && lat != null) lon to lat else lastLocation(context, 600_000)?.let { it.longitude to it.latitude } ?: return 0f
        return GeomagneticField(y.toFloat(), x.toFloat(), 0f, System.currentTimeMillis()).declination
    }
    var shooting by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    // A picked file or a photo goes into the project at once, with what the camera knew ([meta]);
    // the feature gets it when the form is saved. A photo's own file in the cache ([temp]) goes then.
    fun attach(uri: Uri?, meta: JsonObject? = null, temp: File? = null) {
        val import = importFile ?: return
        if (uri == null) return
        adding = true
        scope.launch {
            val imported = import(uri)
            temp?.delete()
            adding = false
            when {
                imported == null -> addFailed = true
                meta == null -> files += imported
                else -> files += JsonObject(imported + ("meta" to meta))
            }
        }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) shooting = true else cameraDenied = true
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { attach(it) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { attach(it) }

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
                val x = Numbers.double(easting)
                val y = Numbers.double(northing)
                if (x != null && y != null) crs.convert(crs.srs, DEGREES_CRS, x, y)?.let { (lon, lat) -> longitude = degrees(lon); latitude = degrees(lat) }
            }
            byXY = xy
        }
    }

    fun save() {
        val values = fields.filter {
            val text = texts[it.name].orEmpty()
            settable(it.type) && if (isNew) text.isNotBlank() else text != original[it.name]
        }
        // Positions compare as typed: a value read with more decimals than shown is no change.
        val position = when {
            start == null -> null
            byXY && crs != null -> {
                val first = startXY
                val moved = isNew || first == null || easting != first.first || northing != first.second
                if (moved) Position(Numbers.double(easting)!!, Numbers.double(northing)!!, crs.srs) else null
            }
            else -> {
                val moved = isNew || longitude != start.first || latitude != start.second
                if (moved) Position(Degrees.parse(longitude, Degrees.Axis.LONGITUDE)!!, Degrees.parse(latitude, Degrees.Axis.LATITUDE)!!, DEGREES_CRS) else null
            }
        }
        val attached = files.toList().takeIf { attachments != null && it != attachments }
        onSave(JsonObject(values.associate { it.name to fieldValue(it.type, texts[it.name].orEmpty()) }), position, attached)
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
                        FieldInput(field, texts[field.name].orEmpty(), onChange = { texts[field.name] = it }, onPick = { picking = field })
                    }
                    if (attitude != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { measuring = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Explore, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.clino_title))
                        }
                    }
                    if (attachments != null) {
                        FormHeader(stringResource(R.string.attachments))
                        AttachmentStrip(
                            files = files,
                            adding = adding,
                            onAdd = { source ->
                                when (source) {
                                    AttachmentSource.CAMERA -> if (mayUseCamera(context)) shooting = true else askCamera.launch(Manifest.permission.CAMERA)
                                    AttachmentSource.GALLERY -> pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    AttachmentSource.FILE -> pickFile.launch(arrayOf("*/*"))
                                }
                            },
                            onOpen = { file ->
                                val path = (file["path"] as? JsonPrimitive)?.contentOrNull
                                val type = (file["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                when {
                                    path == null || !File(path).exists() -> Unit
                                    type.startsWith("image/") -> viewing = file
                                    else -> openWith(context, File(path), openText)
                                }
                            },
                            onRemove = { files.remove(it) },
                        )
                    }
                    if (onReshape != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onReshape, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.draw_reshape))
                        }
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
                            Text(deleteText)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    viewing?.let { PhotoViewer(it, onDismiss = { viewing = null }) }
    if (measuring && attitude != null) {
        ClinometerScreen(
            declination = remember { declinationHere() },
            onRecord = { a ->
                measuring = false
                val direction = wholeDirection(a.dipDirection)
                texts[attitude.dipDirection.name] = direction.toString()
                texts[attitude.dip.name] = wholeDip(a.dip).toString()
                attitude.strike?.let { texts[it.name] = ((direction + 270) % 360).toString() }
            },
            onDismiss = { measuring = false },
        )
    }
    if (shooting) {
        CameraScreen(
            onTaken = { shot ->
                shooting = false
                attach(Uri.fromFile(shot.file), shot.meta, shot.file)
            },
            onDismiss = { shooting = false },
        )
    }
    if (cameraDenied) {
        AppDialog(
            onDismissRequest = { cameraDenied = false },
            confirmButton = { TextButton(onClick = { cameraDenied = false }) { Text(stringResource(R.string.ok)) } },
            text = { Text(stringResource(R.string.camera_denied)) },
        )
    }
    if (addFailed) {
        AppDialog(
            onDismissRequest = { addFailed = false },
            confirmButton = { TextButton(onClick = { addFailed = false }) { Text(stringResource(R.string.ok)) } },
            text = { Text(stringResource(R.string.attachment_failed)) },
        )
    }
    picking?.let { field ->
        FieldPicker(field, texts[field.name].orEmpty(), onDismiss = { picking = null }) {
            texts[field.name] = it
            picking = null
        }
    }
    if (confirmDelete && onDelete != null) {
        AppDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text(deleteTitle) },
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

/**
 * The input for [field]'s value, [text] as a form keeps it: a switch for a yes
 * or no, else a text field that says what is wrong with what is typed. A date
 * is typed or chosen ([onPick]); a date and time is chosen, and can be cleared.
 */
@Composable
internal fun FieldInput(field: FormField, text: String, onChange: (String) -> Unit, onPick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    if (field.type == "boolean") {
        Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(field.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = text == "true" || text == "1", onCheckedChange = { onChange(it.toString()) })
        }
        return
    }
    val problem = valueProblem(field, text)
    OutlinedTextField(
        value = if (editable(field.type)) text else shownDateTime(field.type, text, LocalConfiguration.current.locales[0]),
        onValueChange = onChange,
        label = { Text(field.name) },
        placeholder = if (field.type == "date") ({ Text("2026-11-26") }) else null,
        trailingIcon = if (field.type == "date" || field.type == "datetime") {
            {
                Row {
                    if (field.type == "datetime" && text.isNotBlank()) {
                        IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Clear, stringResource(R.string.clear)) }
                    }
                    IconButton(onClick = onPick) { Icon(Icons.Rounded.CalendarMonth, stringResource(R.string.pick_date)) }
                }
            }
        } else {
            null
        },
        // A date and time, chosen and not typed, wraps rather than losing its seconds in a narrow dialog.
        singleLine = field.type != "string" && field.type != "datetime",
        readOnly = !editable(field.type),
        isError = problem != null,
        supportingText = problem?.let { { Text(it.message()) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard(field.type)),
        modifier = modifier,
    )
}

/** Chooses [field]'s date, or its date and then its time, from [text]; [onPick] gets the value as a form keeps it. */
@Composable
internal fun FieldPicker(field: FormField, text: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    var day by remember(field) { mutableStateOf<LocalDate?>(null) }
    val chosen = day
    when {
        field.type == "date" -> DateDialog(dateOf(text), onDismiss = onDismiss) { onPick(it.toString()) }
        chosen == null -> DateDialog(instantOf(text, zone)?.atZone(zone)?.toLocalDate(), onDismiss = onDismiss) { day = it }
        else -> TimeDialog(instantOf(text, zone)?.atZone(zone)?.toLocalTime() ?: LocalTime.now(), onDismiss = onDismiss) {
            onPick(storedDateTime(chosen, it, zone))
        }
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

@Composable
internal fun CoordinateField(value: String, onChange: (String) -> Unit, label: String, problem: Int?, modifier: Modifier, degrees: Boolean = false) {
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

// Typed or switched in its input; a date can be chosen too.
private fun editable(type: String) = type in setOf("string", "real", "integer", "integer64", "date", "boolean")

/** Whether a form sets values of this type: typed, switched or chosen. */
internal fun settable(type: String) = editable(type) || type == "datetime"

private fun keyboard(type: String) = when (type) {
    "real" -> KeyboardType.Decimal
    "integer", "integer64" -> KeyboardType.Number
    else -> KeyboardType.Text
}

// Metres as surveyors write them: to the millimetre.
private fun metres(value: Double): String = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).toPlainString()

// What is wrong with a projected coordinate, as a string resource.
internal fun numberProblem(text: String): Int? = when {
    text.isBlank() -> R.string.coordinate_missing
    Numbers.double(text) == null -> R.string.field_not_a_number
    else -> null
}

// Degrees as the user reads them: to the seventh decimal (about a centimetre), no trailing zeros.
private fun degrees(value: Double): String = BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** A value as a form keeps it: its text, empty for none. */
internal fun fieldText(value: JsonElement?): String = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()

/** What is wrong with the text for [field]: not of its type, or out of its range; null when nothing is. */
internal fun valueProblem(field: FormField, text: String): ValueProblem? {
    if (text.isBlank()) return null
    val type = when (field.type) {
        "real" -> if (Numbers.double(text) != null) null else R.string.field_not_a_number
        "integer" -> if (Numbers.int(text) != null) null else R.string.field_not_a_whole_number
        "integer64" -> if (Numbers.long(text) != null) null else R.string.field_not_a_whole_number
        "date" -> if (FieldType.DATE.accepts(JsonPrimitive(Numbers.plain(text)))) null else R.string.field_not_a_date
        else -> null
    }
    if (type != null) return ValueProblem(type)
    val range = field.range ?: return null
    val x = if (field.type in NUMBER_TYPES) Numbers.double(text) else null
    if (x == null || x in range) return null
    val min = range.min
    val max = range.max
    // Both ends when both are in the range; else the end the number is past.
    return when {
        min != null && max != null && range.minIncluded && range.maxIncluded -> ValueProblem(R.string.field_range_between, listOf(plainNumber(min), plainNumber(max)))
        min != null && (x < min || (x == min && !range.minIncluded)) ->
            ValueProblem(if (range.minIncluded) R.string.field_range_at_least else R.string.field_range_above, listOf(plainNumber(min)))
        max != null -> ValueProblem(if (range.maxIncluded) R.string.field_range_at_most else R.string.field_range_below, listOf(plainNumber(max)))
        else -> null
    }
}

/** A number as people write it: 90, not 90.0. */
internal fun plainNumber(x: Double): String = BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()

// What is wrong with a coordinate, as a string resource: a point always has both.
internal fun coordinateProblem(text: String, limit: Double, outOfRange: Int): Int? {
    val degrees = Degrees.parse(text, if (limit > 90) Degrees.Axis.LONGITUDE else Degrees.Axis.LATITUDE)
    return when {
        text.isBlank() -> R.string.coordinate_missing
        degrees == null || !degrees.isFinite() -> R.string.field_not_a_number
        abs(degrees) > limit -> outOfRange
        else -> null
    }
}

/** A value as typed, for feature.add and feature.values: numbers and dates full-width too (Numbers.plain); what is still no number goes as text, for the layer to refuse. */
internal fun fieldValue(type: String, text: String): JsonElement = when {
    text.isBlank() -> JsonNull
    type == "real" -> Numbers.double(text)?.let { JsonPrimitive(it) } ?: JsonPrimitive(text.trim())
    type == "integer" || type == "integer64" -> Numbers.long(text)?.let { JsonPrimitive(it) } ?: JsonPrimitive(text.trim())
    type == "boolean" -> JsonPrimitive(text == "true")
    type == "date" -> JsonPrimitive(Numbers.plain(text))
    else -> JsonPrimitive(text)
}
