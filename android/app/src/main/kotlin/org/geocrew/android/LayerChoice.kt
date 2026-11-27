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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.geocrew.core.project.LayerColors
import org.geocrew.core.project.ProjectFormat
import org.geocrew.storage.Crs

/** A layer of the open project as the map knows it: its name, id, colour on the map, how many features it has, whether it shows, and its geometry type. */
data class MapLayer(val name: String, val id: String, val color: Color, val count: Int, val visible: Boolean = true, val geometry: String? = "POINT")

/** The project's point layers, from the layers project.current lists, each in its colour. */
fun pointLayers(layers: List<JsonObject>, counts: (String) -> Int): List<MapLayer> = layers.mapNotNull { l ->
    val name = l.text("name") ?: return@mapNotNull null
    if (l.text("geometry") != "POINT") return@mapNotNull null
    MapLayer(name, l.text("id").orEmpty(), styleOf(l, layerColor(madeIndex(l))).color, counts(name), l.text("visible") != "false")
}

/** The project's layers of any geometry type (points, lines, areas), for adding a feature to one. */
fun drawLayers(layers: List<JsonObject>, counts: (String) -> Int): List<MapLayer> = layers.mapNotNull { l ->
    val name = l.text("name") ?: return@mapNotNull null
    val geometry = l.text("geometry") ?: return@mapNotNull null
    MapLayer(name, l.text("id").orEmpty(), styleOf(l, layerColor(madeIndex(l))).color, counts(name), l.text("visible") != "false", geometry)
}

/** When a layer listed by project.current was made among the layers: its colour stays when the list is reordered. */
fun madeIndex(layer: JsonObject): Int = layer.text("made")?.toIntOrNull() ?: 0

/** A layer's colour on the map, by its place in the project, until layers have styles of their own (ADR 0006). */
fun layerColor(index: Int): Color = LAYER_COLORS[index % LAYER_COLORS.size]

/** [layerColor] as MapLibre takes it. */
fun layerColorHex(index: Int): String = "#%06x".format(layerColor(index).toArgb() and 0xffffff)

