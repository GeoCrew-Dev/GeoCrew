package org.geocrew.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.geocrew.core.action.Outcome
import org.geocrew.storage.Workspace

/** A CRS as the picker lists it: how GDAL names it, its name, PROJ's type for it, and where it applies. */
data class CrsItem(val srs: String, val name: String, val type: String?, val area: String?)

/** The CRSs in an output of crs.search. */
fun listedCrs(output: JsonObject): List<CrsItem> = output["crs"]?.jsonArray.orEmpty().mapNotNull { c ->
    val o = c.jsonObject
    fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
    CrsItem(text("srs") ?: return@mapNotNull null, text("name").orEmpty(), text("type"), text("area"))
}

/**
 * The CRSs field work in China uses most, with those for the place [near]
 * (longitude, latitude): CGCS2000, its Gauss-Kruger zones there (with and
 * without the zone number in front), WGS 84 and its UTM zone, Web Mercator,
 * Xian 1980 and Beijing 1954.
 */
fun commonCrs(near: Pair<Double, Double>?): List<String> = buildList {
    add("EPSG:4490")
    if (near != null) {
        val (lon, lat) = near
        for (prefix in listOf(false, true)) gaussKruger3(lon, prefix)?.let { add("EPSG:${it.first}") }
        for (prefix in listOf(false, true)) gaussKruger6(lon, prefix)?.let { add("EPSG:${it.first}") }
        add("EPSG:4326")
        val zone = (floor((lon + 180) / 6).toInt() + 1).coerceIn(1, 60)
        add("EPSG:${(if (lat >= 0) 32600 else 32700) + zone}")
    } else {
        add("EPSG:4326")
    }
    add("EPSG:3857")
    add("EPSG:4610")
    add("EPSG:4214")
}

/** The CRSs named by [srs] (AUTH:CODE), in that order, with their names; through crs.search. */
suspend fun lookUpCrs(graph: AppGraph, srs: List<String>): List<CrsItem> {
    if (srs.isEmpty()) return emptyList()
    val found = graph.invoke(Workspace.CRS_SEARCH, buildJsonObject { putJsonArray("srs") { srs.forEach { add(it) } } })
    return (found as? Outcome.Done)?.output?.jsonObject?.let(::listedCrs).orEmpty()
}

/** A CRS's name: from PROJ's database for AUTH:CODE, else as GDAL reads the definition (WKT, PROJ string); null when unknown. */
suspend fun crsName(graph: AppGraph, srs: String): String? =
    lookUpCrs(graph, listOf(srs)).firstOrNull()?.name
        ?: ((graph.invoke(Workspace.CRS_DESCRIBE, buildJsonObject { put("srs", srs) }) as? Outcome.Done)?.output?.jsonObject?.get("name") as? JsonPrimitive)?.contentOrNull

/**
 * A CRS, shown like a drop-down field: its name, with its code under it. A
 * tap opens [CrsPickerDialog]; what is picked goes to [onPick] and to the
 * recently used. [near] is where the map is, for the zones that apply there.
 */
