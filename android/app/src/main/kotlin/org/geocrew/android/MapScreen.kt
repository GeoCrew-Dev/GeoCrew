package org.geocrew.android

import android.Manifest
import android.app.Activity
import android.hardware.GeomagneticField
import android.graphics.RectF
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.core.edit.FieldType
import org.geocrew.core.project.PointNumbers
import org.geocrew.storage.Crs
import org.geocrew.storage.Workspace
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
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
fun MapScreen(
    graph: AppGraph,
    project: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onProjectSettings: () -> Unit,
    onLayers: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val mapView = rememberMapView()
    val key = remember { TiandituKey.read(context) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var layers by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    // Each layer as layer.features gives it, by name.
    var collections by remember { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    var styleLoads by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(false to false) }
    var placing by remember { mutableStateOf(false) }
    // The layer the point being placed goes into.
    var target by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var rememberNew by remember { mutableStateOf(false) }
    // A point going to another layer (its layer and fid), to a new one made like its own, and a move that would drop values.
    var moving by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var movingNew by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var dropping by remember { mutableStateOf<Move?>(null) }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var drawn by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var selected by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // Layers in a projected CRS, by name: their points can be entered as X and Y.
    var projected by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var basemaps by remember { mutableStateOf<List<BasemapInfo>>(emptyList()) }
    // Where the map looks, as it settles: the Gauss-Kruger zone of a new layer follows it.
    var mapCenter by remember { mutableStateOf<LatLng?>(null) }
    val convert: suspend (String, String, Double, Double) -> Pair<Double, Double>? = { from, to, x, y ->
        val moved = graph.invoke(Workspace.CRS_TRANSFORM, buildJsonObject { put("from", from); put("to", to); putJsonArray("xy") { add(x); add(y) } })
        val xy = ((moved as? Outcome.Done)?.output?.jsonObject?.get("xy") as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
        if (xy != null && xy.size == 2 && xy[0] != null && xy[1] != null) xy[0]!! to xy[1]!! else null
    }
    val describeCrs: suspend (String) -> String? = { srs ->
        ((graph.invoke(Workspace.CRS_DESCRIBE, buildJsonObject { put("srs", srs) }) as? Outcome.Done)?.output as? JsonObject)?.text("name")
    }
    // One per layer, kept while the layers stay: the form keys its state on it.
    val formCrs = remember(projected) { projected.mapValues { (_, srs) -> FormCrs(srs, convert) } }
    var failure by remember { mutableStateOf<String?>(null) }
    // The phone's position: the system asks for permission the first time; no fix is said plainly.
    val noFixText = stringResource(R.string.no_fix)
    // Whether the map may show where the phone is; granted later, it shows from then on.
    var located by remember { mutableStateOf(mayLocate(context)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) located = true }
    // Only while the app is in front: the map stays composed when the user goes elsewhere.
    val inFront = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val phone by rememberPhonePosition(located && inFront)
    val heading by rememberHeading(located && inFront)
    // The zoom the heading cone is drawn for, in quarter steps, so it keeps its size on screen.
    var coneZoom by remember { mutableStateOf(0.0) }
    val locate: suspend () -> Fix? = {
        if (!mayLocate(context)) {
            askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            null
        } else {
            currentFix(context).also { if (it == null) failure = noFixText }
        }
    }
    var locating by remember { mutableStateOf(false) }
    var placingAccuracy by remember { mutableStateOf<Float?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // The measuring tool: what it measures and the points tapped so far.
    var measuring by remember { mutableStateOf<MeasureMode?>(null) }
    var measured by remember { mutableStateOf(emptyList<LatLng>()) }
    var goingTo by remember { mutableStateOf(false) }
    var ai by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<File?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val modified = collections.values.any { it.flag("projectModified") }
    val points = pointLayers(layers) { name -> (collections[name]?.get("features") as? JsonArray)?.size ?: 0 }
    val savedText = stringResource(R.string.saved)
    val addedText = stringResource(R.string.place_added)
    val outsideText = stringResource(R.string.position_outside)
    val movedText = stringResource(R.string.moved)
    val changeLayerText = stringResource(R.string.change_layer)
    val suggestedLayer = stringResource(R.string.layer_default_name)
    val texts = FailureTexts(
        unsaved = stringResource(R.string.failure_unsaved),
        failed = stringResource(R.string.action_failed),
        start = stringResource(R.string.start_failed),
        empty = stringResource(R.string.failure_empty),
        crs = stringResource(R.string.failure_crs),
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

    // The layers as they are now, and what the project's undo history holds.
    suspend fun reload() {
        val loaded = LinkedHashMap<String, JsonObject>()
        for (name in layers.mapNotNull { it.name() }) {
            // In longitude and latitude, as the map wants, whatever CRS the layer is in.
            when (val read = graph.invoke(Workspace.FEATURES, buildJsonObject { put("layer", name); put("srs", Crs.LONGITUDE_LATITUDE) })) {
                is Outcome.Done -> loaded[name] = read.output.jsonObject
                else -> failure = texts.of(read)
            }
        }
        collections = loaded
        history = graph.registry.canUndo(Workspace.PROJECT_SCOPE) to graph.registry.canRedo(Workspace.PROJECT_SCOPE)
    }

    // The project as project.current describes it: its id, layers and basemaps, and which layers are in a projected CRS.
    suspend fun show(about: JsonObject) {
        projectId = (about["id"] as? JsonPrimitive)?.contentOrNull
        layers = about["layers"]?.jsonArray.orEmpty().map { it.jsonObject }
        basemaps = listedBasemaps(about)
        val kinds = HashMap<String, String>()
        for (l in layers) {
            val name = l.name() ?: continue
            val srs = l.text("srs") ?: continue
            if (Crs.same(srs, Crs.LONGITUDE_LATITUDE)) continue
            val described = graph.invoke(Workspace.CRS_DESCRIBE, buildJsonObject { put("srs", srs) }) as? Outcome.Done
            if (described?.output?.jsonObject?.text("projected") == "true") kinds[name] = srs
        }
        projected = kinds
        reload()
    }

    // A position as a point in [layer]'s own CRS, converted when it is in another; null with the failure said.
    suspend fun inLayer(layer: String, position: Position): JsonObject? {
        val own = layers.firstOrNull { it.name() == layer }?.text("srs")
        if (own == null || Crs.same(own, position.srs)) return point(position.x to position.y)
        val moved = convert(position.srs, own, position.x, position.y)
        if (moved == null) failure = outsideText
        return moved?.let(::point)
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
        collections[name].flag("editing") || act(Editing.START, buildJsonObject { put("layer", name) })

    LaunchedEffect(project) {
        when (val opened = graph.open(project)) {
            is Outcome.Done -> {
                show(opened.output.jsonObject)
                // Every layer opens for editing, so edits left unsaved when the app was
                // stopped come back at once: they show, and new points are named after them.
                val closed = layers.mapNotNull { it.name() }.filterNot { collections[it].flag("editing") }
                for (name in closed) {
                    val started = graph.invoke(Editing.START, buildJsonObject { put("layer", name) })
                    if (started !is Outcome.Done) failure = texts.of(started)
                }
                if (closed.isNotEmpty()) reload()
                // Back from the layers page with a layer to show in full, or from a table with a feature to open.
                graph.zoomTo?.let { zoom ->
                    graph.zoomTo = null
                    val features = (collections[zoom.layer]?.get("features") as? JsonArray).orEmpty()
                    val shown = if (zoom.fid == null) features else features.filter { (it as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull == zoom.fid }
                    fit(mapView.awaitMap(), JsonArray(shown), density)
                    if (zoom.fid != null && shown.isNotEmpty()) selected = zoom.layer to zoom.fid
                }
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
            // While measuring, a tap adds a point to the measurement.
            if (measuring != null) {
                measured = withPoint(measuring ?: return@addOnMapClickListener true, measured, point.wrap())
                return@addOnMapClickListener true
            }
            val at = map.projection.toScreenLocation(point)
            val reach = 24 * density
            val hit = map.queryRenderedFeatures(RectF(at.x - reach, at.y - reach, at.x + reach, at.y + reach), POINT_SOURCE).firstOrNull()
            // Ids are "layer index/fid": points of every layer share the source.
            val (index, fid) = hit?.id()?.split("/")?.takeIf { it.size == 2 } ?: return@addOnMapClickListener false
            val name = layers.getOrNull(index.toIntOrNull() ?: -1)?.name()
            if (name != null) fid.toLongOrNull()?.let { selected = name to it }
            true
        }
        map.addOnCameraIdleListener { mapCenter = map.cameraPosition.target?.wrap() }
        map.addOnCameraMoveListener { coneZoom = Math.round(map.cameraPosition.zoom * 4) / 4.0 }
        coneZoom = Math.round(map.cameraPosition.zoom * 4) / 4.0
        // A picture for the home screen only once the map has been drawn in full.
        mapView.addOnDidBecomeIdleListener { drawn = true }
        // While a point is being placed, where the crosshair is.
        map.addOnCameraMoveListener { if (placing) center = map.cameraPosition.target?.wrap() }
        map.setStyle(Style.Builder().fromJson(key?.let(MapStyles::tiandituImagery) ?: MapStyles.BLANK)) { style ->
            style.addSource(GeoJsonSource(POINT_SOURCE))
            style.addLayer(
                CircleLayer(POINT_SOURCE, POINT_SOURCE).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor(Expression.get(COLOR)),
                    PropertyFactory.circleOpacity(Expression.get(OPACITY)),
                    PropertyFactory.circleStrokeOpacity(Expression.get(OPACITY)),
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
                    PropertyFactory.iconOpacity(Expression.get(OPACITY)),
                ).withFilter(Expression.has(MapLabels.PROPERTY)),
            )
            // The measurement on top of everything: its line or polygon, and its points.
            style.addSource(GeoJsonSource(MEASURE_SOURCE))
            style.addLayer(
                FillLayer("$MEASURE_SOURCE-fill", MEASURE_SOURCE).withProperties(
                    PropertyFactory.fillColor(MEASURE_COLOR),
                    PropertyFactory.fillOpacity(0.2f),
                ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Polygon"))),
            )
            style.addLayer(
                LineLayer("$MEASURE_SOURCE-line", MEASURE_SOURCE).withProperties(
                    PropertyFactory.lineColor(MEASURE_COLOR),
                    PropertyFactory.lineWidth(3f),
                ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString"))),
            )
            style.addLayer(
                CircleLayer("$MEASURE_SOURCE-points", MEASURE_SOURCE).withProperties(
                    PropertyFactory.circleRadius(5f),
                    PropertyFactory.circleColor(MEASURE_COLOR),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2f),
                ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point"))),
            )
            addMyLocationLayers(style)
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

    LaunchedEffect(styleLoads, measuring, measured) {
        val style = mapView.awaitMap().style ?: return@LaunchedEffect
        val mode = measuring
        val shown = if (mode == null) measureGeoJson(MeasureMode.DISTANCE, emptyList()) else measureGeoJson(mode, measured)
        style.getSourceAs<GeoJsonSource>(MEASURE_SOURCE)?.setGeoJson(shown.toString())
    }

    // The phone's position and heading on the map; the compass gives magnetic north, the map wants true north.
    LaunchedEffect(styleLoads, phone, heading, coneZoom) {
        val style = mapView.awaitMap().style ?: return@LaunchedEffect
        val at = phone
        val north = if (at == null || heading == null) heading else
            (heading!! + GeomagneticField(at.latitude.toFloat(), at.longitude.toFloat(), 0f, System.currentTimeMillis()).declination + 360) % 360
        style.getSourceAs<GeoJsonSource>(ME_SOURCE)?.setGeoJson(myLocationGeoJson(at, north, coneZoom).toString())
    }

    // Each basemap as the project says: shown or not, and how opaque (Tianditu's imagery is "base", its annotation "labels").
    LaunchedEffect(styleLoads, basemaps) {
        val style = mapView.awaitMap().style ?: return@LaunchedEffect
        for (b in basemaps) {
            val layer = style.getLayer(if (b.id == "cia") "labels" else "base") ?: continue
            layer.setProperties(
                PropertyFactory.visibility(if (b.visible) Property.VISIBLE else Property.NONE),
                PropertyFactory.rasterOpacity(b.opacity),
            )
        }
    }

    var fitted by remember { mutableStateOf(graph.cameras.containsKey(project)) }
    LaunchedEffect(styleLoads, collections) {
        if (collections.isEmpty()) return@LaunchedEffect
        val map = mapView.awaitMap()
        val style = map.style ?: return@LaunchedEffect
        // Every point layer in one source, each point with its layer's colour and an id that names its layer.
        // Bottom of the list first, so the first layer is drawn on top; hidden layers stay off the map.
        val features = JsonArray(
            layers.mapIndexed { index, l -> index to l }.reversed().flatMap { (index, l) ->
                if ((l["visible"] as? JsonPrimitive)?.contentOrNull == "false") return@flatMap emptyList()
                val opacity = (l["opacity"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 1.0
                val collection = collections[l.name()] ?: return@flatMap emptyList()
                if ((l["geometry"] as? JsonPrimitive)?.contentOrNull != "POINT") return@flatMap emptyList()
                val own = collection["features"] as? JsonArray ?: return@flatMap emptyList()
                val labelled = nameField(collection)?.let { MapLabels.label(own, it, style, density) } ?: own
                labelled.map { tagged(it.jsonObject, index, layerColorHex(madeIndex(l)), opacity) }
            },
        )
        val geoJson = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", features)
        }
        style.getSourceAs<GeoJsonSource>(POINT_SOURCE)?.setGeoJson(geoJson.toString())
        // The first visit shows the points there are.
        if (!fitted && styleLoads > 0) {
            fitted = true
            fit(map, features, density)
        }
    }

    fun startPlacing(layer: String) {
        target = layer
        placingAccuracy = null // a fix from before is not where the crosshair is now
        measuring = null // one tool at a time: placing ends a measurement
        measured = emptyList()
        placing = true
        scope.launch { center = mapView.awaitMap().cameraPosition.target?.wrap() }
    }

    // Placing asks which layer the point goes into: not when the user chose to
    // remember one that is still there; and a project with no point layer makes one first.
    fun requestPlace() {
        val id = projectId ?: return
        val remembered = graph.settings.placingLayer(id)
        // A hidden layer takes no new points (they would not show); a remembered one that is hidden asks, and is kept.
        val kept = points.firstOrNull { it.id == remembered && it.visible }
        if (remembered != null && points.none { it.id == remembered }) graph.settings.setPlacingLayer(id, null) // the layer has gone: ask again
        when {
            kept != null -> startPlacing(kept.name)
            points.isEmpty() -> {
                rememberNew = false
                naming = true
            }
            else -> picking = true
        }
    }

    fun createLayer(name: String, srs: String?) = once {
        val params = buildJsonObject { put("name", name); put("language", AppLanguage.speaking(context)); if (srs != null) put("srs", srs) }
        when (val made = graph.invoke(Workspace.LAYER_CREATE, params)) {
            is Outcome.Done -> {
                show(made.output.jsonObject)
                val id = made.output.jsonObject["layers"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.name() == name }
                    ?.let { (it["id"] as? JsonPrimitive)?.contentOrNull }
                if (rememberNew && id != null) projectId?.let { graph.settings.setPlacingLayer(it, id) }
                startPlacing(name)
            }
            else -> failure = texts.of(made)
        }
    }

    // The new point's form comes first: its save adds the point, its cancel leaves
    // none, the QGIS way. The point is named after the last in its layer, D001 and on.
    fun place() = scope.launch {
        val layer = target ?: return@launch
        val at = mapView.awaitMap().cameraPosition.target?.wrap() ?: return@launch
        placing = false
        val collection = collections[layer]
        val field = nameField(collection)
        val names = collection?.get("features")?.jsonArray.orEmpty().map { f ->
            field?.let { (((f as? JsonObject)?.get("properties") as? JsonObject)?.get(it) as? JsonPrimitive)?.contentOrNull }
        }
        val values = if (field == null) emptyMap() else mapOf(field to JsonPrimitive(PointNumbers.next(names)))
        draft = Draft(layer, round7(at.longitude), round7(at.latitude), JsonObject(values))
    }

    // After adding, the snackbar names the layer and offers to change it: the quick way back from the wrong one.
    fun add(layer: String, values: JsonObject, at: Position) = once {
        if (!startEditing(layer)) return@once
        val geometry = inLayer(layer, at) ?: return@once
        val added = graph.invoke(Editing.ADD, buildJsonObject { put("layer", layer); put("geometry", geometry); put("properties", values) })
        reload()
        if (added !is Outcome.Done) {
            failure = texts.of(added)
            return@once
        }
        val fid = (added.output.jsonObject["fid"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@once
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val answer = snackbar.showSnackbar(addedText.format(layer), actionLabel = changeLayerText, duration = SnackbarDuration.Long)
            if (answer == SnackbarResult.ActionPerformed) moving = layer to fid
        }
    }

    fun move(m: Move) = once {
        if (!startEditing(m.layer) || !startEditing(m.to)) return@once
        if (act(Editing.MOVE, buildJsonObject { put("layer", m.layer); put("fid", m.fid); put("to", m.to) })) {
            scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(movedText.format(m.to)) }
        }
    }

    // A move that would drop values asks first; one that keeps them all goes ahead.
    fun requestMove(layer: String, fid: Long, to: String) {
        val feature = collections[layer]?.get("features")?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == fid } ?: return
        // A value goes along when the other layer has a field of that name that takes it, as the move itself decides.
        val takes = formFields(collections[to]).associate { it.name to FieldType.of(it.type) }
        val lost = (feature["properties"] as? JsonObject).orEmpty()
            .filter { (name, value) -> value !is JsonNull && takes[name]?.accepts(value) != true }.keys.toList()
        val m = Move(layer, fid, to, lost)
        if (lost.isEmpty()) move(m) else dropping = m
    }

    // A new layer like the point's own, so the point keeps every value, then the move into it.
    fun moveToNewLayer(name: String, like: String, fid: Long) = once {
        when (val made = graph.invoke(Workspace.LAYER_CREATE, buildJsonObject { put("name", name); put("like", like) })) {
            is Outcome.Done -> show(made.output.jsonObject)
            else -> {
                failure = texts.of(made)
                return@once
            }
        }
        if (!startEditing(like) || !startEditing(name)) return@once
        if (act(Editing.MOVE, buildJsonObject { put("layer", like); put("fid", fid); put("to", name) })) {
            scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(movedText.format(name)) }
        }
    }

    fun change(layer: String, fid: Long, values: JsonObject, at: Position?) = once {
        if (!startEditing(layer)) return@once
        if (at != null) {
            val geometry = inLayer(layer, at) ?: return@once
            if (!act(Editing.GEOMETRY, buildJsonObject { put("layer", layer); put("fid", fid); put("geometry", geometry) })) return@once
        }
        if (values.isNotEmpty()) act(Editing.VALUES, buildJsonObject { put("layer", layer); put("fid", fid); put("values", values) })
    }

    fun delete(layer: String, fid: Long) = once {
        if (startEditing(layer)) act(Editing.DELETE, buildJsonObject { put("layer", layer); put("fid", fid) })
    }

    fun save() = once {
        if (act(Editing.SAVE_ALL, NONE)) scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(savedText) }
    }

    // Leaves the map. A picture of it goes to the project's card at home first, if
    // the map has been drawn and gives one in time; then the project closes, its layers stopping.
    suspend fun close() {
        try {
            if (drawn) withTimeoutOrNull(THUMBNAIL_WAIT_MS) { saveThumbnail(mapView.awaitMap(), graph.thumbnail(project)) }
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

    // Every point of every layer in view.
    fun showAll() = scope.launch {
        val shown = layers.filter { it.text("visible") != "false" }.mapNotNull { it.name() }
        val all = JsonArray(shown.flatMap { (collections[it]?.get("features") as? JsonArray).orEmpty() })
        fit(mapView.awaitMap(), all, density)
    }

    fun northUp() = scope.launch { mapView.awaitMap().resetNorth() }

    fun goTo(longitude: Double, latitude: Double) = scope.launch {
        val map = mapView.awaitMap()
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), maxOf(map.cameraPosition.zoom, 15.0)))
    }

    // To where the phone is: the map's last position at once, else one asked for.
    fun showMe() {
        if (!mayLocate(context)) {
            askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        scope.launch {
            val map = mapView.awaitMap()
            val at = phone?.let { LatLng(it.latitude, it.longitude) } ?: locate()?.let { LatLng(it.latitude, it.longitude) } ?: return@launch
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(at, maxOf(map.cameraPosition.zoom, 16.0)))
        }
    }

    fun measure(mode: MeasureMode) {
        placing = false
        measuring = mode
        measured = emptyList()
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
                    PlacingHint(
                        layer = target.orEmpty(),
                        center = { center },
                        onChangeLayer = { picking = true },
                        locating = locating,
                        accuracy = placingAccuracy,
                        onLocate = {
                            // The crosshair goes to where the phone is.
                            if (!locating) {
                                locating = true
                                scope.launch {
                                    val fix = locate()
                                    locating = false
                                    if (fix != null) {
                                        placingAccuracy = fix.accuracy
                                        val map = mapView.awaitMap()
                                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), maxOf(map.cameraPosition.zoom, 17.0)))
                                    }
                                }
                            }
                        },
                    )
                } else if (measuring != null) {
                    Spacer(Modifier.height(8.dp))
                    MeasurePanel(
                        mode = measuring ?: MeasureMode.DISTANCE,
                        points = measured,
                        onUndo = { measured = measured.dropLast(1) },
                        onClear = { measured = emptyList() },
                        onClose = {
                            measuring = null
                            measured = emptyList()
                        },
                    )
                } else if (key == null) {
                    Spacer(Modifier.height(8.dp))
                    NoKey(onSettings)
                }
            }

            Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val projectScope = buildJsonObject { put("scope", Workspace.PROJECT_SCOPE) }
                RoundButton(Icons.Rounded.Layers, stringResource(R.string.map_layers), onClick = onLayers)
                RoundButton(
                    Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.map_undo),
                    onClick = { once { act(ActionRegistry.UNDO, projectScope) } }, enabled = history.first,
                )
                RoundButton(
                    Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.map_redo),
                    onClick = { once { act(ActionRegistry.REDO, projectScope) } }, enabled = history.second,
                )
                RoundButton(Icons.Rounded.MyLocation, stringResource(R.string.my_position), onClick = ::showMe)
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
                        Icons.Rounded.AddLocationAlt, stringResource(R.string.map_place), onClick = ::requestPlace,
                        size = 72.dp, solid = true, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary,
                        enabled = projectId != null,
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
        // The menu in levels (backlog: tiered menus of GIS and map tools); groups fill up tool by tool.
        MenuSheet(
            title = project,
            entries = listOf(
                MenuTool(stringResource(R.string.map_layers), Icons.Rounded.Layers, onLayers),
                MenuGroup(
                    stringResource(R.string.menu_map), Icons.Rounded.Map,
                    listOf(
                        MenuTool(stringResource(R.string.tool_show_all), Icons.Rounded.ZoomOutMap, ::showAll),
                        MenuTool(stringResource(R.string.tool_go_to), Icons.Rounded.NearMe) { goingTo = true },
                        MenuTool(stringResource(R.string.tool_north_up), Icons.Rounded.Explore, ::northUp),
                    ),
                ),
                MenuGroup(
                    stringResource(R.string.menu_measure), Icons.Rounded.Straighten,
                    listOf(
                        MenuTool(stringResource(R.string.tool_measure_distance), Icons.Rounded.Straighten) { measure(MeasureMode.DISTANCE) },
                        MenuTool(stringResource(R.string.tool_measure_area), Icons.Rounded.SquareFoot) { measure(MeasureMode.AREA) },
                        MenuTool(stringResource(R.string.tool_azimuth), Icons.Rounded.Explore) { measure(MeasureMode.AZIMUTH) },
                        MenuTool(stringResource(R.string.tool_pick), Icons.Rounded.PinDrop) { measure(MeasureMode.PICK) },
                    ),
                ),
                MenuGroup(
                    stringResource(R.string.menu_data), Icons.Rounded.Storage,
                    listOf(
                        MenuTool(stringResource(R.string.export_gpkg), Icons.Rounded.SaveAlt) { export("gpkg") },
                        MenuTool(stringResource(R.string.export_kml), Icons.Rounded.Public) { export("kml") },
                    ),
                ),
                MenuGroup(
                    stringResource(R.string.project_section), Icons.Rounded.Folder,
                    listOf(
                        MenuTool(stringResource(R.string.project_settings), Icons.Rounded.Tune, onProjectSettings),
                        MenuTool(stringResource(R.string.project_close), Icons.AutoMirrored.Rounded.Logout, ::requestLeave),
                    ),
                ),
            ),
            onDismiss = { menu = false },
        )
    }
    if (goingTo) {
        GoToDialog(
            onDismiss = { goingTo = false },
            onGo = { longitude, latitude ->
                goingTo = false
                goTo(longitude, latitude)
            },
        )
    }
    if (picking) {
        LayerPicker(
            title = stringResource(R.string.pick_layer_title),
            layers = points.filter { it.visible },
            onPick = { layer, remember ->
                picking = false
                if (remember) projectId?.let { graph.settings.setPlacingLayer(it, layer.id) }
                startPlacing(layer.name)
            },
            onNew = { remember ->
                picking = false
                rememberNew = remember
                naming = true
            },
            onDismiss = { picking = false },
        )
    }
    moving?.let { (layer, fid) ->
        LayerPicker(
            title = stringResource(R.string.move_title),
            layers = points.filter { it.visible && it.name != layer },
            showRemember = false,
            onPick = { to, _ ->
                moving = null
                requestMove(layer, fid, to.name)
            },
            onNew = { _ ->
                moving = null
                movingNew = layer to fid
            },
            onDismiss = { moving = null },
        )
    }
    movingNew?.let { (like, fid) ->
        NewLayerDialog(
            existing = layers.mapNotNull { it.name() },
            suggested = like,
            onDismiss = { movingNew = null },
            onCreate = { name, _ ->
                movingNew = null
                moveToNewLayer(name, like, fid)
            },
        )
    }
    dropping?.let { m ->
        AlertDialog(
            onDismissRequest = { dropping = null },
            title = { Text(stringResource(R.string.move_drops_title)) },
            text = { Text(stringResource(R.string.move_drops_body, m.to, m.lost.joinToString("、"))) },
            confirmButton = {
                TextButton(onClick = {
                    dropping = null
                    move(m)
                }) { Text(stringResource(R.string.move_anyway)) }
            },
            dismissButton = { TextButton(onClick = { dropping = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (naming) {
        NewLayerDialog(
            existing = layers.mapNotNull { it.name() },
            suggested = suggestedLayer,
            onDismiss = { naming = false },
            onCreate = { name, srs ->
                naming = false
                createLayer(name, srs)
            },
            centerLongitude = mapCenter?.longitude,
            describe = describeCrs,
        )
    }
    draft?.let { d ->
        FeatureForm(
            title = stringResource(R.string.place_new_point_in, d.layer),
            fields = formFields(collections[d.layer]),
            properties = d.values,
            coordinates = d.longitude to d.latitude,
            isNew = true,
            onDismiss = { draft = null },
            onSave = { values, at ->
                draft = null
                add(d.layer, values, at ?: Position(d.longitude, d.latitude, Crs.LONGITUDE_LATITUDE))
            },
            onDelete = null,
            crs = formCrs[d.layer],
            locate = locate,
        )
    }
    selected?.let { (layer, fid) ->
        // A feature that has gone (an undone addition, say) shows no form.
        val collection = collections[layer]
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
                    if (values.isNotEmpty() || at != null) change(layer, fid, values, at)
                },
                onDelete = {
                    selected = null
                    delete(layer, fid)
                },
                onMove = {
                    selected = null
                    moving = layer to fid
                },
                crs = formCrs[layer],
                locate = locate,
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

/** A point on its way to layer [to], and the fields whose values it would leave behind. */
private data class Move(val layer: String, val fid: Long, val to: String, val lost: List<String>)

/** A point about to be added: its layer, where it goes and its first values. The form's save adds it. */
private data class Draft(val layer: String, val longitude: Double, val latitude: Double, val values: JsonObject)

// The measuring tool's source on the map, and its colour.
private const val MEASURE_SOURCE = "geocrew-measure"
private const val MEASURE_COLOR = "#ff6d00"

// Feature properties the map reads: the colour of the point's layer.
private const val COLOR = "_color"

// A feature for the shared source: an id that names its layer, and its layer's colour.
private fun tagged(feature: JsonObject, layer: Int, color: String, opacity: Double): JsonObject {
    val properties = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val fid = (feature["id"] as? JsonPrimitive)?.contentOrNull
    val shown = properties + (COLOR to JsonPrimitive(color)) + (OPACITY to JsonPrimitive(opacity))
    return JsonObject(feature + ("id" to JsonPrimitive("$layer/$fid")) + ("properties" to JsonObject(shown)))
}

// The point's layer's opacity.
private const val OPACITY = "_opacity"

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
private fun PlacingHint(layer: String, center: () -> LatLng?, onChangeLayer: () -> Unit, locating: Boolean, accuracy: Float?, onLocate: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.OpenWith, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Which layer the point goes into, so it does not land in the wrong one.
                Text(stringResource(R.string.place_into, layer), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.place_hint), style = MaterialTheme.typography.bodyMedium)
                center()?.let {
                    Text(
                        stringResource(R.string.place_coordinates, DEGREES.format(Locale.ROOT, it.longitude), DEGREES.format(Locale.ROOT, it.latitude)),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onChangeLayer) { Text(stringResource(R.string.place_change_layer)) }
                IconButton(onClick = onLocate, enabled = !locating) {
                    if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.MyLocation, stringResource(R.string.my_position))
                }
                accuracy?.let { Text(String.format(Locale.ROOT, "±%.0f m", it), style = MaterialTheme.typography.labelSmall) }
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

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

// GNSS and the map are WGS84, taken as CGCS2000 (ADR 0015).
private fun point(at: Pair<Double, Double>): JsonObject = buildJsonObject {
    put("type", "Point")
    putJsonArray("coordinates") { add(at.first); add(at.second) }
}
