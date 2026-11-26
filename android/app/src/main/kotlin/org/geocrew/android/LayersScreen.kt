package org.geocrew.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.storage.Workspace

/** A layer of the open project, in its colour on the map, with its fields and how many features it has once read. */
data class LayerInfo(val name: String, val id: String, val point: Boolean, val color: Color, val count: Int = 0, val fields: List<FormField> = emptyList())

/** The layers project.current lists, in order, each in its colour on the map. */
fun listedLayers(about: JsonObject): List<LayerInfo> = about["layers"]?.jsonArray.orEmpty().mapIndexedNotNull { index, l ->
    val listed = l.jsonObject
    val name = (listed["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
    LayerInfo(
        name,
        (listed["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        (listed["geometry"] as? JsonPrimitive)?.contentOrNull == "POINT",
        layerColor(index),
    )
}

/** A field to add once the project's edits are saved. */
private data class NewField(val layer: String, val name: String, val type: String)

/**
 * The project's layers and their fields (ADR 0004), from the map's layers
 * button: each layer's fields fold open under it; fields and layers are added
 * here. Everything goes through actions (ADR 0010).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersScreen(graph: AppGraph, project: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var layers by remember { mutableStateOf<List<LayerInfo>>(emptyList()) }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    var naming by remember { mutableStateOf(false) }
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

    fun createLayer(name: String, srs: String?) = scope.launch {
        val params = buildJsonObject { put("name", name); put("language", AppLanguage.speaking(context)); if (srs != null) put("srs", srs) }
        when (val made = graph.invoke(Workspace.LAYER_CREATE, params)) {
            is Outcome.Done -> loads++
            else -> failure = texts.of(made)
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
                                pluralStringResource(R.plurals.points_count, layer.count, layer.count) + " · " +
                                    pluralStringResource(R.plurals.fields_count, layer.fields.size, layer.fields.size),
                            )
                        },
                        leadingContent = { LayerDot(layer.color) },
                        trailingContent = { Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.rotate(turn)) },
                        colors = clear,
                        modifier = Modifier.animateItem().clickable { open[layer.name] = !expanded },
                    )
                }
                if (expanded) {
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
                        FilledTonalButton(onClick = { adding = layer }, modifier = Modifier.animateItem().padding(start = 40.dp, top = 4.dp, bottom = 12.dp)) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.field_add))
                        }
                    }
                }
            }
            item(key = "new") {
                OutlinedButton(onClick = { naming = true }, modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.layer_new))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (naming) {
        NewLayerDialog(
            existing = layers.map { it.name },
            suggested = stringResource(R.string.layer_default_name),
            onDismiss = { naming = false },
            onCreate = { name, srs ->
                naming = false
                createLayer(name, srs)
            },
            // The Gauss-Kruger zone follows where the map was last.
            centerLongitude = graph.cameras[project]?.target?.longitude,
            describe = { srs ->
                ((graph.invoke(Workspace.CRS_DESCRIBE, buildJsonObject { put("srs", srs) }) as? Outcome.Done)?.output?.jsonObject?.get("name") as? JsonPrimitive)?.contentOrNull
            },
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

// A field's type at a glance.
private fun fieldIcon(type: String): ImageVector = when (type) {
    "real", "integer", "integer64" -> Icons.Rounded.Numbers
    "date", "datetime" -> Icons.Rounded.Event
    "boolean" -> Icons.Rounded.ToggleOn
    else -> Icons.Rounded.TextFields
}
