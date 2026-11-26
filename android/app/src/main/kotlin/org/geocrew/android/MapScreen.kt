package org.geocrew.android

import android.app.Activity
import android.graphics.RectF
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.core.project.PointNumbers
import org.geocrew.storage.Workspace
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * A project's main screen: its map (ADR 0006). The map fills the screen and the
 * buttons float over it. The fixed ones are opaque and always there: leave and
 * the title at the top left, save at the top right, and the menu, placing a
 * point and the AI along the bottom. The others (layers, undo, redo) are
 * translucent: they are the ones the user will be able to arrange. Editing goes
 * the QGIS way, through actions (ADR 0005, 0010): a change starts editing its
 * layer, one undo history and one save cover the whole project, and leaving
 * asks about edits not saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(graph: AppGraph, project: String, onBack: () -> Unit, onSettings: () -> Unit, onProjectSettings: () -> Unit) {
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
    var placing by remember { mutableStateOf(false) }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var layerSheet by remember { mutableStateOf(false) }
    var ai by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<File?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val modified = collection.flag("projectModified")
    val savedText = stringResource(R.string.saved)
    val texts = FailureTexts(
        unsaved = stringResource(R.string.failure_unsaved),
        failed = stringResource(R.string.action_failed),
        start = stringResource(R.string.start_failed),
    )

    // One operation at a time: a tap while one runs is ignored, so a quick
    // double tap cannot add two points or save twice.
    val busy = remember { Mutex() }
    fun once(block: suspend () -> Unit) = scope.launch {
        if (!busy.tryLock()) return@launch
        try {
            block()
        } finally {
            busy.unlock()
        }
    }

    // The point layer as it is now, and what the project's undo history holds.
    suspend fun reload() {
        val name = layer ?: return
        val loaded = graph.invoke(Workspace.FEATURES, buildJsonObject { put("layer", name) })
        if (loaded is Outcome.Done) collection = loaded.output.jsonObject else failure = texts.of(loaded)
        history = graph.registry.canUndo(Workspace.PROJECT_SCOPE) to graph.registry.canRedo(Workspace.PROJECT_SCOPE)
    }

    // Runs an action, then shows what it did; a failure is said in a dialog.
    suspend fun act(action: String, params: JsonObject): Boolean {
        val outcome = graph.invoke(action, params)
        if (outcome !is Outcome.Done) failure = texts.of(outcome)
        reload()
        return outcome is Outcome.Done
    }

    // A change starts editing its layer first, the QGIS way.
    suspend fun startEditing(name: String): Boolean =
        collection.flag("editing") || act(Editing.START, buildJsonObject { put("layer", name) })

    LaunchedEffect(project) {
        when (val opened = graph.open(project)) {
            is Outcome.Done -> {
                layers = opened.output.jsonObject["layers"]?.jsonArray.orEmpty().map { it.jsonObject }
                layer = pointLayer(layers)
                reload()
            }
            else -> failure = texts.of(opened)
        }
    }

    // The basemap, then the points and their names on top.
    LaunchedEffect(mapView) {
        val map = mapView.awaitMap()
        map.uiSettings.isLogoEnabled = false // MapLibre's licence does not ask for it; we credit the data ourselves
        map.uiSettings.isAttributionEnabled = false
        map.cameraPosition = graph.cameras[project] ?: CameraPosition.Builder().target(LatLng(35.0, 105.0)).zoom(3.5).build()
        map.addOnMapClickListener { point ->
            if (placing) return@addOnMapClickListener false
            val at = map.projection.toScreenLocation(point)
            val reach = 24 * density
            val hit = map.queryRenderedFeatures(RectF(at.x - reach, at.y - reach, at.x + reach, at.y + reach), POINT_SOURCE).firstOrNull()
            hit?.id()?.toLongOrNull()?.let { selected = it }
            hit != null
        }
        // While a point is being placed, where the crosshair is.
        map.addOnCameraMoveListener { if (placing) center = map.cameraPosition.target?.wrap() }
        map.setStyle(Style.Builder().fromJson(key?.let(MapStyles::tiandituImagery) ?: MapStyles.BLANK)) { style ->
            style.addSource(GeoJsonSource(POINT_SOURCE))
            style.addLayer(
                CircleLayer(POINT_SOURCE, POINT_SOURCE).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor("#e53935"),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            // Point names to the right of the points; MapLibre keeps them from piling up.
            style.addLayer(
                SymbolLayer(POINT_LABELS, POINT_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get(MapLabels.PROPERTY)),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_LEFT),
                    PropertyFactory.iconOffset(arrayOf(8f, 0f)),
                ).withFilter(Expression.has(MapLabels.PROPERTY)),
            )
            styleLoads++
        }
    }

    // The compass, when the map is turned, stands over the menu button, clear of
    // the system bars and the camera in either orientation.
    val insets = WindowInsets.safeDrawing
    val insetStart = insets.getLeft(LocalDensity.current, LocalLayoutDirection.current)
    val insetBottom = insets.getBottom(LocalDensity.current)
    LaunchedEffect(insetStart, insetBottom) {
        val map = mapView.awaitMap()
        map.uiSettings.compassGravity = Gravity.BOTTOM or Gravity.START
        map.uiSettings.setCompassMargins(insetStart + (24 * density).toInt(), 0, 0, insetBottom + (96 * density).toInt())
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
            put("features", nameField(collection)?.let { MapLabels.label(features, it, style, density) } ?: features)
        }
        style.getSourceAs<GeoJsonSource>(POINT_SOURCE)?.setGeoJson(geoJson.toString())
        // The first visit shows the points there are.
        if (!fitted && styleLoads > 0) {
            fitted = true
            fit(map, features, density)
        }
    }

    fun startPlacing() {
        placing = true
        scope.launch { center = mapView.awaitMap().cameraPosition.target?.wrap() }
    }

    // The new point's form comes first: its save adds the point, its cancel leaves
    // none, the QGIS way. The point is named after the last, D001 and on.
    fun place() = scope.launch {
        val at = mapView.awaitMap().cameraPosition.target?.wrap() ?: return@launch
        placing = false
        val field = nameField(collection)
        val names = collection?.get("features")?.jsonArray.orEmpty().map { f ->
            field?.let { (((f as? JsonObject)?.get("properties") as? JsonObject)?.get(it) as? JsonPrimitive)?.contentOrNull }
        }
        val values = if (field == null) emptyMap() else mapOf(field to JsonPrimitive(PointNumbers.next(names)))
        draft = Draft(round7(at.longitude), round7(at.latitude), JsonObject(values))
    }

    fun add(values: JsonObject, at: Pair<Double, Double>) = once {
        val name = layer ?: return@once
        if (!startEditing(name)) return@once
        act(Editing.ADD, buildJsonObject { put("layer", name); put("geometry", point(at)); put("properties", values) })
    }

    fun change(fid: Long, values: JsonObject, at: Pair<Double, Double>?) = once {
        val name = layer ?: return@once
        if (!startEditing(name)) return@once
        if (at != null && !act(Editing.GEOMETRY, buildJsonObject { put("layer", name); put("fid", fid); put("geometry", point(at)) })) return@once
        if (values.isNotEmpty()) act(Editing.VALUES, buildJsonObject { put("layer", name); put("fid", fid); put("values", values) })
    }

    fun delete(fid: Long) = once {
        val name = layer ?: return@once
        if (startEditing(name)) act(Editing.DELETE, buildJsonObject { put("layer", name); put("fid", fid) })
    }

    fun save() = once {
        if (act(Editing.SAVE_ALL, NONE)) scope.launch { snackbar.showSnackbar(savedText) }
    }

    // Leaves the map. A picture of it goes to the project's card at home first, if
    // the map gives one in time; then the project closes, its layers stopping.
    suspend fun close() {
        try {
            withTimeoutOrNull(THUMBNAIL_WAIT_MS) { saveThumbnail(mapView.awaitMap(), graph.thumbnail(project)) }
        } catch (e: IOException) {
            // The card keeps the picture it had.
        }
        when (val closed = graph.invoke(Workspace.CLOSE)) {
            is Outcome.Done -> onBack()
            else -> failure = texts.of(closed)
        }
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

    BackHandler(enabled = !placing) { requestLeave() }
    BackHandler(enabled = placing) { placing = false }

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
        if (placing) Crosshair(Modifier.align(Alignment.Center))

        // The buttons keep clear of the system bars and the camera, in either orientation.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.map_leave), onClick = ::requestLeave, solid = true)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) { Title(project, modified) }
                    Spacer(Modifier.width(8.dp))
                    RoundButton(
                        Icons.Rounded.Save, stringResource(R.string.save), onClick = { save() }, solid = true, enabled = modified,
                        container = if (modified) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        content = if (modified) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (placing) {
                    Spacer(Modifier.height(8.dp))
                    PlacingHint(center = { center })
                } else if (key == null) {
                    Spacer(Modifier.height(8.dp))
                    NoKey(onSettings)
                }
            }

            Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val projectScope = buildJsonObject { put("scope", Workspace.PROJECT_SCOPE) }
                RoundButton(Icons.Rounded.Layers, stringResource(R.string.map_layers), onClick = { layerSheet = true })
                RoundButton(
                    Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.map_undo),
                    onClick = { once { act(ActionRegistry.UNDO, projectScope) } }, enabled = history.first,
                )
                RoundButton(
                    Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.map_redo),
                    onClick = { once { act(ActionRegistry.REDO, projectScope) } }, enabled = history.second,
                )
            }

            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (placing) {
                    RoundButton(Icons.Rounded.Close, stringResource(R.string.cancel), onClick = { placing = false }, size = 56.dp, solid = true)
                    // The content form: the icon-and-text form hides its text from screen readers.
                    ExtendedFloatingActionButton(
                        onClick = { place() },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Rounded.Check, null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.place_here))
                    }
                    Spacer(Modifier.size(56.dp)) // keeps the confirm button in the middle
                } else {
                    RoundButton(
                        Icons.Rounded.Menu, stringResource(R.string.map_menu), onClick = { menu = true },
                        size = 56.dp, solid = true, container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    RoundButton(
                        Icons.Rounded.AddLocationAlt, stringResource(R.string.map_place), onClick = ::startPlacing,
                        size = 72.dp, solid = true, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary,
                        enabled = layer != null,
                    )
                    RoundButton(
                        Icons.Rounded.AutoAwesome, stringResource(R.string.map_ai), onClick = { ai = true },
                        size = 56.dp, solid = true, container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
            if (key != null) {
                Text(
                    stringResource(R.string.attribution_tianditu),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 2.dp),
                )
            }
        }
    }

    if (menu) {
        ModalBottomSheet(onDismissRequest = { menu = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(project, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SheetItem(Icons.Rounded.Layers, stringResource(R.string.map_layers)) { menu = false; layerSheet = true }
                SheetItem(Icons.Rounded.Tune, stringResource(R.string.project_settings)) { menu = false; onProjectSettings() }
                SheetItem(Icons.Rounded.SaveAlt, stringResource(R.string.export_gpkg)) { menu = false; export("gpkg") }
                SheetItem(Icons.Rounded.Public, stringResource(R.string.export_kml)) { menu = false; export("kml") }
                SheetItem(Icons.AutoMirrored.Rounded.Logout, stringResource(R.string.project_close)) { menu = false; requestLeave() }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (layerSheet) {
        // How many features each layer has, as edited.
        val counts by produceState(emptyMap<String, Int>(), layers) {
            value = layers.mapNotNull { l ->
                val name = l.name() ?: return@mapNotNull null
                val info = graph.invoke(Workspace.LAYER_INFO, buildJsonObject { put("layer", name) }) as? Outcome.Done
                info?.output?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull?.let { name to it.toInt() }
            }.toMap()
        }
        // Fully open at once: a half-open sheet dragged open ignored the back gesture afterwards.
        ModalBottomSheet(onDismissRequest = { layerSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.map_layers),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                for (l in layers) {
                    val name = l.name().orEmpty()
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = counts[name]?.let { { Text(pluralStringResource(R.plurals.points_count, it, it)) } },
                        leadingContent = { Icon(Icons.Rounded.Place, null, tint = Color(0xFFE53935)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    draft?.let { d ->
        FeatureForm(
            title = stringResource(R.string.place_new_point),
            fields = formFields(collection),
            properties = d.values,
            coordinates = d.longitude to d.latitude,
            isNew = true,
            onDismiss = { draft = null },
            onSave = { values, at ->
                draft = null
                add(values, at ?: (d.longitude to d.latitude))
            },
            onDelete = null,
        )
    }
    selected?.let { fid ->
        // A feature that has gone (an undone addition, say) shows no form.
        val feature = collection?.get("features")?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == fid }
        if (feature != null) {
            val properties = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val name = nameField(collection)?.let { (properties[it] as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }
            FeatureForm(
                title = name ?: stringResource(R.string.feature_untitled, fid),
                fields = formFields(collection),
                properties = properties,
                coordinates = coordinatesOf(feature),
                isNew = false,
                onDismiss = { selected = null },
                onSave = { values, at ->
                    selected = null
                    if (values.isNotEmpty() || at != null) change(fid, values, at)
                },
                onDelete = {
                    selected = null
                    delete(fid)
                },
            )
        }
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_body)) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    once { if (act(Editing.SAVE_ALL, NONE)) close() }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        leaving = false
                        once { if (act(Editing.DISCARD_ALL, NONE)) close() }
                    }) { Text(stringResource(R.string.dont_save), color = MaterialTheme.colorScheme.error) }
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

/** A point about to be added: where it goes and its first values. The form's save adds it. */
private data class Draft(val longitude: Double, val latitude: Double, val values: JsonObject)

/** The project's name, and whether it has edits to save; fixed and opaque. */
@Composable
private fun Title(project: String, modified: Boolean) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
        Row(Modifier.height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                project,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (modified) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.map_unsaved_short),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        }
    }
}

/** What to do while placing a point, and where the crosshair is. */
@Composable
private fun PlacingHint(center: () -> LatLng?) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.OpenWith, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.place_hint), style = MaterialTheme.typography.bodyMedium)
                center()?.let {
                    Text(
                        stringResource(R.string.place_coordinates, DEGREES.format(Locale.ROOT, it.longitude), DEGREES.format(Locale.ROOT, it.latitude)),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** No basemap without the user's Tianditu key: where to set it. */
@Composable
private fun NoKey(onSettings: () -> Unit) {
    // A colour with alpha matches no theme colour, so the text colour is given: it would be black on dark.
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = TRANSLUCENT),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 1.dp,
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.map_no_key), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text(stringResource(R.string.map_set_key)) }
        }
    }
}

private val NONE = JsonObject(emptyMap())

private const val DEGREES = "%.7f"

// How long leaving waits for the map's picture.
private const val THUMBNAIL_WAIT_MS = 2_000L

private fun JsonObject?.flag(name: String): Boolean = (this?.get(name) as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.name(): String? = (this["name"] as? JsonPrimitive)?.contentOrNull

// GNSS and the map are WGS84, taken as CGCS2000 (ADR 0015).
private fun point(at: Pair<Double, Double>): JsonObject = buildJsonObject {
    put("type", "Point")
    putJsonArray("coordinates") { add(at.first); add(at.second) }
}
