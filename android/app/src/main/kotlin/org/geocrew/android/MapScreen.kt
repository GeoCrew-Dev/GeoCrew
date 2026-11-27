package org.geocrew.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.RectF
import android.hardware.GeomagneticField
import android.location.LocationManager
import android.os.Build
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
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
import kotlinx.serialization.json.putJsonObject
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.core.edit.FieldType
import org.geocrew.core.project.PointNumbers
import org.geocrew.core.project.Templates
import org.geocrew.storage.Crs
import org.geocrew.storage.Projects
import org.geocrew.storage.Workspace
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
    onFiles: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val mapView = rememberMapView(graph.settings.mapCacheMb)
    val key = remember { TiandituKey.read(context) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var layers by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    // Each layer as layer.features gives it, by name.
    var collections by remember { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    // The features by their ids on the map ("layer index/fid"), with their exact coordinates, for snapping to.
    val featuresById by remember {
        derivedStateOf {
            buildMap {
                layers.forEachIndexed { index, l ->
                    for (f in (collections[l.name()]?.get("features") as? JsonArray).orEmpty()) {
                        val o = f as? JsonObject ?: continue
                        val fid = (o["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                        put("$index/$fid", o)
                    }
                }
            }
        }
    }
    // Where the crosshair snaps to while drawing, when it is near a vertex or an edge.
    var snapped by remember { mutableStateOf<LatLng?>(null) }

    // The map id of the feature a drawing reshapes, which it does not snap to; null for a new one.
    fun reshapedId(d: Drawing): String? = d.fid?.let { fid -> layers.indexOfFirst { it.name() == d.layer }.takeIf { it >= 0 }?.let { "$it/$fid" } }
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
    // A line or an area being drawn, and one drawn, waiting for its form.
    var drawing by remember { mutableStateOf<Drawing?>(null) }
    // The shapes it was before each change, to go back on one at a time.
    var drawingHistory by remember { mutableStateOf(emptyList<Drawing>()) }
    var shapeDraft by remember { mutableStateOf<Drawing?>(null) }
    var droppingDrawing by remember { mutableStateOf(false) }
    // Cancelling a drawing that has changes asks first.
    var cancellingDrawing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // Layers in a projected CRS, by name: their points can be entered as X and Y.
    var projected by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var basemaps by remember { mutableStateOf<List<BasemapInfo>>(emptyList()) }
    // The project's rasters, under its layers.
    var rasters by remember { mutableStateOf<List<RasterInfo>>(emptyList()) }
    // Where the map looks, as it settles: the Gauss-Kruger zone of a new layer follows it.
    var mapCenter by remember { mutableStateOf<LatLng?>(null) }
    val convert: suspend (String, String, Double, Double) -> Pair<Double, Double>? = { from, to, x, y ->
        val moved = graph.invoke(Workspace.CRS_TRANSFORM, buildJsonObject { put("from", from); put("to", to); putJsonArray("xy") { add(x); add(y) } })
        val xy = ((moved as? Outcome.Done)?.output?.jsonObject?.get("xy") as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
        if (xy != null && xy.size == 2 && xy[0] != null && xy[1] != null) xy[0]!! to xy[1]!! else null
    }
    // One per layer, kept while the layers stay: the form keys its state on it.
    val formCrs = remember(projected) { projected.mapValues { (_, srs) -> FormCrs(srs, convert) } }
    var failure by remember { mutableStateOf<String?>(null) }
    // The phone's position: the system asks for permission the first time; no fix is said plainly.
    val noFixText = stringResource(R.string.no_fix)
    val goToOutsideText = stringResource(R.string.go_to_outside)
    val screenshotFailedText = stringResource(R.string.map_screenshot_failed)
    val attributionText = stringResource(R.string.attribution_tianditu)
    val trackFailedText = stringResource(R.string.track_failed)
    val trackEmptyText = stringResource(R.string.track_empty)
    val trackOtherText = stringResource(R.string.track_other_project)
    val locationOffText = stringResource(R.string.location_off)
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
    var converting by remember { mutableStateOf(false) }
    var choosingBasemap by remember { mutableStateOf(false) }
    var editingToolbar by remember { mutableStateOf(false) }
    // Which Tianditu map shows: imagery, the vector map or terrain, kept per project on this phone.
    // Null until the project says which: no basemap shows (or loads) before then.
    var basemapKind by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId) { projectId?.let { basemapKind = graph.settings.basemapKind(it) } }
    // The bookmarks sheet: shown with the project's saved views once read.
    var bookmarks by remember { mutableStateOf<List<MapBookmark>?>(null) }
    // The GPS track recording or paused; the map shows it when it goes into this project.
    val track by graph.tracks.track.collectAsStateWithLifecycle()
    val ownTrack = track?.takeIf { it.projectId == projectId }
    // Saving a finished track: which layer it goes into (or a new track layer), then its form.
    var trackPicking by remember { mutableStateOf(false) }
    var trackNaming by remember { mutableStateOf(false) }
    var trackLayer by remember { mutableStateOf<String?>(null) }
    var discardingTrack by remember { mutableStateOf(false) }
    var ai by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<File?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val modified = collections.values.any { it.flag("projectModified") }
    val points = pointLayers(layers) { name -> (collections[name]?.get("features") as? JsonArray)?.size ?: 0 }
    val drawables = drawLayers(layers) { name -> (collections[name]?.get("features") as? JsonArray)?.size ?: 0 }
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
        rasters = listedRasters(about)
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
                    val bounds = zoom.bounds
                    if (bounds != null && bounds.size == 4) {
                        // A raster's extent, from the layers page.
                        val (west, south, east, north) = bounds
                        mapView.awaitMap().animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(north, east, south, west), (48 * density).toInt()))
                    } else {
                        val features = (collections[zoom.layer]?.get("features") as? JsonArray).orEmpty()
                        val shown = if (zoom.fid == null) features else features.filter { (it as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull == zoom.fid }
                        fit(mapView.awaitMap(), JsonArray(shown), density)
                        if (zoom.fid != null && shown.isNotEmpty()) selected = zoom.layer to zoom.fid
                    }
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
            // While drawing, a tap on a vertex picks it (or puts it down), a tap on the middle of a side puts a vertex
            // in there, picked; elsewhere a tap puts the pick down, or with none picked adds a vertex at the end.
            drawing?.let { d ->
                if (d.moving) return@addOnMapClickListener true
                val at = map.projection.toScreenLocation(point)
                fun away(p: LatLng) = map.projection.toScreenLocation(p).let { s -> hypot(s.x - at.x, s.y - at.y) }
                val reach = 24 * density
                val vertex = d.vertices.indices.minByOrNull { away(d.vertices[it]) }?.takeIf { away(d.vertices[it]) <= reach }
                val middle = d.middles().minByOrNull { away(it.second) }?.takeIf { away(it.second) <= reach }
                val toVertex = vertex?.let { away(d.vertices[it]) } ?: Float.MAX_VALUE
                val toMiddle = middle?.let { away(it.second) } ?: Float.MAX_VALUE
                when {
                    vertex != null && toVertex <= toMiddle -> drawing = d.copy(picked = if (d.picked == vertex) null else vertex)
                    middle != null -> {
                        drawingHistory = drawingHistory + d.copy(picked = null)
                        drawing = d.copy(vertices = d.vertices.toMutableList().apply { add(middle.first, middle.second) }, picked = middle.first)
                    }
                    d.picked != null -> drawing = d.copy(picked = null)
                    else -> {
                        drawingHistory = drawingHistory + d
                        val to = if (graph.settings.snapping) snap(map, at, d, featuresById, reshapedId(d), SNAP_REACH * density, SNAP_LAYERS) else null
                        drawing = d.copy(vertices = d.vertices + (to ?: point.wrap()))
                    }
                }
                return@addOnMapClickListener true
            }
            // While measuring, a tap adds a point to the measurement.
            if (measuring != null) {
                measured = withPoint(measuring ?: return@addOnMapClickListener true, measured, point.wrap())
                return@addOnMapClickListener true
            }
            val at = map.projection.toScreenLocation(point)
            val reach = 24 * density
            val near = RectF(at.x - reach, at.y - reach, at.x + reach, at.y + reach)
            val hit = map.queryRenderedFeatures(near, POINT_SOURCE).firstOrNull()
                ?: map.queryRenderedFeatures(near, FEATURE_LINE).firstOrNull()
                ?: map.queryRenderedFeatures(at, FEATURE_FILL).firstOrNull()
            // Ids are "layer index/fid": points of every layer share the source.
            val (index, fid) = hit?.id()?.split("/")?.takeIf { it.size == 2 } ?: return@addOnMapClickListener false
            val name = layers.getOrNull(index.toIntOrNull() ?: -1)?.name()
            if (name != null) fid.toLongOrNull()?.let { selected = name to it }
            true
        }
        map.addOnCameraIdleListener { mapCenter = map.cameraPosition.target?.wrap() }
        map.addOnCameraMoveListener { coneZoom = Math.round(map.cameraPosition.zoom * 4) / 4.0 }
        // Line names turn with the map; when it has turned far enough to put one upside down, it is turned round.
        var labelBearing = 0L
        map.addOnCameraMoveListener {
            val bearing = Math.round(map.cameraPosition.bearing)
            if (bearing == labelBearing) return@addOnCameraMoveListener
            labelBearing = bearing
            map.style?.getLayer("$SHAPE_LABELS-line")?.setProperties(PropertyFactory.iconRotate(MapLabels.upright(bearing.toDouble())))
        }
        coneZoom = Math.round(map.cameraPosition.zoom * 4) / 4.0
        // A picture for the home screen only once the map has been drawn in full.
        mapView.addOnDidBecomeIdleListener { drawn = true }
        // While a point is being placed, where the crosshair is.
        map.addOnCameraMoveListener { if (placing || drawing != null) center = map.cameraPosition.target?.wrap() }
        map.setStyle(Style.Builder().fromJson(key?.let(MapStyles::tianditu) ?: MapStyles.BLANK)) { style ->
            style.addSource(GeoJsonSource(POINT_SOURCE))
            // Areas under lines under points, each in its layer's colour.
            style.addLayer(
                FillLayer(FEATURE_FILL, POINT_SOURCE).withProperties(
                    PropertyFactory.fillColor(Expression.get(COLOR)),
                    PropertyFactory.fillOpacity(Expression.product(Expression.get(OPACITY), Expression.get(FILL))),
                ).withFilter(isType("Polygon")),
            )
            style.addLayer(
                LineLayer(FEATURE_LINE, POINT_SOURCE).withProperties(
                    PropertyFactory.lineColor(Expression.get(COLOR)),
                    PropertyFactory.lineOpacity(Expression.get(OPACITY)),
                    PropertyFactory.lineWidth(Expression.get(WIDTH)),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                ).withFilter(Expression.any(isType("LineString"), isType("Polygon"))),
            )
            style.addLayer(
                CircleLayer(POINT_SOURCE, POINT_SOURCE).withProperties(
                    PropertyFactory.circleRadius(Expression.get(SIZE)),
                    PropertyFactory.circleColor(Expression.get(COLOR)),
                    PropertyFactory.circleOpacity(Expression.get(OPACITY)),
                    PropertyFactory.circleStrokeOpacity(Expression.get(OPACITY)),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2f),
                ).withFilter(isType("Point")),
            )
            // Point names to the right of the points; MapLibre keeps them from piling up.
            style.addLayer(
                SymbolLayer(POINT_LABELS, POINT_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get(MapLabels.PROPERTY)),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_LEFT),
                    PropertyFactory.iconOffset(Expression.get(LABEL_OFFSET)),
                    PropertyFactory.iconOpacity(Expression.get(OPACITY)),
                ).withFilter(Expression.all(Expression.has(MapLabels.PROPERTY), isType("Point"))),
            )
            // Names of lines (turned with them, so they turn with the map) and of areas (upright on the screen).
            style.addSource(GeoJsonSource(SHAPE_LABELS))
            for ((kind, alignment) in listOf("line" to Property.ICON_ROTATION_ALIGNMENT_MAP, "area" to Property.ICON_ROTATION_ALIGNMENT_VIEWPORT)) {
                style.addLayer(
                    SymbolLayer("$SHAPE_LABELS-$kind", SHAPE_LABELS).withProperties(
                        PropertyFactory.iconImage(Expression.get(MapLabels.PROPERTY)),
                        PropertyFactory.iconRotate(if (kind == "line") MapLabels.upright(map.cameraPosition.bearing) else Expression.literal(0f)),
                        PropertyFactory.iconRotationAlignment(alignment),
                        PropertyFactory.iconOpacity(Expression.get(OPACITY)),
                    ).withFilter(Expression.eq(Expression.get(MapLabels.KIND), Expression.literal(kind))),
                )
            }
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
                // A drawing's vertices are marked by role: the picked one bigger and blue, the middles of sides small and hollow.
                CircleLayer("$MEASURE_SOURCE-points", MEASURE_SOURCE).withProperties(
                    PropertyFactory.circleRadius(
                        Expression.match(
                            Expression.get("role"), Expression.literal(5f),
                            Expression.stop("picked", 9f), Expression.stop("middle", 4f), Expression.stop("snap", 12f),
                        ),
                    ),
                    PropertyFactory.circleColor(
                        Expression.toColor(
                            Expression.match(
                                Expression.get("role"), Expression.literal(MEASURE_COLOR),
                                Expression.stop("picked", PICKED_COLOR), Expression.stop("middle", "#ffffff"), Expression.stop("snap", "rgba(0, 0, 0, 0)"),
                            ),
                        ),
                    ),
                    PropertyFactory.circleStrokeColor(
                        Expression.toColor(
                            Expression.match(Expression.get("role"), Expression.literal("#ffffff"), Expression.stop("middle", MEASURE_COLOR), Expression.stop("snap", SNAP_COLOR)),
                        ),
                    ),
                    PropertyFactory.circleStrokeWidth(Expression.match(Expression.get("role"), Expression.literal(2f), Expression.stop("snap", 3f))),
                ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point"))),
            )
            addTrackLayers(style)
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

    // While drawing, the crosshair snaps to a vertex or an edge near it.
    LaunchedEffect(drawing, center, featuresById, graph.settings.snapping) {
        val d = drawing
        val c = center
        if (d == null || c == null || !graph.settings.snapping) {
            snapped = null
            return@LaunchedEffect
        }
        val map = mapView.awaitMap()
        snapped = snap(map, map.projection.toScreenLocation(c), d, featuresById, reshapedId(d), SNAP_REACH * density, SNAP_LAYERS)
    }

    LaunchedEffect(styleLoads, measuring, measured, drawing, center, snapped) {
        val style = mapView.awaitMap().style ?: return@LaunchedEffect
        val mode = measuring
        val d = drawing
        val shown = when {
            d != null -> drawingGeoJson(d, snapped ?: center, snapped = snapped != null)
            mode == null -> measureGeoJson(MeasureMode.DISTANCE, emptyList())
            else -> measureGeoJson(mode, measured)
        }
        style.getSourceAs<GeoJsonSource>(MEASURE_SOURCE)?.setGeoJson(shown.toString())
    }

    // The project's rasters under its layers. The files they draw (VRTs for some) are made ready off the main thread.
    LaunchedEffect(styleLoads, rasters) {
        val style = mapView.awaitMap().style ?: return@LaunchedEffect
        val files = withContext(Dispatchers.IO) {
            if (runCatching { graph.start() }.isFailure) return@withContext emptyMap()
            rasters.filter { !it.missing }.mapNotNull { r -> runCatching { r.id to RasterTiles.fileFor(context, r) }.getOrNull() }.toMap()
        }
        // The style may have been replaced meanwhile; the next run shows them there.
        if (mapView.awaitMap().style === style) showRasters(style, rasters, files, below = FEATURE_FILL)
    }

    // The track as it grows; it goes when it is saved or thrown away.
    LaunchedEffect(styleLoads, ownTrack?.points) {
        val style = mapView.awaitMap().style ?: return@LaunchedEffect
        showTrack(style, ownTrack?.points)
    }

    // The phone's position and heading on the map; the compass gives magnetic north, the map wants true north.
    LaunchedEffect(styleLoads, phone, heading, coneZoom) {
        val style = mapView.awaitMap().style ?: return@LaunchedEffect
        val at = phone
        val north = if (at == null || heading == null) heading else
            (heading!! + GeomagneticField(at.latitude.toFloat(), at.longitude.toFloat(), 0f, System.currentTimeMillis()).declination + 360) % 360
        style.getSourceAs<GeoJsonSource>(ME_SOURCE)?.setGeoJson(myLocationGeoJson(at, north, coneZoom).toString())
    }

    // Rotation locked: north stays up and two fingers no longer turn the map.
    LaunchedEffect(graph.settings.rotationLocked) {
        val map = mapView.awaitMap()
        map.uiSettings.isRotateGesturesEnabled = !graph.settings.rotationLocked
        if (graph.settings.rotationLocked) map.resetNorth()
    }

    // The chosen Tianditu map, its base and labels as the project says: shown or not, and how opaque
    // (the base is the img row of the display, the labels the cia row, whichever map is chosen).
    LaunchedEffect(styleLoads, basemaps, basemapKind) {
        val style = mapView.awaitMap().style ?: return@LaunchedEffect
        val display = basemaps.associateBy { it.id }
        for (t in MapStyles.Tianditu.entries) {
            for ((layerId, row) in listOf("base-${t.base}" to "img", "labels-${t.base}" to "cia")) {
                val layer = style.getLayer(layerId) ?: continue
                val d = display[row]
                val shown = t.base == basemapKind && (d?.visible ?: true)
                layer.setProperties(
                    PropertyFactory.visibility(if (shown) Property.VISIBLE else Property.NONE),
                    PropertyFactory.rasterOpacity(d?.opacity ?: 1f),
                )
            }
        }
    }

    var fitted by remember { mutableStateOf(graph.cameras.containsKey(project)) }
    LaunchedEffect(styleLoads, collections) {
        val map = mapView.awaitMap()
        val style = map.style ?: return@LaunchedEffect
        // With no layers left, nothing of theirs stays on the map.
        if (collections.isEmpty()) {
            for (source in listOf(POINT_SOURCE, SHAPE_LABELS)) style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(EMPTY_COLLECTION)
            return@LaunchedEffect
        }
        // Every layer in one source (points, lines, areas), each feature with its layer's colour and an id that names its layer.
        // Bottom of the list first, so the first layer is drawn on top; hidden layers stay off the map.
        val features = JsonArray(
            layers.mapIndexed { index, l -> index to l }.reversed().flatMap { (index, l) ->
                if ((l["visible"] as? JsonPrimitive)?.contentOrNull == "false") return@flatMap emptyList()
                val opacity = (l["opacity"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 1.0
                val collection = collections[l.name()] ?: return@flatMap emptyList()
                val own = collection["features"] as? JsonArray ?: return@flatMap emptyList()
                val point = (l["geometry"] as? JsonPrimitive)?.contentOrNull == "POINT"
                val look = styleOf(l, layerColor(madeIndex(l)))
                val labelled = labelField(look, collection)?.takeIf { point }?.let { MapLabels.label(own, it, style, density) } ?: own
                labelled.map { tagged(it.jsonObject, index, look, opacity) }
            },
        )
        val geoJson = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", features)
        }
        style.getSourceAs<GeoJsonSource>(POINT_SOURCE)?.setGeoJson(geoJson.toString())
        // The names of lines and areas, as points of their own: in the middle of a line, turned with it; in an area's centre.
        val shapeLabels = layers.reversed().flatMap { l ->
            if ((l["visible"] as? JsonPrimitive)?.contentOrNull == "false") return@flatMap emptyList()
            if ((l["geometry"] as? JsonPrimitive)?.contentOrNull == "POINT") return@flatMap emptyList()
            val collection = collections[l.name()] ?: return@flatMap emptyList()
            val field = labelField(styleOf(l, layerColor(madeIndex(l))), collection) ?: return@flatMap emptyList()
            val opacity = (l["opacity"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 1.0
            MapLabels.shapes(collection["features"] as? JsonArray ?: return@flatMap emptyList(), field, style, density) { label ->
                JsonObject(label + ("properties" to JsonObject((label["properties"] as JsonObject) + (OPACITY to JsonPrimitive(opacity)))))
            }
        }
        style.getSourceAs<GeoJsonSource>(SHAPE_LABELS)?.setGeoJson(
            buildJsonObject {
                put("type", "FeatureCollection")
                put("features", JsonArray(shapeLabels))
            }.toString(),
        )
        // The first visit shows the points there are.
        if (!fitted && styleLoads > 0) {
            fitted = true
            fit(map, features, density)
        }
    }

    fun startDrawing(layer: String, area: Boolean) {
        placing = false
        measuring = null // one tool at a time
        measured = emptyList()
        drawingHistory = emptyList()
        drawing = Drawing(layer, area)
        scope.launch { center = mapView.awaitMap().cameraPosition.target?.wrap() }
    }

    // A change to the shape being drawn, kept so it can be gone back on.
    fun editDrawing(next: Drawing) {
        drawing?.let { drawingHistory = drawingHistory + it.copy(picked = null, moving = false) }
        drawing = next
    }

    fun undoDrawing() {
        val last = drawingHistory.lastOrNull() ?: return
        drawingHistory = drawingHistory.dropLast(1)
        drawing = last
    }

    // A vertex at the crosshair: after the picked one (then picked itself), else at the end.
    fun addVertex(at: LatLng) {
        val d = drawing ?: return
        val i = d.picked
        editDrawing(if (i == null) d.copy(vertices = d.vertices + at) else d.copy(vertices = d.vertices.toMutableList().apply { add(i + 1, at) }, picked = i + 1))
    }

    // The map goes to the picked vertex, which then stands at the crosshair, moving with the map until put down.
    fun moveVertex() {
        val d = drawing ?: return
        val at = d.picked?.let { d.vertices.getOrNull(it) } ?: return
        scope.launch {
            mapView.awaitMap().moveCamera(CameraUpdateFactory.newLatLng(at))
            center = at
            drawing = drawing?.copy(moving = true)
        }
    }

    fun putVertex() {
        val d = drawing ?: return
        val i = d.picked ?: return
        val at = snapped ?: center ?: return
        // Put down where it was, it is no change to go back on.
        if (at == d.vertices.getOrNull(i)) drawing = d.copy(moving = false) else editDrawing(d.copy(vertices = d.vertices.toMutableList().apply { set(i, at) }, moving = false))
    }

    // Cancelling ends the drawing at once while nothing was changed; otherwise it asks first.
    fun cancelDrawing() {
        if (drawingHistory.isEmpty()) drawing = null else cancellingDrawing = true
    }

    fun deleteVertex() {
        val d = drawing ?: return
        val i = d.picked ?: return
        editDrawing(d.copy(vertices = d.vertices.filterIndexed { j, _ -> j != i }, picked = null))
    }

    fun startPlacing(layer: String) {
        drawing = null
        target = layer
        placingAccuracy = null // a fix from before is not where the crosshair is now
        measuring = null // one tool at a time: placing ends a measurement
        measured = emptyList()
        placing = true
        scope.launch { center = mapView.awaitMap().cameraPosition.target?.wrap() }
    }

    fun startAdding(layer: MapLayer) {
        if (layer.geometry == "POINT") startPlacing(layer.name) else startDrawing(layer.name, holdsAreas(layer.geometry))
    }

    // Placing asks which layer the point goes into: not when the user chose to
    // remember one that is still there; and a project with no point layer makes one first.
    fun requestPlace() {
        val id = projectId ?: return
        val remembered = graph.settings.placingLayer(id)
        // A hidden layer takes no new points (they would not show); a remembered one that is hidden asks, and is kept.
        val kept = drawables.firstOrNull { it.id == remembered && it.visible }
        if (remembered != null && drawables.none { it.id == remembered }) graph.settings.setPlacingLayer(id, null) // the layer has gone: ask again
        when {
            kept != null -> startAdding(kept)
            drawables.isEmpty() -> {
                rememberNew = false
                naming = true
            }
            else -> picking = true
        }
    }

    fun createLayer(name: String, srs: String?, template: String? = null) = once {
        val params = buildJsonObject {
            put("name", name)
            put("language", AppLanguage.speaking(context))
            if (srs != null) put("srs", srs)
            if (template != null) put("template", template)
        }
        when (val made = graph.invoke(Workspace.LAYER_CREATE, params)) {
            is Outcome.Done -> {
                show(made.output.jsonObject)
                val id = made.output.jsonObject["layers"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.name() == name }
                    ?.let { (it["id"] as? JsonPrimitive)?.contentOrNull }
                if (rememberNew && id != null) projectId?.let { graph.settings.setPlacingLayer(it, id) }
                when (template) {
                    "field-line" -> startDrawing(name, area = false)
                    "field-polygon" -> startDrawing(name, area = true)
                    Templates.TRACK.id -> trackLayer = name
                    else -> startPlacing(name)
                }
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

    // The drawn vertices in the layer's CRS, as GeoJSON; null (and said) when they don't convert.
    suspend fun shapeInLayer(d: Drawing): JsonObject? {
        val own = layers.firstOrNull { it.name() == d.layer }?.text("srs") ?: Crs.LONGITUDE_LATITUDE
        // Degrees are kept to the seventh decimal; converted ones keep full precision, so a projected layer's vertices come back as they were.
        if (Crs.same(own, Crs.LONGITUDE_LATITUDE)) return shapeGeoJson(d.vertices.map { round7(it.longitude) to round7(it.latitude) }, d.area)
        val degrees = d.vertices.map { it.longitude to it.latitude }
        val moved = graph.invoke(
            Workspace.CRS_TRANSFORM,
            buildJsonObject {
                put("from", Crs.LONGITUDE_LATITUDE)
                put("to", own)
                putJsonArray("xy") { for ((x, y) in degrees) { add(x); add(y) } }
            },
        )
        val xy = ((moved as? Outcome.Done)?.output?.jsonObject?.get("xy") as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
        if (xy == null || xy.size != degrees.size * 2 || xy.any { it == null || !it.isFinite() }) {
            failure = outsideText
            return null
        }
        return shapeGeoJson(xy.chunked(2).map { it[0]!! to it[1]!! }, d.area)
    }

    fun reshape(d: Drawing) = once {
        val fid = d.fid ?: return@once
        // The feature may have gone while it was reshaped (an undone addition, say): then there is nothing to change.
        val there = (collections[d.layer]?.get("features") as? JsonArray).orEmpty()
            .any { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == fid }
        if (!there) return@once
        if (!startEditing(d.layer)) return@once
        val geometry = shapeInLayer(d) ?: return@once
        act(Editing.GEOMETRY, buildJsonObject { put("layer", d.layer); put("fid", fid); put("geometry", geometry) })
    }

    fun addShape(d: Drawing, values: JsonObject, onAdded: () -> Unit = {}) = once {
        if (!startEditing(d.layer)) return@once
        val geometry = shapeInLayer(d) ?: return@once
        val added = graph.invoke(Editing.ADD, buildJsonObject { put("layer", d.layer); put("geometry", geometry); put("properties", values) })
        reload()
        if (added !is Outcome.Done) {
            failure = texts.of(added)
            return@once
        }
        onAdded()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(addedText.format(d.layer))
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
        when {
            drawing?.vertices?.isNotEmpty() == true -> droppingDrawing = true // a shape half drawn is asked about first
            modified -> leaving = true
            else -> once { close() }
        }
    }

    // Every point of every layer in view.
    // Every feature of every layer in view, and the rasters shown, by their extents.
    fun showAll() = scope.launch {
        val shown = layers.filter { it.text("visible") != "false" }.mapNotNull { it.name() }
        val boxes = rasters.filter { it.visible && !it.missing }.mapNotNull { it.extent }.map { (west, south, east, north) ->
            buildJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "MultiPoint")
                    putJsonArray("coordinates") {
                        addJsonArray { add(west); add(south) }
                        addJsonArray { add(east); add(north) }
                    }
                }
            }
        }
        val all = JsonArray(shown.flatMap { (collections[it]?.get("features") as? JsonArray).orEmpty() } + boxes)
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

    // A bookmark action; its output is the bookmarks as they now are.
    fun bookmarkAction(action: String, params: JsonObject = JsonObject(emptyMap())) = once {
        when (val outcome = graph.invoke(action, params)) {
            is Outcome.Done -> bookmarks = listedBookmarks(outcome.output.jsonObject)
            else -> failure = texts.of(outcome)
        }
    }

    fun addBookmark(name: String) = scope.launch {
        val camera = mapView.awaitMap().cameraPosition
        val at = camera.target?.wrap() ?: return@launch
        bookmarkAction(
            Workspace.BOOKMARK_ADD,
            buildJsonObject {
                put("name", name)
                put("longitude", at.longitude)
                put("latitude", at.latitude)
                put("zoom", camera.zoom)
                put("bearing", camera.bearing)
            },
        )
    }

    fun goToBookmark(b: MapBookmark) = scope.launch {
        bookmarks = null
        val position = CameraPosition.Builder().target(LatLng(b.latitude, b.longitude)).zoom(b.zoom).bearing(if (graph.settings.rotationLocked) 0.0 else b.bearing).build()
        mapView.awaitMap().animateCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    // A track records with location granted (asked for first) and on.
    fun mayRecord(): Boolean {
        if (!mayLocate(context)) {
            askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return false
        }
        if (context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            failure = locationOffText
            return false
        }
        return true
    }

    fun startTrack() {
        val id = projectId ?: return
        if (mayRecord() && !graph.tracks.start(id, project)) failure = trackFailedText
    }

    fun resumeTrack() {
        if (mayRecord() && !graph.tracks.resume()) failure = trackFailedText
    }

    // The notification that a track records is asked for once; the track records either way.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startTrack() }

    // One track at a time: one going into another project is said, and stays.
    fun requestTrack() {
        val other = track
        when {
            other != null && other.projectId != projectId -> failure = trackOtherText.format(other.projectName)
            other != null -> Unit
            mayLocate(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> startTrack()
        }
    }

    // Line layers a track can go into, track layers first.
    val trackIds = layers.filter { it.text("template") == Templates.TRACK.id }.mapNotNull { it.text("id") }.toSet()
    val trackLines = drawables.filter { it.visible && sameKind(it.geometry, "LINESTRING") }.sortedBy { it.id !in trackIds }

    // Ends the recording and asks where the track goes; until it is saved it stays, paused.
    fun finishTrack() {
        val t = ownTrack ?: return
        graph.tracks.pause()
        if (t.points.size < 2) {
            graph.tracks.discard()
            scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(trackEmptyText) }
            return
        }
        if (trackLines.isEmpty()) trackNaming = true else trackPicking = true
    }

    // The track as a line of [layer], like one drawn; once it is in, the recording is gone.
    fun saveTrack(layer: String, values: JsonObject) {
        val t = ownTrack ?: return
        addShape(Drawing(layer, area = false, vertices = t.points.map { LatLng(it.latitude, it.longitude) }), values) { graph.tracks.discard() }
    }

    fun measure(mode: MeasureMode) {
        placing = false
        drawing = null // one tool at a time
        measuring = mode
        measured = emptyList()
    }

    fun export(format: String) = once {
        when (val outcome = graph.invoke(Workspace.EXPORT, buildJsonObject { put("format", format) })) {
            is Outcome.Done -> exported = File(outcome.output.jsonObject.getValue("path").jsonPrimitive.content)
            else -> failure = texts.of(outcome)
        }
    }

    // A measuring tool, or the same one again to stop it, as from the toolbar.
    fun measureOrStop(mode: MeasureMode) {
        if (measuring == mode) {
            measuring = null
            measured = emptyList()
        } else {
            measure(mode)
        }
    }

    // Runs a map tool, from the menu or the toolbar.
    fun runTool(tool: MapTool) {
        val projectScope = buildJsonObject { put("scope", Workspace.PROJECT_SCOPE) }
        when (tool) {
            MapTool.LAYERS -> onLayers()
            MapTool.UNDO -> once { act(ActionRegistry.UNDO, projectScope) }
            MapTool.REDO -> once { act(ActionRegistry.REDO, projectScope) }
            MapTool.ADD_FEATURE -> if (drawables.isEmpty()) {
                rememberNew = false
                naming = true
            } else {
                picking = true
            }
            MapTool.SHOW_ALL -> showAll()
            MapTool.GO_TO -> goingTo = true
            MapTool.NORTH_UP -> northUp()
            MapTool.BASEMAP -> choosingBasemap = true
            MapTool.SCREENSHOT -> scope.launch {
                // The map alone, without the buttons; saved with the exports, then offered to share.
                val file = saveScreenshot(mapView.awaitMap(), File(graph.root, Projects.EXPORTS), project, attributionText.takeIf { key != null }, density)
                if (file != null) exported = file else failure = screenshotFailedText
            }
            MapTool.BOOKMARKS -> bookmarkAction(Workspace.BOOKMARKS)
            MapTool.LOCK_ROTATION -> graph.settings.lockRotation(!graph.settings.rotationLocked)
            MapTool.SNAP -> graph.settings.changeSnapping(!graph.settings.snapping)
            MapTool.MEASURE_DISTANCE -> measureOrStop(MeasureMode.DISTANCE)
            MapTool.MEASURE_AREA -> measureOrStop(MeasureMode.AREA)
            MapTool.AZIMUTH -> measureOrStop(MeasureMode.AZIMUTH)
            MapTool.PICK -> measureOrStop(MeasureMode.PICK)
            MapTool.MY_LOCATION -> showMe()
            MapTool.TRACK -> if (ownTrack == null) requestTrack() else finishTrack()
            MapTool.CONVERT -> converting = true
            MapTool.EXPORT_GPKG -> export("gpkg")
            MapTool.EXPORT_KML -> export("kml")
            MapTool.FILES -> onFiles()
            MapTool.PROJECT_SETTINGS -> onProjectSettings()
            MapTool.CLOSE_PROJECT -> requestLeave()
        }
    }

    // How a tool shows now: some change name and icon, some cannot be used, and a measuring tool in use is on.
    @Composable
    fun stateOf(tool: MapTool): ToolState {
        val plain = ToolState(stringResource(tool.label), tool.icon)
        return when (tool) {
            MapTool.UNDO -> plain.copy(enabled = history.first)
            MapTool.REDO -> plain.copy(enabled = history.second)
            MapTool.ADD_FEATURE -> plain.copy(enabled = projectId != null)
            MapTool.SNAP -> plain.copy(on = graph.settings.snapping)
            // A locked map is always north up.
            MapTool.NORTH_UP -> plain.copy(enabled = !graph.settings.rotationLocked)
            MapTool.LOCK_ROTATION -> if (graph.settings.rotationLocked) ToolState(stringResource(R.string.tool_unlock_rotation), Icons.Rounded.ScreenRotation, on = true) else plain
            MapTool.MEASURE_DISTANCE -> plain.copy(on = measuring == MeasureMode.DISTANCE)
            MapTool.MEASURE_AREA -> plain.copy(on = measuring == MeasureMode.AREA)
            MapTool.AZIMUTH -> plain.copy(on = measuring == MeasureMode.AZIMUTH)
            MapTool.PICK -> plain.copy(on = measuring == MeasureMode.PICK)
            MapTool.TRACK -> if (ownTrack != null) ToolState(stringResource(R.string.tool_track_finish), Icons.Rounded.Stop, on = ownTrack.recording) else plain
            else -> plain
        }
    }

    BackHandler(enabled = !placing && drawing == null) { requestLeave() }
    // Back first stops a move, then puts the pick down, then goes back one change; with nothing to go back on, the drawing ends.
    BackHandler(enabled = drawing != null) {
        val d = drawing ?: return@BackHandler
        when {
            d.moving -> drawing = d.copy(moving = false)
            d.picked != null -> drawing = d.copy(picked = null)
            drawingHistory.isNotEmpty() -> undoDrawing()
            else -> drawing = null
        }
    }
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
        if (placing || drawing != null) Crosshair(Modifier.align(Alignment.Center))

        // The buttons keep clear of the system bars and the camera, in either orientation.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // The toolbar's tools stand in columns from the right edge: as many to a column as fit between the
            // title and the bottom buttons (few in landscape), so none is hidden.
            val toolbar = graph.settings.toolbar
            val perColumn = ((maxHeight - TOOLBAR_TOP - TOOLBAR_BOTTOM + TOOL_GAP) / (TOOL_SIZE + TOOL_GAP)).toInt().coerceAtLeast(1)
            // At most two columns: the toolbar holds the user's everyday tools; the menu holds them all.
            val toolColumns = toolbar.chunked(perColumn).take(2)
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
                // Clear of the toolbar on the right.
                Column(Modifier.padding(end = (TOOL_SIZE + TOOL_GAP) * toolColumns.size)) {
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
                } else if (drawing != null) {
                    Spacer(Modifier.height(8.dp))
                    DrawPanel(
                        drawing = drawing ?: return@Column,
                        canUndo = drawingHistory.isNotEmpty(),
                        onUndo = ::undoDrawing,
                        onClose = ::cancelDrawing,
                        onMove = ::moveVertex,
                        onDelete = ::deleteVertex,
                        onUnpick = { drawing = drawing?.copy(picked = null, moving = false) },
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
                ownTrack?.takeIf { !placing && drawing == null && measuring == null }?.let { t ->
                    Spacer(Modifier.height(8.dp))
                    TrackPanel(
                        t,
                        onPause = { graph.tracks.pause() },
                        onResume = ::resumeTrack,
                        onFinish = ::finishTrack,
                        onDiscard = { discardingTrack = true },
                    )
                }
                }
            }

            // The toolbar: the tools the user put there (MapTool), translucent, the first column at the right edge.
            // A tool in use is shown on.
            Box(Modifier.fillMaxSize().padding(top = TOOLBAR_TOP, bottom = TOOLBAR_BOTTOM, end = 12.dp), contentAlignment = Alignment.CenterEnd) {
                Row(horizontalArrangement = Arrangement.spacedBy(TOOL_GAP), verticalAlignment = Alignment.Top) {
                    for (column in toolColumns.reversed()) {
                        Column(verticalArrangement = Arrangement.spacedBy(TOOL_GAP)) {
                            for (tool in column) {
                                val shown = stateOf(tool)
                                RoundButton(
                                    shown.icon, shown.label, onClick = { runTool(tool) }, enabled = shown.enabled,
                                    container = if (shown.on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    content = if (shown.on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
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
                } else if (drawing?.moving == true) {
                    // Moving a vertex: put it at the crosshair, or leave it where it was.
                    RoundButton(Icons.Rounded.Close, stringResource(R.string.cancel), onClick = { drawing = drawing?.copy(moving = false) }, size = 56.dp, solid = true)
                    ExtendedFloatingActionButton(
                        onClick = ::putVertex,
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Rounded.Check, null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.draw_put_here))
                    }
                    Spacer(Modifier.size(56.dp)) // keeps the button in the middle
                } else if (drawing != null) {
                    val d = drawing
                    RoundButton(Icons.Rounded.Close, stringResource(R.string.cancel), onClick = ::cancelDrawing, size = 56.dp, solid = true)
                    ExtendedFloatingActionButton(
                        onClick = {
                            scope.launch {
                                val at = snapped ?: mapView.awaitMap().cameraPosition.target?.wrap() ?: return@launch
                                addVertex(at)
                            }
                        },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.draw_add_vertex))
                    }
                    RoundButton(
                        Icons.Rounded.Check, stringResource(R.string.draw_finish),
                        onClick = {
                            val done = drawing
                            drawing = null
                            if (done?.fid != null) reshape(done) else shapeDraft = done
                        },
                        size = 56.dp, solid = true, enabled = d?.complete == true,
                        container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
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

    bookmarks?.let { list ->
        BookmarksSheet(
            bookmarks = list,
            onGo = ::goToBookmark,
            onAdd = ::addBookmark,
            onDelete = { b -> bookmarkAction(Workspace.BOOKMARK_DELETE, buildJsonObject { put("id", b.id) }) },
            onDismiss = { bookmarks = null },
        )
    }

    if (menu) {
        // The menu in levels (backlog: tiered menus of GIS and map tools); groups fill up tool by tool.
        @Composable
        fun entry(tool: MapTool): MenuTool = stateOf(tool).let { MenuTool(it.label, it.icon) { runTool(tool) } }
        val groupIcons = mapOf(
            R.string.menu_edit to Icons.Rounded.EditLocationAlt,
            R.string.menu_map to Icons.Rounded.Map,
            R.string.menu_measure to Icons.Rounded.Straighten,
            R.string.menu_location to Icons.Rounded.GpsFixed,
            R.string.menu_analysis to Icons.Rounded.Calculate,
            R.string.menu_data to Icons.Rounded.Storage,
            R.string.project_section to Icons.Rounded.Folder,
        )
        MenuSheet(
            title = project,
            entries = MapTool.entries.filter { it.group == null }.map { entry(it) } + MapTool.GROUPS.map { group ->
                val tools = MapTool.entries.filter { it.group == group }
                    // A locked map is always north up.
                    .filterNot { it == MapTool.NORTH_UP && graph.settings.rotationLocked }
                    .map { entry(it) }
                val extra = if (group == R.string.menu_map) {
                    listOf(MenuTool(stringResource(R.string.toolbar_edit), Icons.Rounded.DashboardCustomize) { editingToolbar = true })
                } else {
                    emptyList()
                }
                MenuGroup(stringResource(group), groupIcons.getValue(group), tools + extra)
            },
            onDismiss = { menu = false },
        )
    }
    if (editingToolbar) {
        ToolbarEditor(graph.settings.toolbar, onChange = graph.settings::changeToolbar, onDismiss = { editingToolbar = false })
    }
    if (choosingBasemap) {
        BasemapSheet(
            chosen = basemapKind ?: "img",
            onPick = { kind ->
                choosingBasemap = false
                basemapKind = kind
                projectId?.let { graph.settings.setBasemapKind(it, kind) }
            },
            onDismiss = { choosingBasemap = false },
        )
    }
    if (converting) {
        CoordinateConvertDialog(
            graph = graph,
            near = mapCenter?.let { it.longitude to it.latitude },
            onDismiss = { converting = false },
            onShow = { longitude, latitude ->
                converting = false
                goTo(longitude, latitude)
            },
        )
    }
    if (goingTo) {
        GoToDialog(
            graph = graph,
            near = mapCenter?.let { it.longitude to it.latitude },
            onDismiss = { goingTo = false },
            onGo = { at ->
                goingTo = false
                scope.launch {
                    val degrees = if (Crs.same(at.srs, Crs.LONGITUDE_LATITUDE)) at.x to at.y else convert(at.srs, Crs.LONGITUDE_LATITUDE, at.x, at.y)
                    if (degrees == null) failure = goToOutsideText else goTo(degrees.first, degrees.second)
                }
            },
        )
    }
    if (picking) {
        LayerPicker(
            title = stringResource(R.string.pick_layer_title),
            layers = drawables.filter { it.visible },
            onPick = { layer, remember ->
                picking = false
                if (remember) projectId?.let { graph.settings.setPlacingLayer(it, layer.id) }
                startAdding(layer)
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
            // Layers of the same kind (points, lines or areas) take the feature.
            layers = drawables.filter { other -> other.visible && other.name != layer && sameKind(other.geometry, drawables.firstOrNull { it.name == layer }?.geometry) },
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
            onCreate = { name, _, _ ->
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
            onCreate = { name, srs, template ->
                naming = false
                createLayer(name, srs, template)
            },
            graph = graph,
            near = mapCenter?.let { it.longitude to it.latitude },
            kinds = true,
        )
    }
    shapeDraft?.let { d ->
        FeatureForm(
            title = stringResource(if (d.area) R.string.draw_new_area_in else R.string.draw_new_line_in, d.layer),
            fields = formFields(collections[d.layer]),
            properties = JsonObject(emptyMap()),
            coordinates = null,
            isNew = true,
            onDismiss = {
                shapeDraft = null
                drawing = d
            },
            onSave = { values, _ ->
                shapeDraft = null
                addShape(d, values)
            },
            onDelete = null,
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
                // Without a name, what it is and its number: point, line or area.
                title = name ?: stringResource(
                    when (((feature["geometry"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()) {
                        "LineString", "MultiLineString" -> R.string.line_untitled
                        "Polygon", "MultiPolygon" -> R.string.area_untitled
                        else -> R.string.feature_untitled
                    },
                    fid,
                ),
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
                onReshape = reshapeVertices(feature)?.let { (area, vertices) ->
                    {
                        selected = null
                        startDrawing(layer, area)
                        drawing = Drawing(layer, area, vertices, fid)
                    }
                },
                crs = formCrs[layer],
                locate = locate,
                // A line or an area says so when deleted.
                area = (feature["geometry"] as? JsonObject)?.let { g -> (g["type"] as? JsonPrimitive)?.contentOrNull }
                    ?.takeUnless { it.endsWith("Point") }?.contains("Polygon"),
            )
        }
    }
    if (trackPicking) {
        LayerPicker(
            title = stringResource(R.string.track_save_title),
            layers = trackLines,
            showRemember = false,
            onPick = { layer, _ ->
                trackPicking = false
                trackLayer = layer.name
            },
            onNew = { _ ->
                trackPicking = false
                trackNaming = true
            },
            onDismiss = { trackPicking = false },
        )
    }
    if (trackNaming) {
        NewLayerDialog(
            existing = layers.mapNotNull { it.name() },
            suggested = stringResource(R.string.layer_default_track),
            onDismiss = { trackNaming = false },
            onCreate = { name, _, _ ->
                trackNaming = false
                rememberNew = false // a track layer never becomes the layer points go into
                createLayer(name, null, Templates.TRACK.id)
            },
        )
    }
    trackLayer?.let { layer ->
        val t = ownTrack
        if (t != null) {
            val fields = formFields(collections[layer])
            val values = trackValues(layers.firstOrNull { it.name() == layer }, collections[layer], t)
            FeatureForm(
                title = stringResource(R.string.track_new_in, layer),
                fields = fields,
                properties = values,
                coordinates = null,
                isNew = true,
                onDismiss = { trackLayer = null },
                onSave = { entered, _ ->
                    trackLayer = null
                    // Its times are not entered in the form: they come with the track.
                    val times = values.filterKeys { k -> fields.firstOrNull { it.name == k }?.type == "datetime" }
                    saveTrack(layer, JsonObject(times + entered))
                },
                onDelete = null,
            )
        }
    }
    if (discardingTrack) {
        AlertDialog(
            onDismissRequest = { discardingTrack = false },
            title = { Text(stringResource(R.string.track_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    discardingTrack = false
                    graph.tracks.discard()
                }) { Text(stringResource(R.string.track_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { discardingTrack = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (cancellingDrawing) {
        AlertDialog(
            onDismissRequest = { cancellingDrawing = false },
            title = { Text(stringResource(R.string.draw_drop_title)) },
            confirmButton = {
                TextButton(onClick = {
                    cancellingDrawing = false
                    drawing = null
                }) { Text(stringResource(R.string.draw_drop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { cancellingDrawing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (droppingDrawing) {
        AlertDialog(
            onDismissRequest = { droppingDrawing = false },
            title = { Text(stringResource(R.string.draw_drop_title)) },
            confirmButton = {
                TextButton(onClick = {
                    droppingDrawing = false
                    drawing = null
                    requestLeave()
                }) { Text(stringResource(R.string.draw_drop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { droppingDrawing = false }) { Text(stringResource(R.string.cancel)) } },
        )
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

// The map layers that draw the lines and areas of the features source.
private const val FEATURE_LINE = "geocrew-points-line"
private const val FEATURE_FILL = "geocrew-points-fill"

// Features of geometry [type] (Point, LineString, Polygon), single or multi.
private fun isType(type: String): Expression =
    Expression.any(Expression.eq(Expression.geometryType(), Expression.literal(type)), Expression.eq(Expression.geometryType(), Expression.literal("Multi$type")))

// Where the names of lines and areas are shown, as points.
private const val SHAPE_LABELS = "geocrew-shape-labels"

private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

// The measuring tool's source on the map, and its colour.
private const val MEASURE_SOURCE = "geocrew-measure"
private const val MEASURE_COLOR = "#ff6d00"

// A drawing's picked vertex.
private const val PICKED_COLOR = "#1565c0"

// Where drawing snaps: how near, in dp, the layers it snaps to, and the ring that shows it.
private const val SNAP_REACH = 16
private val SNAP_LAYERS = arrayOf(POINT_SOURCE, FEATURE_LINE, FEATURE_FILL)
private const val SNAP_COLOR = "#d500f9"

// Feature properties the map reads: the colour of the point's layer.
private const val COLOR = "_color"

// A feature for the shared source: an id that names its layer, and its layer's colour.
private fun tagged(feature: JsonObject, layer: Int, style: LayerStyle, opacity: Double): JsonObject {
    val properties = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val fid = (feature["id"] as? JsonPrimitive)?.contentOrNull
    val shown = properties + (COLOR to JsonPrimitive(style.mapColorFor(properties))) + (OPACITY to JsonPrimitive(opacity)) +
        (SIZE to JsonPrimitive(style.size)) + (WIDTH to JsonPrimitive(style.width)) + (FILL to JsonPrimitive(style.fill)) +
        // The name label starts just right of the point, however big the point is.
        (LABEL_OFFSET to JsonArray(listOf(JsonPrimitive(style.size + 2), JsonPrimitive(0))))
    return JsonObject(feature + ("id" to JsonPrimitive("$layer/$fid")) + ("properties" to JsonObject(shown)))
}

// The point's layer's opacity.
private const val OPACITY = "_opacity"

// The layer's style: a point's radius, a line's width, an area's fill opacity.
private const val SIZE = "_size"
private const val WIDTH = "_width"
private const val FILL = "_fill"
private const val LABEL_OFFSET = "_label_offset"

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

// The toolbar's place: below the title row, above the bottom buttons; its buttons and the gaps between them.
private val TOOLBAR_TOP = 76.dp
private val TOOLBAR_BOTTOM = 100.dp
private val TOOL_SIZE = 48.dp
private val TOOL_GAP = 12.dp

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
