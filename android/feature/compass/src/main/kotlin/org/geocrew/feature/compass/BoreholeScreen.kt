package org.geocrew.feature.compass

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.Borehole
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.borehole
import org.geocrew.core.ui.SectionHeader
import java.util.Locale

private fun m(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

/**
 * 钻孔编录: a hole (or pit) logged layer by layer by the depth of each
 * layer's bottom below the collar, with the water table, printed as a
 * borehole column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoreholeScreen(onBack: () -> Unit, viewModel: BoreholeViewModel = hiltViewModel()) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val holes by viewModel.holes.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    BackHandler(enabled = draft != null) { viewModel.close() }

    var exporting by remember { mutableStateOf<GeoFeature?>(null) }
    val createColumn = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = exporting
        if (uri != null && f != null) viewModel.exportColumn(f, uri)
        exporting = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft == null) "钻孔编录" else draft?.title?.ifBlank { "钻孔" } ?: "") },
                navigationIcon = {
                    IconButton(onClick = { if (draft != null) viewModel.close() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (draft != null) IconButton(onClick = viewModel::save) { Icon(Icons.Default.Save, "保存") }
                },
            )
        },
        floatingActionButton = {
            if (draft == null) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::startNew,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("新建钻孔") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = draft
        if (d == null) {
            HoleList(
                holes, Modifier.padding(padding),
                onOpen = viewModel::open,
                onDelete = viewModel::delete,
                onExport = { f -> exporting = f; createColumn.launch(viewModel.columnName(f)) },
            )
        } else {
            HoleEditor(d, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HoleList(items: List<GeoFeature>, modifier: Modifier, onOpen: (GeoFeature) -> Unit, onDelete: (String) -> Unit, onExport: (GeoFeature) -> Unit) {
    var confirm by remember { mutableStateOf<GeoFeature?>(null) }
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "还没有钻孔。从孔口往下，按每层的层底深度记岩性，再填终孔深度和水位，就能导出钻孔柱状图。浅井、探槽壁也可以这样编录。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(32.dp),
            )
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            items(items, key = { it.id }) { f ->
                val h = f.borehole
                ListItem(
                    headlineContent = { Text(f.title?.ifBlank { null } ?: "钻孔") },
                    supportingContent = {
                        Text(buildString {
                            append("孔深 ${m(h.total)} m · ${h.intervals().size} 层")
                            h.water?.let { append(" · 水位 ${m(it)} m") }
                            if (f.geometry == null) append(" · 没有孔口位置")
                        })
                    },
                    trailingContent = {
                        Row {
                            if (h.intervals().isNotEmpty()) IconButton(onClick = { onExport(f) }) { Icon(Icons.Default.PictureAsPdf, "导出钻孔柱状图") }
                            IconButton(onClick = { confirm = f }) { Icon(Icons.Default.Delete, "删除") }
                        }
                    },
                    modifier = Modifier.clickable { onOpen(f) },
                )
            }
        }
    }
    confirm?.let { f ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("删除这个钻孔？") },
            text = { Text("「${f.title ?: "钻孔"}」的分层都会删除，组员同步后也会看到删除。") },
            confirmButton = { TextButton(onClick = { onDelete(f.id); confirm = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun HoleEditor(d: BoreholeDraft, vm: BoreholeViewModel, modifier: Modifier) {
    val picks by vm.picks.collectAsStateWithLifecycle()
    val lithologies by vm.lithologies.collectAsStateWithLifecycle()
    var pickCollar by remember { mutableStateOf(false) }
    val hole = d.hole()
    val intervals = hole.intervals()

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = d.title,
                    onValueChange = { t -> vm.edit { it.copy(title = t) } },
                    label = { Text("孔号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    d.collar?.let { "孔口：${it.label}" } ?: "孔口：未设置（设置后显示在地图上）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = vm::collarHere, label = { Text("用当前位置") }, leadingIcon = { Icon(Icons.Default.MyLocation, null) })
                    AssistChip(onClick = { pickCollar = true }, label = { Text("用一条记录") }, leadingIcon = { Icon(Icons.Default.Place, null) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num(d.depth, { t -> vm.edit { it.copy(depth = t) } }, "终孔深度 m", Modifier.weight(1f))
                    Num(d.water, { t -> vm.edit { it.copy(water = t) } }, "水位埋深 m", Modifier.weight(1f))
                }
                Text(
                    "深度都从孔口往下量；水位没见到就留空。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionHeader("分层（自上而下）", Modifier.padding(horizontal = 16.dp)) }
        itemsIndexed(d.layers) { i, layer ->
            val parsed = layer.parse()
            val iv = parsed?.let { p -> intervals.firstOrNull { it.layer == p } }
            BoreLayerCard(
                layer = layer,
                interval = iv,
                skipped = parsed != null && iv == null,
                lithologies = lithologies,
                onChange = { l -> vm.edit { it.copy(layers = it.layers.with(i, l)) } },
                onRemove = { vm.edit { it.copy(layers = it.layers.without(i).ifEmpty { listOf(BoreLayerInput("1")) }) } },
            )
        }
        item {
            TextButton(
                onClick = { vm.edit { it.copy(layers = it.layers + BoreLayerInput(it.nextNo())) } },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("添加分层") }
        }

        item { SectionHeader("结果", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        item { HoleSummary(hole) }
    }

    if (pickCollar) {
        PickList(
            title = "以哪条记录的位置为孔口",
            items = picks,
            detail = { "有位置" },
            onDismiss = { pickCollar = false },
            onPick = { vm.collarFromPick(it); pickCollar = false },
        )
    }
}

@Composable
private fun BoreLayerCard(
    layer: BoreLayerInput,
    interval: Borehole.Interval?,
    skipped: Boolean,
    lithologies: List<String>,
    onChange: (BoreLayerInput) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = layer.no, onValueChange = { onChange(layer.copy(no = it)) },
                    label = { Text("层号") }, singleLine = true, modifier = Modifier.width(84.dp),
                )
                Num(layer.bottom, { onChange(layer.copy(bottom = it)) }, "层底深度 m", Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "删除这层") }
            }
            OutlinedTextField(
                value = layer.lithology, onValueChange = { onChange(layer.copy(lithology = it)) },
                label = { Text("岩性") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            val suggestions = lithologies.filter { it != layer.lithology && (layer.lithology.isBlank() || it.contains(layer.lithology.trim())) }.take(6)
            if (suggestions.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s -> SuggestionChip(onClick = { onChange(layer.copy(lithology = s)) }, label = { Text(s) }) }
                }
            }
            OutlinedTextField(
                value = layer.note, onValueChange = { onChange(layer.copy(note = it)) },
                label = { Text("描述") }, modifier = Modifier.fillMaxWidth(),
            )
            when {
                interval != null -> Text(
                    "${m(interval.top)}–${m(interval.bottom)} m · 厚 ${m(interval.thickness)} m",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
                skipped -> Text("层底不比上一层深，不计入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun HoleSummary(hole: Borehole) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            buildString {
                append("孔深 ${m(hole.total)} m，共 ${hole.intervals().size} 层")
                append(hole.water?.let { "，水位 ${m(it)} m" } ?: "，未见地下水")
            },
            style = MaterialTheme.typography.titleMedium,
        )
        hole.problems().forEach { p ->
            Text(p, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
