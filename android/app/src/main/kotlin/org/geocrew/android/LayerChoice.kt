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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.geocrew.core.project.ProjectFormat

/** A layer of the open project as the map knows it: its name, id, colour on the map, how many points it has and whether it shows. */
data class MapLayer(val name: String, val id: String, val color: Color, val count: Int, val visible: Boolean = true)

/** The project's point layers, from the layers project.current lists, each in its colour. */
fun pointLayers(layers: List<JsonObject>, counts: (String) -> Int): List<MapLayer> = layers.mapNotNull { l ->
    val name = l.text("name") ?: return@mapNotNull null
    if (l.text("geometry") != "POINT") return@mapNotNull null
    MapLayer(name, l.text("id").orEmpty(), layerColor(madeIndex(l)), counts(name), l.text("visible") != "false")
}

/** When a layer listed by project.current was made among the layers: its colour stays when the list is reordered. */
fun madeIndex(layer: JsonObject): Int = layer.text("made")?.toIntOrNull() ?: 0

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
 * taken), and its CRS. With [describe], which names a CRS or says null for one
 * GDAL does not know, the CRS can be chosen: CGCS2000 longitude and latitude,
 * CGCS2000 Gauss-Kruger in 3 or 6 degree zones (the zone where the map is,
 * [centerLongitude]), WGS84, or any EPSG code. Without it the layer gets the
 * template's CRS.
 */
@Composable
fun NewLayerDialog(
    existing: List<String>,
    suggested: String,
    onDismiss: () -> Unit,
    onCreate: (name: String, srs: String?) -> Unit,
    centerLongitude: Double? = null,
    describe: (suspend (String) -> String?)? = null,
) {
    val start = remember(existing, suggested) { generateSequence(1) { it + 1 }.map { if (it == 1) suggested else "$suggested $it" }.first { n -> existing.none { it.equals(n, ignoreCase = true) } } }
    var name by remember { mutableStateOf(start) }
    var kind by remember { mutableStateOf(CrsKind.CGCS2000) }
    var zonePrefix by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf<Pair<String, String?>?>(null) } // an EPSG code and its name, null when unknown
    val trimmed = name.trim()
    val taken = existing.any { it.equals(trimmed, ignoreCase = true) }
    // Names of GeoPackage's, SQLite's and GeoCrew's own tables.
    val reserved = listOf("gpkg_", "rtree_", "sqlite_", "geocrew_").any { trimmed.startsWith(it, ignoreCase = true) }
    val gk3 = centerLongitude?.let { gaussKruger3(it, zonePrefix) }
    val gk6 = centerLongitude?.let { gaussKruger6(it, zonePrefix) }
    if (describe != null) {
        LaunchedEffect(code) {
            val typed = code.trim()
            checked = if (typed.isEmpty()) null else typed to describe("EPSG:$typed")
        }
    }
    val srs = when (kind) {
        CrsKind.CGCS2000 -> "EPSG:4490"
        CrsKind.GK3 -> gk3?.let { "EPSG:${it.first}" }
        CrsKind.GK6 -> gk6?.let { "EPSG:${it.first}" }
        CrsKind.WGS84 -> "EPSG:4326"
        CrsKind.EPSG -> checked?.takeIf { it.first == code.trim() && it.second != null }?.let { "EPSG:${it.first}" }
    }
    val valid = trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME && !taken && !reserved && (describe == null || srs != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AddLocationAlt, null) },
        title = { Text(stringResource(R.string.layer_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                if (describe != null) {
                    Text(stringResource(R.string.layer_crs), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                    CrsChoice(kind == CrsKind.CGCS2000, stringResource(R.string.crs_cgcs2000)) { kind = CrsKind.CGCS2000 }
                    gk3?.let { CrsChoice(kind == CrsKind.GK3, stringResource(R.string.crs_gk3, it.second)) { kind = CrsKind.GK3 } }
                    gk6?.let { CrsChoice(kind == CrsKind.GK6, stringResource(R.string.crs_gk6, it.second)) { kind = CrsKind.GK6 } }
                    if (kind == CrsKind.GK3 || kind == CrsKind.GK6) {
                        Row(
                            Modifier.fillMaxWidth().clickable { zonePrefix = !zonePrefix }.padding(start = 36.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = zonePrefix, onCheckedChange = { zonePrefix = it })
                            Text(stringResource(R.string.crs_zone_prefix), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    CrsChoice(kind == CrsKind.WGS84, stringResource(R.string.crs_wgs84)) { kind = CrsKind.WGS84 }
                    CrsChoice(kind == CrsKind.EPSG, stringResource(R.string.crs_epsg)) { kind = CrsKind.EPSG }
                    if (kind == CrsKind.EPSG) {
                        val known = checked?.takeIf { it.first == code.trim() }
                        OutlinedTextField(
                            value = code,
                            onValueChange = { typed -> code = typed.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.crs_epsg)) },
                            singleLine = true,
                            isError = known != null && known.second == null,
                            supportingText = {
                                when {
                                    known == null -> Unit
                                    known.second == null -> Text(stringResource(R.string.crs_unknown))
                                    else -> Text(known.second.orEmpty())
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.padding(start = 36.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(trimmed, if (describe == null) null else srs) }, enabled = valid) { Text(stringResource(R.string.project_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private enum class CrsKind { CGCS2000, GK3, GK6, WGS84, EPSG }

@Composable
private fun CrsChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The CGCS2000 3 degree Gauss-Kruger zone for [longitude]: its EPSG code, with
 * the zone number in front of the easting or not, and its central meridian;
 * null outside China's zones 25 to 45.
 */
fun gaussKruger3(longitude: Double, zonePrefix: Boolean): Pair<Int, Int>? {
    val zone = Math.round(longitude / 3).toInt()
    if (zone !in 25..45) return null
    return (if (zonePrefix) 4513 else 4534) + zone - 25 to zone * 3
}

/** The CGCS2000 6 degree zone for [longitude], as [gaussKruger3]; null outside zones 13 to 23. */
fun gaussKruger6(longitude: Double, zonePrefix: Boolean): Pair<Int, Int>? {
    val zone = floor(longitude / 6).toInt() + 1
    if (zone !in 13..23) return null
    return (if (zonePrefix) 4491 else 4502) + zone - 13 to zone * 6 - 3
}
