package org.geocrew.android

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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.storage.Workspace

/** A feature as a row of the table: its fid and its values as shown, one per field. */
private data class TableRow(val fid: Long, val cells: List<String>)

/** A column sorted by, ascending or not. */
private data class Sorting(val column: Int, val ascending: Boolean)

/**
 * A layer's features as a table, one column per field, as edited (layer.features,
 * ADR 0010). A tap on a header sorts by it; a tap on a row shows the feature on the map.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AttributeTableScreen(graph: AppGraph, project: String, layer: String, onBack: () -> Unit, onFeature: (Long) -> Unit) {
    var fields by remember { mutableStateOf<List<FormField>>(emptyList()) }
    var rows by remember { mutableStateOf<List<TableRow>?>(null) }
    var sorting by remember { mutableStateOf<Sorting?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)
    val texts = FailureTexts(
        unsaved = stringResource(R.string.failure_unsaved),
        failed = stringResource(R.string.action_failed),
        start = stringResource(R.string.start_failed),
    )

    LaunchedEffect(project, layer) {
        val opened = graph.open(project)
        val listed = if (opened is Outcome.Done) graph.invoke(Workspace.FEATURES, buildJsonObject { put("layer", layer) }) else opened
        if (listed !is Outcome.Done) {
            failure = texts.of(listed)
            rows = emptyList()
            return@LaunchedEffect
        }
        val collection = listed.output.jsonObject
        val read = formFields(collection)
        fields = read
        rows = collection["features"]?.jsonArray.orEmpty().mapNotNull { f ->
            val feature = f.jsonObject
            val fid = (feature["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val properties = feature["properties"] as? JsonObject
            TableRow(fid, read.map { cell(properties?.get(it.name), it.type, yes, no) })
        }
    }

    val shown = remember(rows, sorting) { sorted(rows.orEmpty(), sorting) }
    val widths = remember(fields, rows) { fields.mapIndexed { i, f -> columnWidth(listOf(f.name) + rows.orEmpty().map { it.cells[i] }) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(layer, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // A table narrower than the screen gives its last column the rest.
            val spare = maxWidth - widths.fold(0.dp) { a, b -> a + b }
            val widths = if (spare > 0.dp && widths.isNotEmpty()) widths.dropLast(1) + (widths.last() + spare) else widths
            when {
                failure != null -> Message(failure!!)
                rows == null -> Unit
                fields.isEmpty() || shown.isEmpty() -> Message(stringResource(R.string.no_features))
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
                            Row(Modifier.clickable { onFeature(row.fid) }) {
                                row.cells.forEachIndexed { i, value ->
                                    Text(
                                        value,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(widths[i]).padding(horizontal = 12.dp, vertical = 14.dp),
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
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// A value as the table shows it: empty for none, yes or no for a boolean.
private fun cell(value: Any?, type: String, yes: String, no: String): String {
    val text = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull ?: return ""
    return if (type == "boolean") if (text == "true" || text == "1") yes else no else text
}

// Numbers sort as numbers, the rest as text; empty cells go last either way.
private fun sorted(rows: List<TableRow>, sorting: Sorting?): List<TableRow> {
    sorting ?: return rows
    val i = sorting.column
    val (empty, filled) = rows.partition { it.cells[i].isEmpty() }
    val numeric = filled.all { it.cells[i].toDoubleOrNull() != null }
    val ordered = if (numeric) filled.sortedBy { it.cells[i].toDouble() } else filled.sortedBy { it.cells[i] }
    return (if (sorting.ascending) ordered else ordered.reversed()) + empty
}

// Wide enough for a column's longest text and the header's sort arrow, within limits; wide characters such as Chinese count double.
private fun columnWidth(texts: List<String>): Dp {
    val longest = texts.maxOfOrNull { t -> t.sumOf { c -> if (c.code > 0x2E80) 2 else 1 } } ?: 0
    return (longest * 8 + 52).coerceIn(88, 260).dp
}