@Composable
fun CrsField(
    graph: AppGraph,
    srs: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.layer_crs),
    near: Pair<Double, Double>? = null,
) {
    var picking by remember { mutableStateOf(false) }
    var name by remember(srs) { mutableStateOf<String?>(null) }
    LaunchedEffect(srs) { name = crsName(graph, srs) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name ?: srs,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            supportingText = if (name != null) ({ Text(srs) }) else null,
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        // The whole field opens the picker; the text field itself takes no typing.
        val description = "$label: ${name ?: srs}"
        Box(Modifier.matchParentSize().semantics { role = Role.DropdownList; contentDescription = description }.clickable { picking = true })
    }
    if (picking) {
        CrsPickerDialog(
            graph = graph,
            current = srs,
            near = near,
            onPick = {
                picking = false
                graph.settings.usedCrs(it)
                onPick(it)
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Every horizontal CRS PROJ knows, to pick one from, as QGIS's CRS selector:
 * search by name or code (Chinese words for China's CRSs too), geographic or
 * projected, or only those that apply at [near]. With nothing typed it shows
 * the recently used and the common ones. A PROJ string or WKT typed in can be
 * picked as it is, once GDAL reads it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrsPickerDialog(graph: AppGraph, current: String?, near: Pair<Double, Double>?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var geographic by remember { mutableStateOf(true) }
    var projected by remember { mutableStateOf(true) }
    var here by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CrsItem>?>(null) }
    var recent by remember { mutableStateOf<List<CrsItem>>(emptyList()) }
    var common by remember { mutableStateOf<List<CrsItem>>(emptyList()) }
    var custom by remember { mutableStateOf<CrsItem?>(null) }

    LaunchedEffect(Unit) {
        val used = graph.settings.recentCrs
        val usual = commonCrs(near).filterNot { it in used }
        val found = lookUpCrs(graph, used + usual).associateBy { it.srs }
        recent = used.mapNotNull(found::get)
        common = usual.mapNotNull(found::get)
    }
    LaunchedEffect(text, geographic, projected, here) {
        if (text.isBlank() && !here) {
            results = null
            custom = null
            return@LaunchedEffect
        }
        delay(200) // typing on: search once it pauses
        val kinds = listOfNotNull("geographic".takeIf { geographic }, "projected".takeIf { projected })
        val params = buildJsonObject {
            put("text", text)
            putJsonArray("kinds") { kinds.forEach { add(it) } }
            if (here && near != null) putJsonArray("near") { add(near.first); add(near.second) }
        }
        results = (graph.invoke(Workspace.CRS_SEARCH, params) as? Outcome.Done)?.output?.jsonObject?.let(::listedCrs).orEmpty()
        // A definition typed in, such as +proj=tmerc +lon_0=114.5 ... for a local system.
        val typed = text.trim()
        custom = if (typed.startsWith("+proj") || '[' in typed) crsName(graph, typed)?.let { CrsItem(typed, it, null, null) } else null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Status bar icons that show on the dialog's own surface.
        val view = LocalView.current
        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = lightSurface }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.layer_crs)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.crs_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (text.isNotEmpty()) ({ IconButton(onClick = { text = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.measure_clear)) } }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // One of the two kinds stays chosen.
                    FilterChip(selected = geographic, onClick = { if (projected || !geographic) geographic = !geographic }, label = { Text(stringResource(R.string.crs_geographic)) })
                    FilterChip(selected = projected, onClick = { if (geographic || !projected) projected = !projected }, label = { Text(stringResource(R.string.crs_projected)) })
                    if (near != null) FilterChip(selected = here, onClick = { here = !here }, label = { Text(stringResource(R.string.crs_here)) })
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    custom?.let { c -> item(key = "custom") { CrsRow(c, c.srs == current, custom = true) { onPick(c.srs) } } }
                    val found = results
                    if (found == null) {
                        if (recent.isNotEmpty()) {
                            item(key = "recent") { Section(stringResource(R.string.crs_recent)) }
                            items(recent, key = { "recent:" + it.srs }) { c -> CrsRow(c, c.srs == current) { onPick(c.srs) } }
                        }
                        if (common.isNotEmpty()) {
                            item(key = "common") { Section(stringResource(R.string.crs_common)) }
                            items(common, key = { "common:" + it.srs }) { c -> CrsRow(c, c.srs == current) { onPick(c.srs) } }
                        }
                    } else if (found.isEmpty() && custom == null) {
                        item(key = "none") {
                            Text(
                                stringResource(R.string.crs_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        items(found, key = { it.srs }) { c -> CrsRow(c, c.srs == current) { onPick(c.srs) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun CrsRow(crs: CrsItem, selected: Boolean, custom: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(crs.name) },
        supportingContent = {
            val under = if (custom) stringResource(R.string.crs_custom) else listOfNotNull(crs.srs, crs.area).joinToString(" · ")
            Text(under, maxLines = 2)
        },
        leadingContent = {
            val icon = when {
                custom -> Icons.Rounded.Tune
                crs.type == "projected" -> Icons.Rounded.GridOn
                else -> Icons.Rounded.Public
            }
            Icon(icon, null)
        },
        trailingContent = if (selected) ({ Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
