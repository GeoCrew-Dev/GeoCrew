package org.geocrew.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.geocrew.core.project.ProjectFormat

/** A layer of the open project as the map knows it: its name, id, colour on the map and how many points it has. */
data class MapLayer(val name: String, val id: String, val color: Color, val count: Int)

/** The project's point layers, from the layers project.current lists, each in its colour. */
fun pointLayers(layers: List<JsonObject>, counts: (String) -> Int): List<MapLayer> = layers.mapIndexedNotNull { index, l ->
    val name = l.text("name") ?: return@mapIndexedNotNull null
    if (l.text("geometry") != "POINT") return@mapIndexedNotNull null
    MapLayer(name, l.text("id").orEmpty(), layerColor(index), counts(name))
}

/** A layer's colour on the map, by its place in the project, until layers have styles of their own (ADR 0006). */
fun layerColor(index: Int): Color = LAYER_COLORS[index % LAYER_COLORS.size]

/** [layerColor] as MapLibre takes it. */
fun layerColorHex(index: Int): String = "#%06x".format(layerColor(index).toArgb() and 0xffffff)

private val LAYER_COLORS = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFFD81B60),
)

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * Which layer a new point goes into: the project's point layers, a new layer,
 * and "remember", after which points go into the chosen layer without asking
 * until the project settings say otherwise or the layer is gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerPicker(
    title: String,
    layers: List<MapLayer>,
    onPick: (MapLayer, remember: Boolean) -> Unit,
    onNew: (remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
    showRemember: Boolean = true,
) {
    var remember by remember { mutableStateOf(false) }
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            for (layer in layers) {
                ListItem(
                    headlineContent = { Text(layer.name) },
                    supportingContent = { Text(pluralStringResource(R.plurals.points_count, layer.count, layer.count)) },
                    leadingContent = { LayerDot(layer.color) },
                    colors = clear,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(layer, remember) },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.layer_new)) },
                leadingContent = { Icon(Icons.Rounded.Add, null) },
                colors = clear,
                modifier = Modifier.fillMaxWidth().clickable { onNew(remember) },
            )
            if (showRemember) {
                Row(
                    Modifier.fillMaxWidth().clickable { remember = !remember }.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text(stringResource(R.string.pick_layer_remember), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A layer's colour, as its points show on the map. */
@Composable
fun LayerDot(color: Color) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
    }
}

/**
 * A new point layer: its name, [suggested] to start with (野外点, numbered when
 * taken). It gets a name and a description field and CGCS2000 coordinates.
 */
@Composable
fun NewLayerDialog(existing: List<String>, suggested: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val start = remember(existing, suggested) { generateSequence(1) { it + 1 }.map { if (it == 1) suggested else "$suggested $it" }.first { n -> existing.none { it.equals(n, ignoreCase = true) } } }
    var name by remember { mutableStateOf(start) }
    val trimmed = name.trim()
    val taken = existing.any { it.equals(trimmed, ignoreCase = true) }
    // Names of GeoPackage's, SQLite's and GeoCrew's own tables.
    val reserved = listOf("gpkg_", "rtree_", "sqlite_", "geocrew_").any { trimmed.startsWith(it, ignoreCase = true) }
    val valid = trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME && !taken && !reserved
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AddLocationAlt, null) },
        title = { Text(stringResource(R.string.layer_new)) },
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
        confirmButton = { TextButton(onClick = { onCreate(trimmed) }, enabled = valid) { Text(stringResource(R.string.project_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
