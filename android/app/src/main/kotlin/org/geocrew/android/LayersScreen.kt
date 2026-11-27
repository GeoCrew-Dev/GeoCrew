package org.geocrew.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
)

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
        visible = (listed["visible"] as? JsonPrimitive)?.contentOrNull != "false",
        opacity = (listed["opacity"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 1f,
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

/** A field to add once the project's edits are saved. */
private data class NewField(val layer: String, val name: String, val type: String)

/**
 * The project's layers and their fields (ADR 0004), from the map's layers
 * button: each layer's fields fold open under it; fields and layers are added
 * here. Everything goes through actions (ADR 0010).
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
    var basemaps by remember { mutableStateOf<List<BasemapInfo>>(emptyList()) }
    var rasters by remember { mutableStateOf<List<RasterInfo>>(emptyList()) }
    var removing by remember { mutableStateOf<RasterInfo?>(null) }
    // Which Tianditu map the base row is: imagery, vector or terrain.
    var kind by remember { mutableStateOf("img") }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LayerInfo?>(null) }
    var styling by remember { mutableStateOf<LayerInfo?>(null) }
    var deleting by remember { mutableStateOf<LayerInfo?>(null) }
    var adding by remember { mutableStateOf<LayerInfo?>(null) }
    var saveFirst by remember { mutableStateOf<NewField?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loads by remember { mutableIntStateOf(0) }
    val texts = FailureTexts(
        unsaved = stringResource(R.string.failure_unsaved),
        failed = stringResource(R.string.action_failed),
        start = stringResource(R.string.start_failed),
    )

    LaunchedEffect(loads) {
        val opened = graph.open(project)
        if (opened !is Outcome.Done) {
            failure = texts.of(opened)
            return@LaunchedEffect
        }
        basemaps = listedBasemaps(opened.output.jsonObject)
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

    fun createLayer(name: String, srs: String?, template: String?) = scope.launch {
        val params = buildJsonObject {
            put("name", name)
            put("language", AppLanguage.speaking(context))
            if (srs != null) put("srs", srs)
            if (template != null) put("template", template)
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

    // A field is added to a project with nothing unsaved: its file is opened again.
    fun addField(field: NewField) = scope.launch {
        val params = buildJsonObject { put("layer", field.layer); put("name", field.name); put("type", field.type) }
        when (val added = graph.invoke(Workspace.FIELD_ADD, params)) {
            is Outcome.Done -> loads++
            is Outcome.Failed -> if (added.code == "unsaved") saveFirst = field else failure = texts.of(added)
            else -> failure = texts.of(added)
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
        val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
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
            for (layer in layers) {
                val expanded = open[layer.name] == true
                item(key = "layer:${layer.name}") {
                    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
                    ListItem(
                        headlineContent = { Text(layer.name, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(
                                pluralStringResource(countOf(layer.geometry), layer.count, layer.count) + " · " +
                                    pluralStringResource(R.plurals.fields_count, layer.fields.size, layer.fields.size),
                            )
                        },
                        leadingContent = { LayerDot(if (layer.visible) layer.color else layer.color.copy(alpha = 0.3f)) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { display(Workspace.LAYER_DISPLAY, buildJsonObject { put("layer", layer.name); put("visible", !layer.visible) }) }) {
                                    Icon(
                                        if (layer.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        stringResource(if (layer.visible) R.string.layer_hide else R.string.layer_show),
                                    )
                                }
                                Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.rotate(turn))
                            }
                        },
                        colors = clear,
                        modifier = Modifier.animateItem().clickable { open[layer.name] = !expanded },
                    )
                }
                if (expanded) {
                    item(key = "display:${layer.name}") {
                        Column(Modifier.animateItem().padding(start = 40.dp, end = 16.dp)) {
                            OpacitySlider(layer.opacity) { opacity ->
                                display(Workspace.LAYER_DISPLAY, buildJsonObject { put("layer", layer.name); put("opacity", opacity.toDouble()) })
                            }
                            // Up and down the list: the first is drawn on top.
                            val at = layers.indexOf(layer)
                            Row {
                                TextButton(onClick = { display(Workspace.LAYER_ORDER, order(layers, at, at - 1)) }, enabled = at > 0) {
                                    Icon(Icons.Rounded.ArrowUpward, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.move_up))
                                }
                                TextButton(onClick = { display(Workspace.LAYER_ORDER, order(layers, at, at + 1)) }, enabled = at < layers.size - 1) {
                                    Icon(Icons.Rounded.ArrowDownward, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.move_down))
                                }
                            }
                        }
                    }
                    items(layer.fields, key = { "field:${layer.name}:${it.name}" }) { field ->
                        ListItem(
                            headlineContent = { Text(field.name) },
                            supportingContent = { Text(typeName(field.type)) },
                            leadingContent = { Icon(fieldIcon(field.type), null) },
                            colors = clear,
                            modifier = Modifier.animateItem().padding(start = 24.dp),
                        )
                    }
                    item(key = "add:${layer.name}") {
                        FilledTonalButton(onClick = { adding = layer }, modifier = Modifier.animateItem().padding(start = 40.dp, top = 4.dp, bottom = 4.dp)) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.field_add))
                        }
                    }
                    item(key = "about:${layer.name}") {
                        Column(Modifier.animateItem().padding(start = 40.dp, end = 16.dp, bottom = 12.dp)) {
                            layer.srs?.let {
                                Text(
                                    stringResource(R.string.layer_crs_value, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            FlowRow {
                                TextButton(onClick = { styling = layer }) {
                                    Icon(Icons.Rounded.Palette, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.style))
                                }
                                TextButton(onClick = { onTable(layer.name) }) {
                                    Icon(Icons.Rounded.TableChart, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.attribute_table))
                                }
                                TextButton(onClick = { onZoom(layer.name) }) {
                                    Icon(Icons.Rounded.ZoomIn, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.zoom_to_layer))
                                }
                                TextButton(onClick = { renaming = layer }) {
                                    Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.layer_rename))
                                }
                                TextButton(onClick = { deleting = layer }) {
                                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            if (rasters.isNotEmpty()) {
                item(key = "rasters") { SectionHeader(stringResource(R.string.raster_section)) }
                for (raster in rasters) {
                    item(key = "raster:${raster.id}") {
                        Column(Modifier.animateItem()) {
                            ListItem(
                                headlineContent = { Text(raster.name) },
                                supportingContent = {
                                    if (raster.missing) {
                                        Text(stringResource(R.string.raster_missing), color = MaterialTheme.colorScheme.error)
                                    } else {
                                        Text(rasterDetail(raster))
                                    }
                                },
                                leadingContent = { Icon(Icons.Rounded.Terrain, null) },
                                trailingContent = {
                                    IconButton(onClick = { display(RasterActions.DISPLAY, buildJsonObject { put("name", raster.name); put("visible", !raster.visible) }) }) {
                                        Icon(
                                            if (raster.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                            stringResource(if (raster.visible) R.string.layer_hide else R.string.layer_show),
                                        )
                                    }
                                },
                                colors = clear,
                            )
                            Column(Modifier.padding(start = 56.dp, end = 16.dp)) {
                                OpacitySlider(raster.opacity) { opacity ->
                                    display(RasterActions.DISPLAY, buildJsonObject { put("name", raster.name); put("opacity", opacity.toDouble()) })
                                }
                                Row {
                                    TextButton(onClick = { raster.extent?.let(onZoomTo) }, enabled = raster.extent != null && !raster.missing) {
                                        Icon(Icons.Rounded.ZoomIn, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.zoom_to_layer))
                                    }
                                    TextButton(onClick = { removing = raster }) {
                                        Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.raster_remove), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (basemaps.isNotEmpty()) {
                item(key = "basemaps") { SectionHeader(stringResource(R.string.basemap_section)) }
                for (basemap in basemaps.reversed()) {
                    item(key = "basemap:${basemap.id}") {
                        Column(Modifier.animateItem()) {
                            ListItem(
                                headlineContent = { Text(stringResource(basemapName(basemap.id, kind))) },
                                leadingContent = { Icon(Icons.Rounded.Public, null) },
                                trailingContent = {
                                    IconButton(onClick = { display(Workspace.BASEMAP_DISPLAY, buildJsonObject { put("basemap", basemap.id); put("visible", !basemap.visible) }) }) {
                                        Icon(
                                            if (basemap.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                            stringResource(if (basemap.visible) R.string.layer_hide else R.string.layer_show),
                                        )
                                    }
                                },
                                colors = clear,
                            )
                            Column(Modifier.padding(start = 56.dp, end = 16.dp)) {
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
        AlertDialog(
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
    styling?.let { layer ->
        // The layer's features, for the values a categorized style colours.
        var collection by remember(layer.name) { mutableStateOf<JsonObject?>(null) }
        LaunchedEffect(layer.name) {
            val read = graph.invoke(Workspace.FEATURES, buildJsonObject { put("layer", layer.name) })
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
        AlertDialog(
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
                addField(NewField(layer.name, name, type))
            },
        )
    }
    saveFirst?.let { field ->
        AlertDialog(
            onDismissRequest = { saveFirst = null },
            icon = { Icon(Icons.Rounded.Save, null) },
            title = { Text(stringResource(R.string.save_first_title)) },
            text = { Text(stringResource(R.string.save_first_body)) },
            confirmButton = {
                TextButton(onClick = {
                    saveFirst = null
                    scope.launch {
                        when (val saved = graph.invoke(Editing.SAVE_ALL)) {
                            is Outcome.Done -> addField(field)
                            else -> failure = texts.of(saved)
                        }
                    }
                }) { Text(stringResource(R.string.save_and_add)) }
            },
            dismissButton = { TextButton(onClick = { saveFirst = null }) { Text(stringResource(R.string.cancel)) } },
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

/** A layer's new name, checked like a new layer's. */
@Composable
private fun RenameLayerDialog(current: String, existing: List<String>, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val trimmed = name.trim()
    val taken = existing.any { it.equals(trimmed, ignoreCase = true) && !it.equals(current, ignoreCase = true) }
    val reserved = listOf("gpkg_", "rtree_", "sqlite_", "geocrew_").any { trimmed.startsWith(it, ignoreCase = true) }
    val valid = trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME && trimmed != current && !taken && !reserved
    AlertDialog(
        onDismissRequest = onDismiss,
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

// The layer list with the layer at [from] moved to [to], for layer.order.
private fun order(layers: List<LayerInfo>, from: Int, to: Int): JsonObject {
    val names = layers.map { it.name }.toMutableList()
    names.add(to, names.removeAt(from))
    return buildJsonObject { putJsonArray("layers") { names.forEach { add(it) } } }
}

/** How opaque a layer is, set when the finger lifts: each change is written to the project. */
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
