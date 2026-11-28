package org.geocrew.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.core.edit.ValueRange
import org.geocrew.core.project.Numbers
import org.geocrew.core.project.ProjectFormat
import org.geocrew.storage.RasterActions
import org.geocrew.storage.Workspace

/** A layer of the open project, in its colour on the map, with its fields and how many features it has once read. */
data class LayerInfo(
    val name: String,
    val srs: String? = null,
    val id: String,
    val point: Boolean,
    val color: Color,
    val count: Int = 0,
    val fields: List<FormField> = emptyList(),
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val geometry: String? = null,
    val style: LayerStyle = LayerStyle(color),
    val group: String? = null,
    val shown: Boolean = visible,
)

/** A group in the layer list (ADR 0017): its id, name, whether it is a vector layer, whether it shows, and its layers in order. */
data class GroupInfo(val id: String, val name: String, val vector: Boolean, val visible: Boolean, val layers: List<String>)

/** A node at the top of the layer list: a layer, or a group with its layers. */
sealed interface TopNode {
    data class Single(val layer: String) : TopNode
    data class Group(val group: GroupInfo) : TopNode
}

/** The layer list's top nodes in order, as project.current gives them (ADR 0017); the layers alone for a project without a tree. */
fun listedTree(about: JsonObject): List<TopNode> {
    val groups = about["groups"]?.jsonArray.orEmpty().mapNotNull { g ->
        val o = g.jsonObject
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        id to o
    }.toMap()
    val tree = about["tree"]?.jsonArray ?: return listedLayers(about).map { TopNode.Single(it.name) }
    return tree.mapNotNull { n ->
        val node = n.jsonObject
        (node["layer"] as? JsonPrimitive)?.contentOrNull?.let { return@mapNotNull TopNode.Single(it) }
        val id = (node["group"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val g = groups[id] ?: return@mapNotNull null
        TopNode.Group(
            GroupInfo(
                id,
                (g["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (g["vector"] as? JsonPrimitive)?.contentOrNull == "true",
                (g["visible"] as? JsonPrimitive)?.contentOrNull != "false",
                node["layers"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            ),
        )
    }
}

/** The layers project.current lists, in order, each in its colour on the map. */
fun listedLayers(about: JsonObject): List<LayerInfo> = about["layers"]?.jsonArray.orEmpty().mapNotNull { l ->
    val listed = l.jsonObject
    val name = (listed["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    LayerInfo(
        name,
        (listed["srs"] as? JsonPrimitive)?.contentOrNull,
        (listed["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        (listed["geometry"] as? JsonPrimitive)?.contentOrNull == "POINT",
        styleOf(listed, layerColor(madeIndex(listed))).color,
        geometry = (listed["geometry"] as? JsonPrimitive)?.contentOrNull,
        style = styleOf(listed, layerColor(madeIndex(listed))),
        // Its own switch; whether it shows also takes its group's.
        visible = ((listed["ownVisible"] ?: listed["visible"]) as? JsonPrimitive)?.contentOrNull != "false",
        opacity = (listed["opacity"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 1f,
        group = (listed["group"] as? JsonPrimitive)?.contentOrNull,
        shown = (listed["visible"] as? JsonPrimitive)?.contentOrNull != "false",
    )
}

/** The plural for a count of a layer's features: points, lines or areas. */
fun countOf(geometry: String?): Int = when {
    geometry?.contains("LINE") == true -> R.plurals.lines_count
    geometry?.contains("POLYGON") == true -> R.plurals.areas_count
    else -> R.plurals.points_count
}

/** A raster in a line: its format, and its size in pixels. */
private fun rasterDetail(raster: RasterInfo): String {
    fun text(key: String) = (raster.info[key] as? JsonPrimitive)?.contentOrNull
    val size = text("width")?.let { w -> text("height")?.let { h -> "$w × $h" } }
    return listOfNotNull(text("format") ?: text("driver"), size).joinToString(" · ")
}

/** A basemap row's name: the labels (cia), or the base of the Tianditu map [kind] (img, vec, ter). */
fun basemapName(id: String, kind: String): Int = when {
    id == "cia" -> R.string.basemap_cia
    kind == "vec" -> R.string.basemap_vec
    kind == "ter" -> R.string.basemap_ter
    else -> R.string.basemap_img
}

/** A basemap under the layers, as project.current lists it. */
data class BasemapInfo(val id: String, val visible: Boolean, val opacity: Float)

fun listedBasemaps(about: JsonObject): List<BasemapInfo> = about["basemaps"]?.jsonArray.orEmpty().mapNotNull { b ->
    val listed = b.jsonObject
    val id = (listed["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    BasemapInfo(id, (listed["visible"] as? JsonPrimitive)?.contentOrNull != "false", (listed["opacity"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 1f)
}

/** A change to a layer's fields, made in a project with nothing unsaved: tried again once the edits are saved. */
private sealed interface FieldChange {
    val layer: String

    data class Add(override val layer: String, val name: String, val type: String) : FieldChange

    data class Rename(override val layer: String, val field: String, val to: String) : FieldChange

    data class Delete(override val layer: String, val field: String) : FieldChange
}

/**
 * The project's layers and their fields (ADR 0004), from the map's layers
 * button: each layer's fields fold open under it; fields and layers are added
 * here, and fields renamed and deleted. Everything goes through actions (ADR 0010).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LayersScreen(
    graph: AppGraph,
    project: String,
    onBack: () -> Unit,
    onZoom: (String) -> Unit = {},
    onTable: (String) -> Unit = {},
    onZoomTo: (List<Double>) -> Unit = {},
    onAddFile: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var layers by remember { mutableStateOf<List<LayerInfo>>(emptyList()) }
    var tree by remember { mutableStateOf<List<TopNode>>(emptyList()) }
    // Groups are open until folded.
    val folded = remember { mutableStateMapOf<String, Boolean>() }
    var renamingGroup by remember { mutableStateOf<GroupInfo?>(null) }
    var grouping by remember { mutableStateOf<LayerInfo?>(null) }
    var newGroupFor by remember { mutableStateOf<LayerInfo?>(null) }
    var basemaps by remember { mutableStateOf<List<BasemapInfo>>(emptyList()) }
    var rasters by remember { mutableStateOf<List<RasterInfo>>(emptyList()) }
    var removing by remember { mutableStateOf<RasterInfo?>(null) }
    // Which Tianditu map the base row is: imagery, vector or terrain.
    var kind by remember { mutableStateOf("img") }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    // Which raster and basemap rows are open, by id.
    val folds = remember { mutableStateMapOf<String, Boolean>() }
    var fieldsOf by remember { mutableStateOf<String?>(null) }
    var describing by remember { mutableStateOf<LayerInfo?>(null) }
    var liking by remember { mutableStateOf<LayerInfo?>(null) }
    var exporting by remember { mutableStateOf<LayerInfo?>(null) }
    var exported by remember { mutableStateOf<java.io.File?>(null) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LayerInfo?>(null) }
    var styling by remember { mutableStateOf<LayerInfo?>(null) }
    var deleting by remember { mutableStateOf<LayerInfo?>(null) }
    var adding by remember { mutableStateOf<LayerInfo?>(null) }
    var renamingField by remember { mutableStateOf<Pair<LayerInfo, FormField>?>(null) }
    var deletingField by remember { mutableStateOf<Pair<LayerInfo, FormField>?>(null) }
    var rangingField by remember { mutableStateOf<Pair<LayerInfo, FormField>?>(null) }
    var saveFirst by remember { mutableStateOf<FieldChange?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loads by remember { mutableIntStateOf(0) }
    val texts = FailureTexts(
        unsaved = stringResource(R.string.failure_unsaved),
        failed = stringResource(R.string.action_failed),
        start = stringResource(R.string.start_failed),
        shared = stringResource(R.string.failure_shared),
    )

    // A row put in above the first (a new group, a layer moved up) shows: a list at the top stays there.
    val listState = rememberLazyListState()
    // While a row is dragged, the order the list shows (ADR 0017), saved in one move when it is dropped; a group folded
    // for its drag opens again then.
    var draft by remember { mutableStateOf<List<TopNode>?>(null) }
    var unfoldAfter by remember { mutableStateOf<String?>(null) }
    // The order last sent to be saved, until the project shows it: a drag before then starts from there.
    var saving by remember { mutableStateOf<List<TopNode>?>(null) }
    val reorder = rememberReorderState(listState)
    LaunchedEffect(loads) {
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        val opened = graph.open(project)
        if (opened !is Outcome.Done) {
            failure = texts.of(opened)
            return@LaunchedEffect
        }
        basemaps = listedBasemaps(opened.output.jsonObject)
        tree = listedTree(opened.output.jsonObject)
        draft = null
        saving = null
        if (atTop) listState.requestScrollToItem(0)
        rasters = listedRasters(opened.output.jsonObject)
        kind = (opened.output.jsonObject["id"] as? JsonPrimitive)?.contentOrNull?.let(graph.settings::basemapKind) ?: "img"
        layers = listedLayers(opened.output.jsonObject).mapNotNull { layer ->
            when (val info = graph.invoke(Workspace.LAYER_INFO, buildJsonObject { put("layer", layer.name) })) {
                is Outcome.Done -> {
                    val described = info.output.jsonObject
                    layer.copy(count = described["count"]?.jsonPrimitive?.intOrNull ?: 0, fields = formFields(described))
                }
                else -> {
                    failure = texts.of(info)
                    null
                }
            }
        }
    }

    // A new layer from a template, or [like] another one: its fields, geometry type and CRS.
    fun createLayer(name: String, srs: String?, template: String?, like: String? = null) = scope.launch {
        val params = buildJsonObject {
            put("name", name)
            put("language", AppLanguage.speaking(context))
            if (srs != null) put("srs", srs)
            if (template != null) put("template", template)
            if (like != null) put("like", like)
        }
        when (val made = graph.invoke(Workspace.LAYER_CREATE, params)) {
            is Outcome.Done -> loads++
            else -> failure = texts.of(made)
        }
    }

    // Display changes are saved in the project at once; the page shows them when they are.
    fun display(action: String, params: JsonObject) = scope.launch {
        when (val done = graph.invoke(action, params)) {
            is Outcome.Done -> loads++
            else -> failure = texts.of(done)
        }
    }

    // Rows drag among the rows beside them; a group's open layers go with it. The drop saves where the row ended up.
    reorder.canMove = { key, to -> besides(draft ?: tree, key, to) }
    reorder.onMove = { key, to -> draft = movedRow(draft ?: tree, key, to) }
    reorder.blockOf = { key ->
        val group = (draft ?: tree).firstNotNullOfOrNull { node -> (node as? TopNode.Group)?.group?.takeIf { groupKey(it.id) == key } }
        if (group == null || folded[group.id] == true) listOf(key) else listOf(key) + group.layers.map(::layerKey)
    }
    reorder.onDrop = { key ->
        unfoldAfter?.let { folded.remove(it) }
        unfoldAfter = null
        val order = draft ?: tree
        val params = moveParams(saving ?: tree, order, key)
        if (params == null) {
            if (saving == null) draft = null
        } else {
            saving = order
            scope.launch {
                when (val done = graph.invoke(Workspace.LAYER_MOVE, params)) {
                    is Outcome.Done -> loads++
                    else -> {
                        draft = null
                        saving = null
                        failure = texts.of(done)
                    }
                }
            }
        }
    }

    // A layer's row, opening to its legend and opacity: [at] its place among the [siblings] beside it, at the top or in group
    // [into], set in by [indent]; faded when its group is hidden. One item, so that it drags as one.
    fun androidx.compose.foundation.lazy.LazyListScope.layerRows(layer: LayerInfo, siblings: Int, at: Int, into: String?, indent: Dp, groupShown: Boolean) {
        val expanded = open[layer.name] == true
        val key = layerKey(layer.name)
        item(key = key) {
            Column(reorderable(reorder, key)) {
                LayerRow(
                    title = layer.name,
                    detail = pluralStringResource(countOf(layer.geometry), layer.count, layer.count),
                    visible = layer.visible,
                    onToggle = { display(Workspace.LAYER_DISPLAY, buildJsonObject { put("layer", layer.name); put("visible", !layer.visible) }) },
                    swatch = { LegendSwatch(layer.geometry, legendColors(layer), layer.shown) },
                    onClick = { open[layer.name] = !expanded },
                    modifier = Modifier.alpha(if (groupShown) 1f else 0.6f),
                    indent = indent,
                    handle = { DragHandle(reorder, key) },
                    more = {
                        fun place(index: Int) = buildJsonObject {
                            put("layer", layer.name)
                            into?.let { put("into", it) }
                            put("index", index)
                        }
                        MoreMenu(
                            listOf(
                                MoreItem(R.string.zoom_to_layer, Icons.Rounded.ZoomIn) { onZoom(layer.name) },
                                MoreItem(R.string.attribute_table, Icons.Rounded.TableChart) { onTable(layer.name) },
                                MoreItem(R.string.style, Icons.Rounded.Palette) { styling = layer },
                                MoreItem(R.string.layer_fields, Icons.AutoMirrored.Rounded.List) { fieldsOf = layer.name },
                                MoreItem(R.string.move_up, Icons.Rounded.ArrowUpward, enabled = at > 0) { display(Workspace.LAYER_MOVE, place(at - 1)) },
                                MoreItem(R.string.move_down, Icons.Rounded.ArrowDownward, enabled = at < siblings - 1) { display(Workspace.LAYER_MOVE, place(at + 1)) },
                                MoreItem(R.string.layer_move_to_group, Icons.Rounded.DriveFileMove) { grouping = layer },
                                MoreItem(R.string.layer_export, Icons.Rounded.SaveAlt) { exporting = layer },
                                MoreItem(R.string.layer_like, Icons.Rounded.ContentCopy) { liking = layer },
                                MoreItem(R.string.layer_rename, Icons.Rounded.Edit) { renaming = layer },
                                MoreItem(R.string.layer_properties, Icons.Rounded.Info) { describing = layer },
                                null,
                                MoreItem(R.string.delete, Icons.Rounded.DeleteOutline, danger = true) { deleting = layer },
                            ),
                        )
                    },
                )
                AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(start = ROW_TEXT + indent, end = 16.dp, bottom = 8.dp)) {
                        // The legend of a style by values or ranges: each colour and what it stands for, then the rest.
                        if (layer.style.field != null) {
                            val legend = layer.style.ranges?.map { rangeText(it) to it.color } ?: layer.style.classes.map { it.value to it.color }
                            for ((text, shown) in legend + (stringResource(R.string.style_other) to layer.style.color)) {
                                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ColorDot(shown, 16.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        OpacitySlider(layer.opacity) { opacity ->
                            display(Workspace.LAYER_DISPLAY, buildJsonObject { put("layer", layer.name); put("opacity", opacity.toDouble()) })
                        }
                    }
                }
            }
        }
    }

    // A number field's range is set at once, with nothing to save first; values already there stay.
    fun setRange(layer: String, field: String, min: Double?, max: Double?) = scope.launch {
        val params = buildJsonObject {
            put("layer", layer)
            put("field", field)
            min?.let { put("min", it) }
            max?.let { put("max", it) }
        }
        when (val done = graph.invoke(Workspace.FIELD_RANGE, params)) {
            is Outcome.Done -> loads++
            else -> failure = texts.of(done)
        }
    }

    // A layer's fields change in a project with nothing unsaved: its file is opened again. An attitude is two
    // whole-number fields, dip direction and dip, added one after the other.
    val attitudeNames = attitudeNames()
    fun changeField(change: FieldChange) = scope.launch {
        val steps = if (change is FieldChange.Add && change.type == ATTITUDE) {
            attitudeNames.map { FieldChange.Add(change.layer, it, "integer") }
        } else {
            listOf(change)
        }
        for (step in steps) {
            val params = buildJsonObject {
                put("layer", step.layer)
                when (step) {
                    is FieldChange.Add -> {
                        put("name", step.name)
                        put("type", step.type)
                    }
                    is FieldChange.Rename -> {
                        put("field", step.field)
                        put("to", step.to)
                    }
                    is FieldChange.Delete -> put("field", step.field)
                }
            }
            val action = when (step) {
                is FieldChange.Add -> Workspace.FIELD_ADD
                is FieldChange.Rename -> Workspace.FIELD_RENAME
                is FieldChange.Delete -> Workspace.FIELD_DELETE
            }
            when (val done = graph.invoke(action, params)) {
                is Outcome.Done -> loads++
                is Outcome.Failed -> {
                    if (done.code == "unsaved") saveFirst = change else failure = texts.of(done)
                    return@launch
                }
                else -> {
                    failure = texts.of(done)
                    return@launch
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.map_layers)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            if (layers.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.layers_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // One row a layer, folded (ADR 0017): shown or hidden, its look in small, its name and count, and the ⋮ with
            // everything else. Opened, the row shows the full legend and how opaque the layer is. A group's layers come
            // under it, set in, unless it is folded.
            val byName = layers.associateBy { it.name }
            for ((top, node) in (draft ?: tree).withIndex()) {
                when (node) {
                    is TopNode.Single -> byName[node.layer]?.let { layer ->
                        layerRows(layer, siblings = tree.size, at = top, into = null, indent = 0.dp, groupShown = true)
                    }
                    is TopNode.Group -> {
                        val group = node.group
                        val open = folded[group.id] != true
                        val key = groupKey(group.id)
                        item(key = key) {
                            LayerRow(
                                title = group.name,
                                detail = pluralStringResource(R.plurals.layers_count, group.layers.size, group.layers.size),
                                visible = group.visible,
                                onToggle = { display(Workspace.GROUP_DISPLAY, buildJsonObject { put("group", group.id); put("visible", !group.visible) }) },
                                // A vector layer's group (its points, lines and areas) apart from a folder of layers.
                                swatch = { Icon(if (group.vector) Icons.Rounded.Layers else if (open) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, null) },
                                onClick = { folded[group.id] = open },
                                modifier = reorderable(reorder, key),
                                // A group drags folded, its layers with it, and opens again where it is dropped.
                                handle = {
                                    DragHandle(reorder, key, onStart = {
                                        if (folded[group.id] != true) {
                                            folded[group.id] = true
                                            unfoldAfter = group.id
                                        }
                                    })
                                },
                                more = {
                                    MoreMenu(
                                        listOf(
                                            MoreItem(R.string.move_up, Icons.Rounded.ArrowUpward, enabled = top > 0) {
                                                display(Workspace.LAYER_MOVE, buildJsonObject { put("group", group.id); put("index", top - 1) })
                                            },
                                            MoreItem(R.string.move_down, Icons.Rounded.ArrowDownward, enabled = top < tree.size - 1) {
                                                display(Workspace.LAYER_MOVE, buildJsonObject { put("group", group.id); put("index", top + 1) })
                                            },
                                            MoreItem(R.string.layer_rename, Icons.Rounded.Edit) { renamingGroup = group },
                                            null,
                                            MoreItem(R.string.group_ungroup, Icons.Rounded.FolderOff) {
                                                display(Workspace.GROUP_REMOVE, buildJsonObject { put("group", group.id) })
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                        if (open) {
                            for ((i, name) in group.layers.withIndex()) {
                                byName[name]?.let { layer ->
                                    layerRows(layer, siblings = group.layers.size, at = i, into = group.id, indent = GROUP_INDENT, groupShown = group.visible)
                                }
                            }
                        }
                    }
                }
            }
            if (rasters.isNotEmpty()) {
                item(key = "rasters") { SectionHeader(stringResource(R.string.raster_section)) }
                for (raster in rasters) {
                    val expanded = folds["raster:${raster.id}"] == true
                    item(key = "raster:${raster.id}") {
                        LayerRow(
                            title = raster.name,
                            detail = if (raster.missing) stringResource(R.string.raster_missing) else rasterDetail(raster),
                            detailError = raster.missing,
                            visible = raster.visible,
                            onToggle = { display(RasterActions.DISPLAY, buildJsonObject { put("name", raster.name); put("visible", !raster.visible) }) },
                            swatch = { Icon(Icons.Rounded.Terrain, null, modifier = Modifier.alpha(if (raster.visible) 1f else 0.38f)) },
                            onClick = { folds["raster:${raster.id}"] = !expanded },
                            modifier = Modifier.animateItem(),
                            more = {
                                // A single band (a DEM) can be drawn in grey, shaded, or coloured by height.
                                val renders = if (raster.single && !raster.missing) {
                                    listOf(
                                        Triple(RENDER_DEFAULT, R.string.raster_grey, Icons.Rounded.Contrast),
                                        Triple(RENDER_HILLSHADE, R.string.raster_hillshade, Icons.Rounded.Landscape),
                                        Triple(RENDER_RELIEF, R.string.raster_relief, Icons.Rounded.Palette),
                                    )
                                } else {
                                    emptyList()
                                }
                                MoreMenu(
                                    listOf(
                                        MoreItem(R.string.zoom_to_layer, Icons.Rounded.ZoomIn, enabled = raster.extent != null && !raster.missing) {
                                            raster.extent?.let(onZoomTo)
                                        },
                                    ) + renders.map { (render, label, icon) ->
                                        MoreItem(label, icon, checked = raster.render == render) {
                                            display(RasterActions.DISPLAY, buildJsonObject { put("name", raster.name); put("render", render) })
                                        }
                                    } + listOf(null, MoreItem(R.string.raster_remove, Icons.Rounded.DeleteOutline, danger = true) { removing = raster }),
                                )
                            },
                        )
                    }
                    if (expanded) {
                        item(key = "raster-display:${raster.id}") {
                            Column(Modifier.animateItem().padding(start = ROW_TEXT, end = 16.dp, bottom = 8.dp)) {
                                OpacitySlider(raster.opacity) { opacity ->
                                    display(RasterActions.DISPLAY, buildJsonObject { put("name", raster.name); put("opacity", opacity.toDouble()) })
                                }
                            }
                        }
                    }
                }
            }
            if (basemaps.isNotEmpty()) {
                item(key = "basemaps") { SectionHeader(stringResource(R.string.basemap_section)) }
                for (basemap in basemaps.reversed()) {
                    val expanded = folds["basemap:${basemap.id}"] == true
                    item(key = "basemap:${basemap.id}") {
                        LayerRow(
                            title = stringResource(basemapName(basemap.id, kind)),
                            detail = null,
                            visible = basemap.visible,
                            onToggle = { display(Workspace.BASEMAP_DISPLAY, buildJsonObject { put("basemap", basemap.id); put("visible", !basemap.visible) }) },
                            swatch = { Icon(Icons.Rounded.Public, null, modifier = Modifier.alpha(if (basemap.visible) 1f else 0.38f)) },
                            onClick = { folds["basemap:${basemap.id}"] = !expanded },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (expanded) {
                        item(key = "basemap-display:${basemap.id}") {
                            Column(Modifier.animateItem().padding(start = ROW_TEXT, end = 16.dp, bottom = 8.dp)) {
                                OpacitySlider(basemap.opacity) { opacity ->
                                    display(Workspace.BASEMAP_DISPLAY, buildJsonObject { put("basemap", basemap.id); put("opacity", opacity.toDouble()) })
                                }
                            }
                        }
                    }
                }
            }
            item(key = "new") {
                // A new layer, or data from a file (a raster for now).
                FlowRow(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { naming = true }) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.layer_new))
                    }
                    OutlinedButton(onClick = onAddFile) {
                        Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.layer_from_file))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    removing?.let { raster ->
        AppDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.raster_remove_title, raster.name)) },
            text = { Text(stringResource(R.string.raster_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    display(RasterActions.REMOVE, buildJsonObject { put("name", raster.name) })
                }) { Text(stringResource(R.string.raster_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (naming) {
        NewLayerDialog(
            existing = layers.map { it.name },
            suggested = stringResource(R.string.layer_default_name),
            onDismiss = { naming = false },
            onCreate = { name, srs, template ->
                naming = false
                createLayer(name, srs, template)
            },
            kinds = true,
            graph = graph,
            // The zones that apply follow where the map was last.
            near = graph.cameras[project]?.target?.let { it.longitude to it.latitude },
        )
    }
    fieldsOf?.let { name ->
        val layer = layers.firstOrNull { it.name == name }
        if (layer != null) {
            FieldsSheet(
                layer = layer.name,
                fields = layer.fields,
                detail = { field -> field.range?.let { stringResource(R.string.field_type_range, typeName(field.type), rangeText(it)) } ?: typeName(field.type) },
                icon = { fieldIcon(it.type) },
                menu = { field ->
                    FieldMenu(
                        onRename = { renamingField = layer to field },
                        onRange = if (field.type in NUMBER_TYPES) ({ rangingField = layer to field }) else null,
                        onDelete = { deletingField = layer to field },
                    )
                },
                onAdd = { adding = layer },
                onDismiss = { fieldsOf = null },
            )
        }
    }
    grouping?.let { layer ->
        // Into a group, a new one, or out of the one it is in, just under it.
        val groups = tree.mapNotNull { (it as? TopNode.Group)?.group }
        GroupPicker(
            layer = layer.name,
            groups = groups.filter { it.id != layer.group }.map { it.id to it.name },
            inGroup = layer.group != null,
            onPick = { id ->
                grouping = null
                display(Workspace.LAYER_MOVE, buildJsonObject { put("layer", layer.name); put("into", id); put("index", Int.MAX_VALUE / 2) })
            },
            onNew = {
                grouping = null
                newGroupFor = layer
            },
            onOut = {
                grouping = null
                val at = tree.indexOfFirst { (it as? TopNode.Group)?.group?.id == layer.group }
                display(Workspace.LAYER_MOVE, buildJsonObject { put("layer", layer.name); put("index", at + 1) })
            },
            onDismiss = { grouping = null },
        )
    }
    newGroupFor?.let { layer ->
        GroupNameDialog(
            title = stringResource(R.string.layer_group_new),
            current = "",
            existing = tree.mapNotNull { (it as? TopNode.Group)?.group?.name },
            onDismiss = { newGroupFor = null },
            onDone = { name ->
                newGroupFor = null
                display(Workspace.GROUP_CREATE, buildJsonObject { put("name", name); putJsonArray("layers") { add(layer.name) } })
            },
        )
    }
    renamingGroup?.let { group ->
        GroupNameDialog(
            title = stringResource(R.string.layer_rename),
            current = group.name,
            existing = tree.mapNotNull { (it as? TopNode.Group)?.group?.name },
            onDismiss = { renamingGroup = null },
            onDone = { name ->
                renamingGroup = null
                display(Workspace.GROUP_RENAME, buildJsonObject { put("group", group.id); put("to", name) })
            },
        )
    }
    exporting?.let { layer ->
        // A layer on its own, for another program, in a format chosen from the list.
        ExportSheet(
            title = stringResource(R.string.layer_export_title, layer.name),
            geometry = layer.geometry,
            wholeProject = false,
            onPick = { format ->
                exporting = null
                scope.launch {
                    val params = buildJsonObject {
                        put("format", format.id)
                        put("layer", layer.name)
                        put("language", AppLanguage.speaking(context))
                    }
                    val done = graph.invoke(Workspace.EXPORT, params)
                    if (done is Outcome.Done) exported = java.io.File(done.output.jsonObject.getValue("path").jsonPrimitive.content) else failure = texts.of(done)
                }
            },
            onDismiss = { exporting = null },
        )
    }
    exported?.let { file ->
        val title = stringResource(R.string.share_title)
        AppDialog(
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
    describing?.let { layer ->
        LayerPropertiesDialog(layer.name, layer.geometry, layer.count, layer.fields.size, layer.srs, onDismiss = { describing = null })
    }
    liking?.let { layer ->
        NewLayerDialog(
            existing = layers.map { it.name },
            suggested = layer.name,
            onDismiss = { liking = null },
            onCreate = { name, _, _ ->
                liking = null
                createLayer(name, null, null, like = layer.name)
            },
        )
    }
    styling?.let { layer ->
        // The layer's features, for the values a categorized style colours.
        var collection by remember(layer.name) { mutableStateOf<JsonObject?>(null) }
        LaunchedEffect(layer.name) {
            val read = graph.invoke(Workspace.FEATURES, buildJsonObject { put("layer", layer.name) })
            if (read !is Outcome.Done) failure = texts.of(read)
            collection = (read as? Outcome.Done)?.output as? JsonObject ?: JsonObject(emptyMap())
        }
        StyleDialog(
            layer = layer.name,
            geometry = layer.geometry,
            style = layer.style,
            collection = collection,
            onDismiss = { styling = null },
            onSave = { style ->
                styling = null
                display(Workspace.LAYER_STYLE, buildJsonObject { put("layer", layer.name); put("style", style.json()) })
            },
            onReset = {
                styling = null
                display(Workspace.LAYER_STYLE, buildJsonObject { put("layer", layer.name); put("style", JsonObject(emptyMap())) })
            },
        )
    }
    renaming?.let { layer ->
        RenameLayerDialog(
            current = layer.name,
            existing = layers.map { it.name },
            onDismiss = { renaming = null },
            onRename = { to ->
                renaming = null
                open.remove(layer.name)?.let { open[to] = it }
                display(Workspace.LAYER_RENAME, buildJsonObject { put("layer", layer.name); put("to", to) })
            },
        )
    }
    deleting?.let { layer ->
        AppDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text(stringResource(R.string.layer_delete_title, layer.name)) },
            text = { Text(pluralStringResource(R.plurals.layer_delete_body, layer.count, layer.count)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    display(Workspace.LAYER_DELETE, buildJsonObject { put("layer", layer.name) })
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    adding?.let { layer ->
        AddFieldDialog(
            existing = layer.fields.map { it.name },
            onDismiss = { adding = null },
            onAdd = { name, type ->
                adding = null
                changeField(FieldChange.Add(layer.name, name, type))
            },
        )
    }
    rangingField?.let { (layer, field) ->
        RangeDialog(field, onDismiss = { rangingField = null }) { min, max ->
            rangingField = null
            setRange(layer.name, field.name, min, max)
        }
    }
    renamingField?.let { (layer, field) ->
        RenameFieldDialog(
            current = field.name,
            existing = layer.fields.map { it.name },
            onDismiss = { renamingField = null },
            onRename = { to ->
                renamingField = null
                changeField(FieldChange.Rename(layer.name, field.name, to))
            },
        )
    }
    deletingField?.let { (layer, field) ->
        AppDialog(
            onDismissRequest = { deletingField = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text(stringResource(R.string.field_delete_title, field.name)) },
            text = { Text(stringResource(R.string.field_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingField = null
                    changeField(FieldChange.Delete(layer.name, field.name))
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingField = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    saveFirst?.let { change ->
        AppDialog(
            onDismissRequest = { saveFirst = null },
            icon = { Icon(Icons.Rounded.Save, null) },
            title = { Text(stringResource(R.string.save_first_title)) },
            text = {
                Text(
                    stringResource(
                        when (change) {
                            is FieldChange.Add -> R.string.save_first_body
                            is FieldChange.Rename -> R.string.save_first_rename_body
                            is FieldChange.Delete -> R.string.save_first_delete_body
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    saveFirst = null
                    scope.launch {
                        when (val saved = graph.invoke(Editing.SAVE_ALL)) {
                            is Outcome.Done -> changeField(change)
                            else -> failure = texts.of(saved)
                        }
                    }
                }) {
                    Text(
                        stringResource(
                            when (change) {
                                is FieldChange.Add -> R.string.save_and_add
                                is FieldChange.Rename -> R.string.save_and_rename
                                is FieldChange.Delete -> R.string.save_and_delete
                            },
                        ),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { saveFirst = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    failure?.let { message ->
        AppDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(message) },
        )
    }
}

/** What can be done to a field from its row: rename it, set the numbers it takes ([onRange], for number fields), delete it. */
@Composable
private fun FieldMenu(onRename: () -> Unit, onRange: (() -> Unit)?, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.files_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.layer_rename)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            if (onRange != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.field_range)) },
                    leadingIcon = { Icon(Icons.Rounded.LinearScale, null) },
                    onClick = {
                        open = false
                        onRange()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

/** A range in short, as a field's row shows it: "0–90", "≥ 0", "> 0, ≤ 360". */
@Composable
private fun rangeText(range: ValueRange): String {
    val min = range.min
    val max = range.max
    if (min != null && max != null && range.minIncluded && range.maxIncluded) {
        return stringResource(R.string.range_closed, plainNumber(min), plainNumber(max))
    }
    val low = min?.let { stringResource(if (range.minIncluded) R.string.range_from else R.string.range_above, plainNumber(it)) }
    val high = max?.let { stringResource(if (range.maxIncluded) R.string.range_to else R.string.range_below, plainNumber(it)) }
    return if (low != null && high != null) stringResource(R.string.range_both, low, high) else low ?: high.orEmpty()
}

/** The numbers [field] takes: from a minimum, to a maximum, or both, each end in the range; with neither, any number. */
@Composable
private fun RangeDialog(field: FormField, onDismiss: () -> Unit, onSave: (min: Double?, max: Double?) -> Unit) {
    val start = field.range
    var min by remember { mutableStateOf(start?.min?.let(::plainNumber).orEmpty()) }
    var max by remember { mutableStateOf(start?.max?.let(::plainNumber).orEmpty()) }
    val low = Numbers.double(min)
    val high = Numbers.double(max)
    val minProblem = if (min.isNotBlank() && low == null) R.string.field_not_a_number else null
    val maxProblem = when {
        max.isNotBlank() && high == null -> R.string.field_not_a_number
        low != null && high != null && high < low -> R.string.range_max_below_min
        else -> null
    }
    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        title = { Text(stringResource(R.string.field_range_title, field.name)) },
        text = {
            Column {
                RangeEnd(min, { min = it }, stringResource(R.string.range_min), minProblem)
                RangeEnd(max, { max = it }, stringResource(R.string.range_max), maxProblem)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(low, high) },
                enabled = (low != start?.min || high != start?.max) && minProblem == null && maxProblem == null,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// One end of a range, a number or nothing.
@Composable
private fun RangeEnd(value: String, onChange: (String) -> Unit, label: String, problem: Int?) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = problem != null,
        supportingText = problem?.let { { Text(stringResource(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A field's new name, checked like a new field's: its own in the layer, in any case. */
@Composable
private fun RenameFieldDialog(current: String, existing: List<String>, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val trimmed = name.trim()
    val taken = (existing - current + Workspace.RESERVED).any { it.equals(trimmed, ignoreCase = true) }
    val valid = trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME && trimmed != current && !taken
    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        title = { Text(stringResource(R.string.field_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true,
                isError = taken,
                supportingText = { if (taken) Text(stringResource(R.string.field_name_taken)) },
            )
        },
        confirmButton = { TextButton(onClick = { onRename(trimmed) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A layer's new name, checked like a new layer's. */
@Composable
private fun RenameLayerDialog(current: String, existing: List<String>, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val trimmed = name.trim()
    val taken = existing.any { it.equals(trimmed, ignoreCase = true) && !it.equals(current, ignoreCase = true) }
    val reserved = listOf("gpkg_", "rtree_", "sqlite_", "geocrew_").any { trimmed.startsWith(it, ignoreCase = true) }
    val valid = trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME && trimmed != current && !taken && !reserved
    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        title = { Text(stringResource(R.string.layer_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.layer_name)) },
                singleLine = true,
                isError = taken || reserved,
                supportingText = {
                    when {
                        taken -> Text(stringResource(R.string.layer_name_taken))
                        reserved -> Text(stringResource(R.string.layer_name_reserved))
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = { onRename(trimmed) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** How opaque a layer is, set when the finger lifts: each change is written to the project. */
// How far a group's layers are set in under it.
private val GROUP_INDENT = 24.dp

// A layer's colours for its swatch: its own, or the first of a style by values or ranges.
private fun legendColors(layer: LayerInfo): List<Color> =
    if (layer.style.field == null) {
        listOf(layer.color)
    } else {
        (layer.style.ranges?.map { it.color } ?: layer.style.classes.map { it.color }).take(3).ifEmpty { listOf(layer.color) }
    }

@Composable
private fun OpacitySlider(opacity: Float, onSet: (Float) -> Unit) {
    var value by remember(opacity) { mutableFloatStateOf(opacity) }
    Text(stringResource(R.string.layer_opacity, Math.round(value * 100)), style = MaterialTheme.typography.bodyMedium)
    Slider(value = value, onValueChange = { value = it }, onValueChangeFinished = { onSet(value) }, valueRange = 0f..1f)
}

// A field's type at a glance.
private fun fieldIcon(type: String): ImageVector = when (type) {
    "real", "integer", "integer64" -> Icons.Rounded.Numbers
    "date", "datetime" -> Icons.Rounded.Event
    "boolean" -> Icons.Rounded.ToggleOn
    else -> Icons.Rounded.TextFields
}
