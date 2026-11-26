package org.geocrew.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.core.project.PointNumbers
import org.geocrew.storage.Workspace
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * A project's main screen: its map (ADR 0006). The map fills the screen; the
 * buttons float over it, translucent, except the menu and the AI, which always
 * stand out. Adding a point is the main action. Editing works the QGIS way,
 * through actions (ADR 0005, 0010). A point goes in at the crosshair, for now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(graph: AppGraph, project: String, onBack: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val mapView = rememberMapView()
    val key = remember { TiandituKey.read(context) }
    var layers by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var layer by remember { mutableStateOf<String?>(null) }
    var collection by remember { mutableStateOf<JsonObject?>(null) }
    var styleLoads by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(false to false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var layerSheet by remember { mutableStateOf(false) }
    var ai by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<File?>(null) }
    val editing = (collection?.get("editing") as? JsonPrimitive)?.booleanOrNull == true
    val modified = (collection?.get("modified") as? JsonPrimitive)?.booleanOrNull == true
    val texts = FailureTexts(
        unsaved = stringResource(R.string.failure_unsaved),
        editing = stringResource(R.string.failure_editing),
        failed = stringResource(R.string.action_failed),
        start = stringResource(R.string.start_failed),
    )

    // One operation at a time: a tap while one runs is ignored, so a quick
    // double tap cannot start editing twice or add two points.
    val busy = remember { Mutex() }
    fun once(block: suspend () -> Unit) = scope.launch {
        if (!busy.tryLock()) return@launch
        try {
            block()
        } finally {
            busy.unlock()
        }
    }

    suspend fun reload() {
        val name = layer ?: return
        val loaded = graph.invoke(Workspace.FEATURES, buildJsonObject { put("layer", name) })
        if (loaded is Outcome.Done) collection = loaded.output.jsonObject else failure = texts.of(loaded)
        val scopeName = Editing.scope(name)
        history = graph.registry.canUndo(scopeName) to graph.registry.canRedo(scopeName)
    }

    suspend fun act(action: String, params: JsonObject = buildJsonObject { put("layer", layer.orEmpty()) }): Boolean {
        val outcome = graph.invoke(action, params)
        if (outcome !is Outcome.Done) failure = texts.of(outcome)
        reload()
        return outcome is Outcome.Done
    }

    // Open the project, unless it is open already (just made, or back from the settings).
    LaunchedEffect(project) {
        val current = graph.invoke(Workspace.CURRENT)
        val about = (current as? Outcome.Done)?.output?.jsonObject?.takeIf { (it["name"] as? JsonPrimitive)?.contentOrNull == project }
            ?: when (val opened = graph.invoke(Workspace.OPEN, buildJsonObject { put("name", project) })) {
                is Outcome.Done -> opened.output.jsonObject
                else -> {
                    failure = texts.of(opened)
                    return@LaunchedEffect
                }
            }
        layers = about["layers"]?.jsonArray.orEmpty().map { it.jsonObject }
        layer = pointLayer(layers)
        reload()
    }

    // The basemap, then the layer and its labels on top.
    LaunchedEffect(mapView) {
        val map = mapView.awaitMap()
        map.uiSettings.isLogoEnabled = false // MapLibre's licence does not ask for it; we credit the data ourselves
        map.uiSettings.isAttributionEnabled = false
        map.uiSettings.setCompassMargins(0, (96 * density).toInt(), (16 * density).toInt(), 0)
        map.cameraPosition = graph.cameras[project] ?: CameraPosition.Builder().target(LatLng(35.0, 105.0)).zoom(3.5).build()
        map.addOnMapClickListener { point ->
            val at = map.projection.toScreenLocation(point)
            val reach = 24 * density
            val hit = map.queryRenderedFeatures(RectF(at.x - reach, at.y - reach, at.x + reach, at.y + reach), SOURCE).firstOrNull()
            hit?.id()?.toLongOrNull()?.let { selected = it }
            hit != null
        }
        map.setStyle(Style.Builder().fromJson(key?.let(MapStyles::tiandituImagery) ?: MapStyles.BLANK)) { style ->
            style.addSource(GeoJsonSource(SOURCE))
            style.addLayer(
                CircleLayer(SOURCE, SOURCE).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor("#e53935"),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            // Point numbers to the right of the points; MapLibre keeps them from piling up.
            style.addLayer(
                SymbolLayer(LABELS, SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get(MapLabels.PROPERTY)),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_LEFT),
                    PropertyFactory.iconOffset(arrayOf(8f, 0f)),
                ).withFilter(Expression.has(MapLabels.PROPERTY)),
            )
            styleLoads++
        }
    }

    // Where the map was is kept for the next visit.
    DisposableEffect(mapView) {
        onDispose { mapView.getMapAsync { graph.cameras[project] = it.cameraPosition } }
    }

    var fitted by remember { mutableStateOf(graph.cameras.containsKey(project)) }
    LaunchedEffect(styleLoads, collection) {
        val features = collection?.get("features") as? JsonArray ?: return@LaunchedEffect
        val map = mapView.awaitMap()
        val style = map.style ?: return@LaunchedEffect
        val geoJson = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", MapLabels.label(features, numberField(collection), style, density))
        }
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(geoJson.toString())
        // The first visit shows the points there are.
        if (!fitted && styleLoads > 0) {
            fitted = true
            fit(map, features, density)
        }
    }

    fun addPoint() = once {
        val name = layer ?: return@once
        val center = mapView.awaitMap().cameraPosition.target?.wrap() ?: return@once
        val isEditing = (collection?.get("editing") as? JsonPrimitive)?.booleanOrNull == true
        if (!isEditing && !act(Editing.START)) return@once
        val numberField = numberField(collection)
        val numbers = collection?.get("features")?.jsonArray.orEmpty().map {
            (it.jsonObject["properties"]?.jsonObject?.get(numberField) as? JsonPrimitive)?.contentOrNull
        }
        act(
            Editing.ADD,
            buildJsonObject {
                put("layer", name)
                putJsonObject("geometry") {
                    put("type", "Point")
                    // GNSS and the map are WGS84, taken as CGCS2000 (ADR 0015); 1e-7 degrees is about a centimetre.
                    putJsonArray("coordinates") { add(round7(center.longitude)); add(round7(center.latitude)) }
                }
                putJsonObject("properties") {
                    if (numberField in fieldNames(collection)) put(numberField, PointNumbers.next(numbers))
                    timeField(collection)?.let { put(it, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()) }
                }
            },
        )
    }

    // Changing a feature starts editing its layer first, the QGIS way.
    fun change(action: String, params: JsonObject) = once {
        val isEditing = (collection?.get("editing") as? JsonPrimitive)?.booleanOrNull == true
        if (isEditing || act(Editing.START)) act(action, params)
    }

    // Leaves the map: stops editing and closes the project; unsaved edits are asked about first.
    suspend fun close() {
        val isEditing = (collection?.get("editing") as? JsonPrimitive)?.booleanOrNull == true
        if (isEditing && !act(Editing.STOP)) return
        graph.invoke(Workspace.CLOSE)
        onBack()
    }

    fun requestLeave() {
        if (modified) leaving = true else once { close() }
    }

    fun export(format: String) = once {
        when (val outcome = graph.invoke(Workspace.EXPORT, buildJsonObject { put("format", format) })) {
            is Outcome.Done -> exported = File(outcome.output.jsonObject.getValue("path").jsonPrimitive.content)
            else -> failure = texts.of(outcome)
        }
    }

    BackHandler { requestLeave() }

    // The map runs under the status bar, and imagery is mostly dark: light icons there,
    // whatever the theme; the theme's own come back when the map goes.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val wasLight = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = false
        onDispose { if (wasLight != null) bars.isAppearanceLightStatusBars = wasLight }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // A soft shade under the status bar keeps its light icons readable on any imagery.
        Box(
            Modifier.fillMaxWidth()
                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))),
        )
        Crosshair(Modifier.align(Alignment.Center))

        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onClick = ::requestLeave)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = TRANSLUCENT),
                    shadowElevation = 1.dp,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(project, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (editing) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(if (modified) R.string.map_unsaved_short else R.string.map_editing),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (modified) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                }
                }
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Rounded.Layers, stringResource(R.string.map_layers), onClick = { layerSheet = true })
            }
            if (key == null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = TRANSLUCENT),
                    shadowElevation = 1.dp,
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.map_no_key), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onSettings) { Text(stringResource(R.string.map_set_key)) }
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (editing) {
                val scopeParams = buildJsonObject { put("scope", Editing.scope(layer.orEmpty())) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundButton(
                        Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.map_undo),
                        onClick = { once { act(ActionRegistry.UNDO, scopeParams) } }, enabled = history.first,
                    )
                    RoundButton(
                        Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.map_redo),
                        onClick = { once { act(ActionRegistry.REDO, scopeParams) } }, enabled = history.second,
                    )
                    RoundButton(
                        Icons.Rounded.Check, stringResource(R.string.map_save), onClick = { once { act(Editing.SAVE) } },
                        enabled = modified, content = MaterialTheme.colorScheme.primary,
                    )
                    RoundButton(
                        Icons.Rounded.Close, stringResource(R.string.map_discard), onClick = { once { act(Editing.DISCARD) } },
                        enabled = modified, content = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(
                    Icons.Rounded.Menu, stringResource(R.string.map_menu), onClick = { menu = true },
                    size = 56.dp, solid = true, container = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                RoundButton(
                    Icons.Rounded.AddLocationAlt, stringResource(R.string.map_add_point), onClick = { addPoint() },
                    size = 76.dp, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary,
                    enabled = layer != null,
                )
                RoundButton(
                    Icons.Rounded.AutoAwesome, stringResource(R.string.map_ai), onClick = { ai = true },
                    size = 56.dp, solid = true, container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        if (key != null) {
            Text(
                stringResource(R.string.attribution_tianditu),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 2.dp),
            )
        }
    }

    if (menu) {
        ModalBottomSheet(onDismissRequest = { menu = false }) {
            Text(project, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SheetItem(Icons.Rounded.Layers, stringResource(R.string.map_layers)) { menu = false; layerSheet = true }
            SheetItem(Icons.Rounded.SaveAlt, stringResource(R.string.export_gpkg)) { menu = false; export("gpkg") }
            SheetItem(Icons.Rounded.Public, stringResource(R.string.export_kml)) { menu = false; export("kml") }
            SheetItem(Icons.Rounded.Settings, stringResource(R.string.settings)) { menu = false; onSettings() }
            SheetItem(Icons.AutoMirrored.Rounded.Logout, stringResource(R.string.project_close)) { menu = false; requestLeave() }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (layerSheet) {
        ModalBottomSheet(onDismissRequest = { layerSheet = false }) {
            Text(
                stringResource(R.string.map_layers),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            for (l in layers) {
                val name = (l["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val count = if (name == layer) collection?.get("features")?.jsonArray?.size else null
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = count?.let { { Text(pluralStringResource(R.plurals.points_count, it, it)) } },
                    leadingContent = { Icon(Icons.Rounded.Place, null, tint = Color(0xFFE53935)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    selected?.let { fid ->
        // A feature that has gone (an undone addition, say) shows no form.
        val feature = collection?.get("features")?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == fid }
        if (feature != null) {
            val properties = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val number = (properties[numberField(collection)] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            FeatureForm(
                title = number ?: stringResource(R.string.feature_untitled, fid),
                fields = formFields(collection),
                properties = properties,
                onDismiss = { selected = null },
                onSave = { values ->
                    selected = null
                    if (values.isNotEmpty()) {
                        change(Editing.VALUES, buildJsonObject { put("layer", layer.orEmpty()); put("fid", fid); put("values", values) })
                    }
                },
                onDelete = {
                    selected = null
                    change(Editing.DELETE, buildJsonObject { put("layer", layer.orEmpty()); put("fid", fid) })
                },
            )
        }
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_body, layer.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    once { if (act(Editing.SAVE)) close() }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        leaving = false
                        once { if (act(Editing.DISCARD)) close() }
                    }) { Text(stringResource(R.string.map_discard), color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
    if (ai) {
        AlertDialog(
            onDismissRequest = { ai = false },
            icon = { Icon(Icons.Rounded.AutoAwesome, null) },
            title = { Text(stringResource(R.string.map_ai)) },
            text = { Text(stringResource(R.string.map_ai_coming)) },
            confirmButton = { TextButton(onClick = { ai = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    exported?.let { file ->
        val title = stringResource(R.string.share_title)
        AlertDialog(
            onDismissRequest = { exported = null },
            icon = { Icon(Icons.Rounded.SaveAlt, null) },
            text = { Text(stringResource(R.string.exported, file.path)) },
            confirmButton = {
                TextButton(onClick = {
                    exported = null
                    share(context, file, title)
                }) { Text(stringResource(R.string.share)) }
            },
            dismissButton = { TextButton(onClick = { exported = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(message) },
        )
    }
}

/** Failures said in the user's language when their code is known (`unsaved`, `editing`). */
private class FailureTexts(val unsaved: String, val editing: String, val failed: String, val start: String) {
    fun of(outcome: Outcome): String = when (outcome) {
        is Outcome.Failed -> when (outcome.code) {
            "unsaved" -> unsaved
            "editing" -> editing
            AppGraph.START_FAILED -> start.format(outcome.message)
            else -> failed.format(outcome.message)
        }
        else -> failed.format(outcome.toString())
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** The layer points go into: the one made from a point template, else the first. */
private fun pointLayer(layers: List<JsonObject>): String? {
    val named = layers.map { (it["name"] as? JsonPrimitive)?.contentOrNull to (it["template"] as? JsonPrimitive)?.contentOrNull }
    return (named.firstOrNull { it.second in POINT_TEMPLATES } ?: named.firstOrNull())?.first
}

private val POINT_TEMPLATES = setOf("field-point", "geo-point")

private fun fieldNames(collection: JsonObject?): List<String> =
    collection?.get("fields")?.jsonArray.orEmpty().mapNotNull { (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull }

/** The field that numbers points, as the template named it in the project's language. */
private fun numberField(collection: JsonObject?): String = fieldNames(collection).firstOrNull { it in NUMBER_FIELDS } ?: "点号"

private fun timeField(collection: JsonObject?): String? = fieldNames(collection).firstOrNull { it in TIME_FIELDS }

private val NUMBER_FIELDS = setOf("点号", "Number")
private val TIME_FIELDS = setOf("记录时间", "Time")

private fun formFields(collection: JsonObject?): List<FormField> = collection?.get("fields")?.jsonArray.orEmpty().map {
    val f = it.jsonObject
    FormField((f["name"] as JsonPrimitive).content, (f["type"] as JsonPrimitive).content)
}

// Moves the camera to show the features: one point close up, several all at once.
private fun fit(map: MapLibreMap, features: JsonArray, density: Float) {
    val points = features.mapNotNull { f ->
        val xy = ((f as? JsonObject)?.get("geometry") as? JsonObject)?.get("coordinates") as? JsonArray ?: return@mapNotNull null
        if (xy.size < 2) null else LatLng(xy[1].jsonPrimitive.double, xy[0].jsonPrimitive.double)
    }
    if (points.isEmpty()) return
    // Points that span no distance (all in one place) are shown like a single one:
    // a box of no size would zoom in past the basemap's last level.
    val spread = points.maxOf { it.latitude } - points.minOf { it.latitude } + points.maxOf { it.longitude } - points.minOf { it.longitude }
    if (spread < 1e-6) {
        map.cameraPosition = CameraPosition.Builder().target(points.first()).zoom(15.0).build()
    } else {
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.fromLatLngs(points), (64 * density).toInt()))
    }
}

/** Hands an exported file to another app through the system's share sheet. */
private fun share(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val type = if (file.extension == "gpkg") "application/geopackage+sqlite3" else "application/vnd.google-earth.kml+xml"
    val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title))
}

/** Where a new point goes: the middle of the map. */
@Composable
private fun Crosshair(modifier: Modifier) {
    Canvas(modifier.size(28.dp)) {
        val red = Color(0xFFE53935)
        val stroke = 3.dp.toPx()
        drawLine(red, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke)
        drawLine(red, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
    }
}

/** A MapView that follows the screen's lifecycle. */
@Composable
private fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        HttpRequestUtil.setLogEnabled(false) // MapLibre logs the URLs of failed requests, and Tianditu's carry the key
        MapView(context).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
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
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

private suspend fun MapView.awaitMap(): MapLibreMap =
    suspendCancellableCoroutine { continuation -> getMapAsync { if (continuation.isActive) continuation.resume(it) } }

private fun round7(degrees: Double): Double = (degrees * 1e7).roundToLong() / 1e7

// Names of our own: the basemap's style has layers of its own (Tianditu's "labels", say).
private const val SOURCE = "geocrew-points"
private const val LABELS = "geocrew-point-labels"
