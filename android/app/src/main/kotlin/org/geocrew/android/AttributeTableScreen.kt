package org.geocrew.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.storage.Workspace

/**
 * A feature as a row of the table: its fid; its values as shown ([cells]) and as
 * a form keeps them ([values]), one per column; and the moments its dates and
 * times say (null elsewhere).
 */
private data class TableRow(val fid: Long, val cells: List<String>, val values: List<String>, val moments: List<Instant?>)

// The fid column's header, the name GeoPackage gives it.
private const val FID = "fid"

/**
 * Whether a row's [cells] hold [query] (trimmed): in any column, whatever the
 * case, full-width letters and digits as their plain forms. A blank query holds every row.
 */
internal fun matches(cells: List<String>, query: String): Boolean {
    val q = folded(query.trim())
    return q.isEmpty() || cells.any { folded(it).contains(q) }
}

// Text as compared in a search: full-width forms as plain ones (NFKC), lower case.
private fun folded(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()

/** A column sorted by, ascending or not. */
private data class Sorting(val column: Int, val ascending: Boolean)

/**
 * A layer's features as a table, one column per field, as edited (layer.features,
 * ADR 0010). A tap on a header sorts by it; a tap on a row shows the feature on the map.
 * The search in the top bar keeps the rows that hold what is typed. With values
 * being changed, a tap on a cell changes its value as the feature's form does
 * (feature.values, ADR 0005), and the project's undo and redo are at hand.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AttributeTableScreen(graph: AppGraph, project: String, layer: String, onBack: () -> Unit, onFeature: (Long) -> Unit) {
    var fields by remember { mutableStateOf<List<FormField>>(emptyList()) }
    var rows by remember { mutableStateOf<List<TableRow>?>(null) }
    var sorting by remember { mutableStateOf<Sorting?>(null) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    var failure by remember { mutableStateOf<String?>(null) }
    var loads by remember { mutableIntStateOf(0) }
    // While values are being changed, a tap on a cell changes its value instead of showing the feature.
    var changing by rememberSaveable { mutableStateOf(false) }
    // The cell whose value is being changed: its row's fid and its column.
    var changed by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var layerEditing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false to false) }
    // A change that failed, said over the table.
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)
    val texts = FailureTexts(
        unsaved = stringResource(R.string.failure_unsaved),
        failed = stringResource(R.string.action_failed),
        start = stringResource(R.string.start_failed),
    )

    LaunchedEffect(project, layer, loads) {
        val opened = graph.open(project)
        val listed = if (opened is Outcome.Done) graph.invoke(Workspace.FEATURES, buildJsonObject { put("layer", layer) }) else opened
        if (listed !is Outcome.Done) {
            failure = texts.of(listed)
            rows = emptyList()
            return@LaunchedEffect
        }
        val collection = listed.output.jsonObject
        layerEditing = (collection["editing"] as? JsonPrimitive)?.booleanOrNull == true
        history = graph.registry.canUndo(Workspace.PROJECT_SCOPE) to graph.registry.canRedo(Workspace.PROJECT_SCOPE)
        val read = formFields(collection)
        // The fid first, as QGIS shows it: a layer without fields still lists its features.
        fields = listOf(FormField(FID, "integer64")) + read
        rows = collection["features"]?.jsonArray.orEmpty().mapNotNull { f ->
            val feature = f.jsonObject
            val fid = (feature["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val properties = feature["properties"] as? JsonObject
            val raw = read.map { fieldText(properties?.get(it.name)) }
            TableRow(
                fid,
                listOf(fid.toString()) + read.mapIndexed { i, f -> cell(raw[i], f.type, yes, no, locale) },
                listOf(fid.toString()) + raw,
                listOf<Instant?>(null) + read.mapIndexed { i, f -> if (f.type == "datetime") instantOf(raw[i]) else null },
            )
        }
    }

    // Runs a change; a failure is said over the table.
    suspend fun act(action: String, params: JsonObject): Boolean {
        val outcome = graph.invoke(action, params)
        if (outcome !is Outcome.Done) problem = texts.of(outcome)
        return outcome is Outcome.Done
    }

    // A value changes as in the feature's form: the layer starts being edited first, the QGIS way. Then the table reads it again.
    fun change(fid: Long, field: FormField, text: String) = scope.launch {
        // Noted at once, so a second change before the table is read again does not start it twice.
        if (!layerEditing) layerEditing = act(Editing.START, buildJsonObject { put("layer", layer) })
        if (layerEditing) {
            val values = buildJsonObject { put(field.name, fieldValue(field.type, text)) }
            act(Editing.VALUES, buildJsonObject { put("layer", layer); put("fid", fid); put("values", values) })
        }
        loads++
    }

    // The project's undo or redo, as on the map.
    fun step(action: String) = scope.launch {
        act(action, buildJsonObject { put("scope", Workspace.PROJECT_SCOPE) })
        loads++
    }

    BackHandler(enabled = query != null) { query = null }
    BackHandler(enabled = changing && query == null) { changing = false }
    val shown = remember(rows, sorting, query) { sorted(rows.orEmpty(), sorting).filter { matches(it.cells, query.orEmpty()) } }
    val widths = remember(fields, rows) { fields.mapIndexed { i, f -> columnWidth(listOf(f.name) + rows.orEmpty().map { it.cells[i] }) } }

    Scaffold(
        topBar = {
            if (query != null) {
                TopAppBar(
                    title = { SearchField(query.orEmpty(), onChange = { query = it }, hint = stringResource(R.string.table_search_hint)) },
                    navigationIcon = { IconButton(onClick = { query = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                    actions = {
                        if (!query.isNullOrEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.files_clear)) }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(layer, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                    actions = {
                        if (changing) {
                            IconButton(onClick = { step(ActionRegistry.UNDO) }, enabled = history.first) {
                                Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.map_undo))
                            }
                            IconButton(onClick = { step(ActionRegistry.REDO) }, enabled = history.second) {
                                Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.map_redo))
                            }
                        }
                        if (!rows.isNullOrEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Search, stringResource(R.string.files_search)) }
                            IconButton(onClick = { changing = !changing }) {
                                if (changing) {
                                    Icon(Icons.Rounded.Check, stringResource(R.string.table_change_done))
                                } else {
                                    Icon(Icons.Rounded.Edit, stringResource(R.string.table_change_values))
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // A table narrower than the screen gives its last column the rest.
            val spare = maxWidth - widths.fold(0.dp) { a, b -> a + b }
            val widths = if (spare > 0.dp && widths.isNotEmpty()) widths.dropLast(1) + (widths.last() + spare) else widths
            when {
                failure != null -> Message(failure!!)
                rows == null -> Unit
                rows!!.isEmpty() -> Message(stringResource(R.string.no_features))
                shown.isEmpty() -> Message(stringResource(R.string.table_no_matches))
                else -> Column(Modifier.fillMaxHeight().horizontalScroll(rememberScrollState())) {
                    LazyColumn(Modifier.width(widths.fold(0.dp) { a, b -> a + b })) {
                        stickyHeader {
                            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                fields.forEachIndexed { i, f ->
                                    Row(
                                        Modifier.width(widths[i]).clickable {
                                            sorting = when {
                                                sorting?.column != i -> Sorting(i, true)
                                                sorting!!.ascending -> Sorting(i, false)
                                                else -> null
                                            }
                                        }.padding(horizontal = 12.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            f.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        sorting?.takeIf { it.column == i }?.let {
                                            Icon(
                                                if (it.ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                                null,
                                                modifier = Modifier.padding(start = 4.dp).size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(shown, key = { it.fid }) { row ->
                            Row(if (changing) Modifier else Modifier.clickable { onFeature(row.fid) }) {
                                row.cells.forEachIndexed { i, value ->
                                    // Not the fid, which is GeoPackage's, nor values a form does not set either.
                                    val tappable = changing && i > 0 && settable(fields[i].type)
                                    Text(
                                        value,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(widths[i])
                                            .then(if (tappable) Modifier.clickable { changed = row.fid to i } else Modifier)
                                            .padding(horizontal = 12.dp, vertical = 14.dp),
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
    changed?.let { (fid, i) ->
        val field = fields.getOrNull(i)
        val row = rows?.firstOrNull { it.fid == fid }
        if (field != null && row != null) {
            ValueDialog(field, row.values[i], onDismiss = { changed = null }) { text ->
                changed = null
                change(fid, field, text)
            }
        }
    }
    problem?.let { message ->
        AppDialog(
            onDismissRequest = { problem = null },
            confirmButton = { TextButton(onClick = { problem = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(message) },
        )
    }
}

/** Changes one value, [field]'s, from [initial] as a form keeps it; it saves a change with nothing wrong in it. */
@Composable
private fun ValueDialog(field: FormField, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(field, initial) { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        title = { Text(stringResource(R.string.table_change_value)) },
        text = { FieldInput(field, text, onChange = { text = it }, onPick = { picking = true }) },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text != initial && valueProblem(field.type, text) == null) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (picking) {
        FieldPicker(field, text, onDismiss = { picking = false }) {
            text = it
            picking = false
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// A value as the table shows it: empty for none, yes or no for a boolean, a date and time where the phone is.
private fun cell(text: String, type: String, yes: String, no: String, locale: Locale): String {
    if (text.isEmpty()) return ""
    return when (type) {
        "boolean" -> if (text == "true" || text == "1") yes else no
        else -> shownDateTime(type, text, locale)
    }
}

// Dates and times sort by their moments, numbers as numbers, the rest as text; empty cells go last either way.
private fun sorted(rows: List<TableRow>, sorting: Sorting?): List<TableRow> {
    sorting ?: return rows
    val i = sorting.column
    val (empty, filled) = rows.partition { it.cells[i].isEmpty() }
    val numeric = filled.all { it.cells[i].toDoubleOrNull() != null }
    val timed = filled.all { it.moments[i] != null }
    val collator = Collator.getInstance()
    val ordered = when {
        timed -> filled.sortedBy { it.moments[i] }
        numeric -> filled.sortedBy { it.cells[i].toDouble() }
        else -> filled.sortedWith { a, b -> collator.compare(a.cells[i], b.cells[i]) }
    }
    return (if (sorting.ascending) ordered else ordered.reversed()) + empty
}

// Wide enough for a column's longest text and the header's sort arrow, within limits; wide characters such as Chinese count double.
private fun columnWidth(texts: List<String>): Dp {
    val longest = texts.maxOfOrNull { t -> t.sumOf { c -> if (c.code > 0x2E80) 2 else 1 } } ?: 0
    return (longest * 8 + 52).coerceIn(88, 260).dp
}
