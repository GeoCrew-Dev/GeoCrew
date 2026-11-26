package org.geocrew.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The map of the open project (ADR 0006): the Tianditu basemap, a layer of
 * points, and editing it the QGIS way, all through actions (ADR 0005, 0010).
 * A point goes in at the crosshair in the middle of the map, for now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(graph: AppGraph, project: String, layer: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mapView = rememberMapView()
    var key by remember { mutableStateOf(TiandituKey.read(context)) }
    var askKey by remember { mutableStateOf(false) }
    var collection by remember { mutableStateOf<JsonObject?>(null) }
    var styleLoads by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false to false) }
    val layerParams = buildJsonObject { put("layer", layer) }
    val scopeName = Editing.scope(layer)
    val editing = (collection?.get("editing") as? JsonPrimitive)?.booleanOrNull == true
    val modified = (collection?.get("modified") as? JsonPrimitive)?.booleanOrNull == true

    suspend fun act(action: String, params: JsonObject = layerParams): Boolean {
        val outcome = graph.invoke(action, params)
        if (outcome !is Outcome.Done) failure = (outcome as? Outcome.Failed)?.message ?: outcome.toString()
        val reloaded = graph.invoke(Workspace.FEATURES, layerParams)
        if (reloaded is Outcome.Done) collection = reloaded.output.jsonObject
        history = graph.registry.canUndo(scopeName) to graph.registry.canRedo(scopeName)
        return outcome is Outcome.Done
    }

    LaunchedEffect(Unit) {
        val loaded = graph.invoke(Workspace.FEATURES, layerParams)
        if (loaded is Outcome.Done) collection = loaded.output.jsonObject else failure = loaded.toString()
    }

    // The basemap, again whenever the key changes; the layer goes on top of it.
    LaunchedEffect(mapView, key) {
        val map = mapView.awaitMap()
        map.uiSettings.isLogoEnabled = false // MapLibre's licence does not ask for it; we credit the data ourselves
        map.uiSettings.isAttributionEnabled = false
        if (styleLoads == 0) map.cameraPosition = CameraPosition.Builder().target(LatLng(35.0, 105.0)).zoom(3.5).build()
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
            styleLoads++
        }
    }

    LaunchedEffect(styleLoads, collection) {
        val features = collection?.get("features") ?: return@LaunchedEffect
        val geoJson = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", features)
        }
        mapView.awaitMap().style?.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(geoJson.toString())
    }

    fun addPoint() = scope.launch {
        val center = mapView.awaitMap().cameraPosition.target ?: return@launch
        if (!editing && !act(Editing.START)) return@launch
        val numbers = collection?.get("features")?.jsonArray.orEmpty().map {
            (it.jsonObject["properties"]?.jsonObject?.get(NUMBER) as? JsonPrimitive)?.contentOrNull
        }
        act(
            Editing.ADD,
            buildJsonObject {
                put("layer", layer)
                putJsonObject("geometry") {
                    put("type", "Point")
                    // GNSS and the map are WGS84, taken as CGCS2000 (ADR 0015); 1e-7 degrees is about a centimetre.
                    putJsonArray("coordinates") { add(round7(center.longitude)); add(round7(center.latitude)) }
                }
                putJsonObject("properties") {
                    put(NUMBER, PointNumbers.next(numbers))
                    put(TIME, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                }
            },
        )
    }

    fun leave() = scope.launch {
        if (!editing || act(Editing.STOP)) onBack()
    }

    BackHandler {
        if (modified) leaving = true else leave()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project) },
                navigationIcon = { TextButton(onClick = { if (modified) leaving = true else leave() }) { Text(stringResource(R.string.back)) } },
                actions = { TextButton(onClick = { askKey = true }) { Text(stringResource(R.string.map_key)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { addPoint() }) { Text(stringResource(R.string.map_add_point)) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Text("+", color = Color(0xFFE53935), fontSize = 32.sp, modifier = Modifier.align(Alignment.Center))
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (key == null) {
                    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.map_no_key), Modifier.padding(12.dp))
                    }
                }
                if (editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { scope.launch { act(ActionRegistry.UNDO, scopeParams(scopeName)) } }, enabled = history.first) {
                            Text(stringResource(R.string.map_undo))
                        }
                        FilledTonalButton(onClick = { scope.launch { act(ActionRegistry.REDO, scopeParams(scopeName)) } }, enabled = history.second) {
                            Text(stringResource(R.string.map_redo))
                        }
                        FilledTonalButton(onClick = { scope.launch { act(Editing.SAVE) } }, enabled = modified) { Text(stringResource(R.string.map_save)) }
                        FilledTonalButton(onClick = { scope.launch { act(Editing.DISCARD) } }, enabled = modified) {
                            Text(stringResource(R.string.map_discard))
                        }
                    }
                }
            }
            if (key != null) {
                Text(
                    stringResource(R.string.attribution_tianditu),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(8.dp),
                )
            }
        }
    }

    if (askKey) {
        KeyDialog(current = key.orEmpty(), onDismiss = { askKey = false }, onSave = {
            TiandituKey.write(context, it)
            key = it
            askKey = false
        })
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_body, layer)) },
            confirmButton = {
                TextButton(onClick = { leaving = false; scope.launch { if (act(Editing.SAVE)) leave() } }) { Text(stringResource(R.string.map_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { leaving = false; scope.launch { if (act(Editing.DISCARD)) leave() } }) {
                        Text(stringResource(R.string.map_discard))
                    }
                }
            },
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(stringResource(R.string.action_failed, message)) },
        )
    }
}

@Composable
private fun KeyDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val valid = TiandituKey.isValid(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.key_hint)) },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                supportingText = { if (text.isNotEmpty() && !valid) Text(stringResource(R.string.key_invalid)) },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = valid) { Text(stringResource(R.string.map_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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

private suspend fun MapView.awaitMap(): MapLibreMap = suspendCoroutine { continuation -> getMapAsync { continuation.resume(it) } }

private fun scopeParams(scope: String) = buildJsonObject { put("scope", scope) }

private fun round7(degrees: Double): Double = (degrees * 1e7).roundToLong() / 1e7

private const val SOURCE = "layer"
private const val NUMBER = "点号"
private const val TIME = "记录时间"
