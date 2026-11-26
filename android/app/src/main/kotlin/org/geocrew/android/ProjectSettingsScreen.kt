package org.geocrew.android

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Place
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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

/** A layer of the project, as layer.info describes it. */
private data class LayerInfo(val name: String, val count: Int, val fields: List<FormField>)

/** A field to add once the project's edits are saved. */
private data class NewField(val layer: String, val name: String, val type: String)

/**
 * A project's settings, opened from its map's menu: where the project is kept,
 * and its layers with their fields. A point comes with a name and a
 * description, its coordinates being its position; every other field the user
 * adds here (ADR 0004). Everything goes through actions (ADR 0010).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSettingsScreen(graph: AppGraph, project: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf<String?>(null) }
    var layers by remember { mutableStateOf<List<LayerInfo>>(emptyList()) }
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
        val about = opened.output.jsonObject
        folder = (about["folder"] as? JsonPrimitive)?.contentOrNull
        layers = about["layers"]?.jsonArray.orEmpty().mapNotNull { l ->
            val name = (l.jsonObject["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            when (val info = graph.invoke(Workspace.LAYER_INFO, buildJsonObject { put("layer", name) })) {
                is Outcome.Done -> {
                    val described = info.output.jsonObject
                    LayerInfo(name, described["count"]?.jsonPrimitive?.intOrNull ?: 0, formFields(described))
                }
                else -> {
                    failure = texts.of(info)
                    null
                }
            }
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
                title = { Text(stringResource(R.string.project_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        // List items take the screen's own colour, so the page has no bands.
        val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader(stringResource(R.string.project_section)) }
            item {
                ListItem(
                    headlineContent = { Text(project) },
                    overlineContent = { Text(stringResource(R.string.project_name)) },
                    leadingContent = { Icon(Icons.Rounded.Map, null) },
                    colors = clear,
                )
            }
            folder?.let { path ->
                item {
                    ListItem(
                        headlineContent = { Text(path) },
                        overlineContent = { Text(stringResource(R.string.project_folder)) },
                        leadingContent = { Icon(Icons.Rounded.Folder, null) },
                        colors = clear,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.project_fields_section)) }
            for (layer in layers) {
                item(key = "layer:${layer.name}") {
                    ListItem(
                        headlineContent = { Text(layer.name, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(pluralStringResource(R.plurals.points_count, layer.count, layer.count)) },
                        leadingContent = { Icon(Icons.Rounded.Place, null, tint = Color(0xFFE53935)) },
                        colors = clear,
                    )
                }
                items(layer.fields, key = { "field:${layer.name}:${it.name}" }) { field ->
                    ListItem(
                        headlineContent = { Text(field.name) },
                        supportingContent = { Text(typeName(field.type)) },
                        leadingContent = { Icon(fieldIcon(field.type), null) },
                        colors = clear,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                item(key = "add:${layer.name}") {
                    Column(Modifier.padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                        Text(
                            stringResource(R.string.fields_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { adding = layer }) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.field_add))
                        }
                    }
                }
            }
        }
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