// The same colours exports take (core:project), as Compose takes them.
private val LAYER_COLORS = LayerColors.HEX.map { Color(0xFF000000 or it.removePrefix("#").toLong(16)) }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * Which layer a new feature goes into: the layers, a new layer, and
 * "remember", after which features go into the chosen layer without asking
 * until the project settings say otherwise or the layer is gone. With [kind]
 * the feature's kind comes first (ADR 0017): point, line or area, chosen at
 * the top ([onKind]), and only the layers that take it are listed.
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
    kind: LayerKind? = null,
    onKind: (LayerKind) -> Unit = {},
    entries: List<AddEntry>? = null,
    onPickEntry: (AddEntry, remember: Boolean) -> Unit = { _, _ -> },
) {
    var remember by remember { mutableStateOf(false) }
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (kind != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LayerKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(selected = kind == k, onClick = { onKind(k) }, shape = SegmentedButtonDefaults.itemShape(i, LayerKind.entries.size)) {
                            Text(stringResource(k.label))
                        }
                    }
                }
            }
            if (kind != null && entries != null) {
                // Every vector layer takes the kind chosen (ADR 0017); one without a part for it gets one when picked.
                for (entry in entries) {
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text(entry.count?.let { pluralStringResource(countOf(kind.geometry), it, it) } ?: stringResource(kind.newLayer))
                        },
                        leadingContent = { LegendSwatch(kind.geometry, listOf(entry.color), visible = true) },
                        colors = clear,
                        modifier = Modifier.fillMaxWidth().clickable { onPickEntry(entry, remember) },
                    )
                }
            } else {
                for (layer in if (kind == null) layers else layers.filter { kind.takenBy(it.geometry) }) {
                    ListItem(
                        headlineContent = { Text(layer.name) },
                        supportingContent = { Text(pluralStringResource(countOf(layer.geometry), layer.count, layer.count)) },
                        leadingContent = { LayerDot(layer.color) },
                        colors = clear,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(layer, remember) },
                    )
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.layer_new)) },
                leadingContent = { Icon(Icons.Rounded.Add, null) },
                colors = clear,
                modifier = Modifier.fillMaxWidth().clickable { onNew(remember) },
            )
            if (showRemember) {
                // A row like the others, its box where their dots are.
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pick_layer_remember), style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            Checkbox(checked = remember, onCheckedChange = null)
                        }
                    },
                    colors = clear,
                    modifier = Modifier.fillMaxWidth().toggleable(value = remember, role = Role.Checkbox) { remember = it },
                )
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
 * taken), and, with [graph], its CRS: CGCS2000 longitude and latitude to start
 * with, or any CRS PROJ knows, picked in [CrsField] ([near] is where the map is,
 * for the zones that apply there). Without [graph] the layer gets the
 * template's CRS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewLayerDialog(
    existing: List<String>,
    suggested: String,
    onDismiss: () -> Unit,
    onCreate: (name: String, srs: String?, template: String?) -> Unit,
    graph: AppGraph? = null,
    near: Pair<Double, Double>? = null,
    kinds: Boolean = false,
    startKind: LayerKind = LayerKind.POINT,
) {
    // The first name not taken: 野外点, 野外点 2, ...
    fun free(base: String) = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { n -> existing.none { it.equals(n, ignoreCase = true) } }
    val suggestions = mapOf(
        LayerKind.POINT to suggested,
        LayerKind.LINE to stringResource(R.string.layer_default_line),
        LayerKind.POLYGON to stringResource(R.string.layer_default_polygon),
    )
    var kind by remember { mutableStateOf(startKind) }
    var name by remember { mutableStateOf(free(suggestions.getValue(startKind))) }
    var srs by remember { mutableStateOf(Crs.LONGITUDE_LATITUDE) }
    val trimmed = name.trim()
    val taken = existing.any { it.equals(trimmed, ignoreCase = true) }
    // Names of GeoPackage's, SQLite's and GeoCrew's own tables.
    val reserved = listOf("gpkg_", "rtree_", "sqlite_", "geocrew_").any { trimmed.startsWith(it, ignoreCase = true) }
    val valid = trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME && !taken && !reserved
    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        icon = { Icon(Icons.Rounded.AddLocationAlt, null) },
        title = { Text(stringResource(R.string.layer_new)) },
        text = {
            Column {
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
                if (kinds) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        LayerKind.entries.forEachIndexed { i, k ->
                            SegmentedButton(
                                selected = kind == k,
                                onClick = {
                                    // A suggested name follows the kind; one the user typed stays.
                                    if (name == free(suggestions.getValue(kind))) name = free(suggestions.getValue(k))
                                    kind = k
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, LayerKind.entries.size),
                            ) { Text(stringResource(k.label)) }
                        }
                    }
                }
                if (graph != null) CrsField(graph, srs, onPick = { srs = it }, near = near)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(trimmed, if (graph == null) null else srs, if (kinds) kind.template else null) }, enabled = valid) {
                Text(stringResource(R.string.project_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * A vector layer a new feature can go into (ADR 0017): a layer, or a vector
 * layer's group as one; its name and colour, and how many features of the
 * kind it has, null when it has no part for them yet (made when picked).
 * [layer] is the layer the feature goes into, or the one a new part is made
 * like.
 */
data class AddEntry(val name: String, val color: Color, val count: Int?, val layer: String)

/**
 * What a feature or a new layer is: a point, a line or an area, the template
 * a new layer is made from, the geometry type it holds, and how a vector
 * layer's part for it yet to be made is said.
 */
enum class LayerKind(val template: String, val label: Int, val geometry: String, val newLayer: Int) {
    POINT("field-point", R.string.layer_kind_point, "POINT", R.string.add_part_point),
    LINE("field-line", R.string.layer_kind_line, "LINESTRING", R.string.add_part_line),
    POLYGON("field-polygon", R.string.layer_kind_polygon, "POLYGON", R.string.add_part_polygon),
    ;

    /** Whether a layer of [geometry] takes this kind: its own, or any kind for a layer of mixed geometry (GEOMETRY). */
    fun takenBy(geometry: String?): Boolean = of(geometry).let { it == null || it == this }

    companion object {
        /** The kind a layer of [geometry] holds (multi types too); null for mixed geometry, which takes any. */
        fun of(geometry: String?): LayerKind? = when {
            geometry == null -> null
            geometry.contains("POLYGON") -> POLYGON
            geometry.contains("LINE") -> LINE
            geometry.contains("POINT") -> POINT
            else -> null
        }
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
