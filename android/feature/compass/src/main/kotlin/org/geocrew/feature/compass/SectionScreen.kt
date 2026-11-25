package org.geocrew.feature.compass

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.MeasuredSection
import org.geocrew.core.model.measuredSection
import org.geocrew.core.ui.SectionHeader
import java.util.Locale

private fun f1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
private fun f2(v: Double) = String.format(Locale.ROOT, "%.2f", v)

/**
 * 实测剖面: the traverse (导线) as tape-and-compass legs and the layers (分层)
 * met along it; thicknesses update as the numbers are typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionScreen(onBack: () -> Unit, viewModel: SectionViewModel = hiltViewModel()) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    BackHandler(enabled = draft != null) { viewModel.close() }

    // Stratigraphic column export: ask which way the traverse ran, then where to save.
    var columnFor by remember { mutableStateOf<GeoFeature?>(null) }
    var exporting by remember { mutableStateOf<Pair<GeoFeature, Boolean>?>(null) }
    val createColumn = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val e = exporting
        if (uri != null && e != null) viewModel.exportColumn(e.first, uri, e.second)
        exporting = null
    }
    columnFor?.let { f ->
        ColumnDialog(
            onDismiss = { columnFor = null },
            onConfirm = { startIsBase ->
                columnFor = null
                exporting = f to startIsBase
                createColumn.launch(viewModel.columnName(f))
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft == null) "实测剖面" else draft?.title?.ifBlank { "实测剖面" } ?: "") },
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
                    text = { Text("新建剖面") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = draft
        if (d == null) {
            SectionList(sections, Modifier.padding(padding), onOpen = viewModel::open, onDelete = viewModel::delete, onExport = { columnFor = it })
        } else {
            SectionEditor(d, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun SectionList(items: List<GeoFeature>, modifier: Modifier, onOpen: (GeoFeature) -> Unit, onDelete: (String) -> Unit, onExport: (GeoFeature) -> Unit) {
    var confirm by remember { mutableStateOf<GeoFeature?>(null) }
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "还没有实测剖面。沿剖面拉皮尺、用罗盘测每段导线的方位和坡角，再记下每个分层的终点和产状，就能算出各层真厚度。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(32.dp),
            )
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            items(items, key = { it.id }) { f ->
                val s = f.measuredSection
                val r = s.compute()
                ListItem(
                    headlineContent = { Text(f.title?.ifBlank { null } ?: "实测剖面") },
                    supportingContent = {
                        Text("导线 ${s.legs.size} 段 · ${f1(s.length)} m · ${s.layers.size} 层 · 总厚 ${f1(r.total)} m")
                    },
                    trailingContent = {
                        Row {
                            if (s.layers.isNotEmpty()) IconButton(onClick = { onExport(f) }) { Icon(Icons.Default.PictureAsPdf, "导出柱状图") }
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
            title = { Text("删除这条剖面？") },
            text = { Text("「${f.title ?: "实测剖面"}」的导线和分层都会删除，组员同步后也会看到删除。") },
            confirmButton = { TextButton(onClick = { onDelete(f.id); confirm = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionEditor(d: SectionDraft, vm: SectionViewModel, modifier: Modifier) {
    val picks by vm.picks.collectAsStateWithLifecycle()
    val lithologies by vm.lithologies.collectAsStateWithLifecycle()
    var pickStart by remember { mutableStateOf(false) }
    var pickAttitudeFor by remember { mutableStateOf<Int?>(null) }
    val section = d.section()
    val result = section.compute()

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = d.title,
                    onValueChange = { t -> vm.edit { it.copy(title = t) } },
                    label = { Text("剖面名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    d.start?.let { "起点：${it.label}" } ?: "起点：未设置（设置后剖面线会画在地图上）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = vm::startHere, label = { Text("用当前位置") }, leadingIcon = { Icon(Icons.Default.MyLocation, null) })
                    AssistChip(onClick = { pickStart = true }, label = { Text("用一条记录") }, leadingIcon = { Icon(Icons.Default.Place, null) })
                }
            }
        }

        item { SectionHeader("导线（从起点依次）", Modifier.padding(horizontal = 16.dp)) }
        itemsIndexed(d.legs) { i, leg ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${i + 1}", Modifier.width(20.dp), style = MaterialTheme.typography.bodySmall)
                Num(leg.azimuth, { t -> vm.edit { it.copy(legs = it.legs.with(i, leg.copy(azimuth = t))) } }, "方位°", Modifier.weight(1f))
                Num(leg.slope, { t -> vm.edit { it.copy(legs = it.legs.with(i, leg.copy(slope = t))) } }, "坡角°", Modifier.weight(1f), signed = true)
                Num(leg.distance, { t -> vm.edit { it.copy(legs = it.legs.with(i, leg.copy(distance = t))) } }, "斜距 m", Modifier.weight(1f))
                IconButton(onClick = { vm.edit { it.copy(legs = it.legs.without(i).ifEmpty { listOf(SectionLegInput()) }) } }) {
                    Icon(Icons.Default.Close, "删除这段")
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    vm.edit { it.copy(legs = it.legs + SectionLegInput(azimuth = it.legs.lastOrNull()?.azimuth.orEmpty())) }
                }) { Text("添加一段导线") }
                Text("总长 ${f1(section.length)} m（斜距）", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "坡角：沿前进方向上坡为正、下坡为负。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item { SectionHeader("分层", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        itemsIndexed(d.layers) { i, layer ->
            // Layers that do not parse yet are left out of the section, so map by position among the valid ones.
            val k = d.layers.take(i).count { it.parse() != null }
            LayerCard(
                layer = layer,
                result = if (layer.parse() != null) result.layers.getOrNull(k) else null,
                lithologies = lithologies,
                onChange = { l -> vm.edit { it.copy(layers = it.layers.with(i, l)) } },
                onRemove = { vm.edit { it.copy(layers = it.layers.without(i).ifEmpty { listOf(SectionLayerInput("1")) }) } },
                onPickAttitude = { pickAttitudeFor = i },
            )
        }
        item {
            TextButton(
                onClick = { vm.edit { it.copy(layers = it.layers + SectionLayerInput(it.nextNo())) } },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("添加分层") }
        }

        item { SectionHeader("结果", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        item { Results(section, result) }
    }

    if (pickStart) {
        PickList(
            title = "以哪条记录的位置为起点",
            items = picks.filter { it.position != null },
            detail = { "有位置" + (it.attitude?.let { a -> " · ${a.format()}" } ?: "") },
            onDismiss = { pickStart = false },
            onPick = { vm.startFromPick(it); pickStart = false },
        )
    }
    pickAttitudeFor?.let { i ->
        PickList(
            title = "取哪条记录的产状",
            items = picks.filter { it.attitude != null },
            detail = { it.attitude!!.format() },
            onDismiss = { pickAttitudeFor = null },
            onPick = { p ->
                val a = p.attitude!!
                vm.edit { it.copy(layers = it.layers.with(i, it.layers[i].copy(dipDirection = a.dipDirection.short(), dip = a.dip.short()))) }
                pickAttitudeFor = null
            },
        )
    }
}

@Composable
private fun LayerCard(
    layer: SectionLayerInput,
    result: MeasuredSection.LayerResult?,
    lithologies: List<String>,
    onChange: (SectionLayerInput) -> Unit,
    onRemove: () -> Unit,
    onPickAttitude: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = layer.no, onValueChange = { onChange(layer.copy(no = it)) },
                    label = { Text("层号") }, singleLine = true, modifier = Modifier.width(84.dp),
                )
                Num(layer.top, { onChange(layer.copy(top = it)) }, "终点累计斜距 m", Modifier.weight(1f))
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Num(layer.dipDirection, { onChange(layer.copy(dipDirection = it)) }, "倾向°", Modifier.weight(1f))
                Num(layer.dip, { onChange(layer.copy(dip = it)) }, "倾角°", Modifier.weight(1f))
                TextButton(onClick = onPickAttitude) { Text("取记录") }
            }
            OutlinedTextField(
                value = layer.note, onValueChange = { onChange(layer.copy(note = it)) },
                label = { Text("备注") }, modifier = Modifier.fillMaxWidth(),
            )
            if (result != null) {
                val t = result.thickness
                val text = buildString {
                    append("${f1(result.from)}–${f1(result.to)} m · ")
                    append(if (t == null) "没有产状，算不出厚度" else "真厚 ${f2(t)} m")
                    if (result.borrowed) result.attitude?.let { append("（借用 ${it.format()}）") }
                    if (result.mixed) append(" · 导线在本层内折返，厚度只计净值")
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Results(section: MeasuredSection, result: MeasuredSection.Result) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("总真厚度 ${f2(result.total)} m，共 ${result.layers.size} 层", style = MaterialTheme.typography.titleMedium)
        result.problems.forEach { p ->
            Text(p, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (result.profile.size >= 2) {
            Text("剖面草图（水平距离 × 高差，等比例）", style = MaterialTheme.typography.bodySmall)
            ProfileSketch(section, result)
        }
    }
}

/** The traverse in vertical section, at true scale, with a tick at each layer's end. */
@Composable
private fun ProfileSketch(section: MeasuredSection, result: MeasuredSection.Result) {
    val line = MaterialTheme.colorScheme.onSurface
    val tick = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(160.dp).padding(vertical = 8.dp)) {
        val xs = result.profile.map { it.first }
        val zs = result.profile.map { it.second }
        val w = (xs.max() - xs.min()).coerceAtLeast(1e-6)
        val h = (zs.max() - zs.min()).coerceAtLeast(1e-6)
        val scale = minOf(size.width / w, size.height / h).toFloat()
        val ox = ((size.width - w * scale) / 2).toFloat()
        val oy = ((size.height - h * scale) / 2).toFloat()
        fun at(p: Pair<Double, Double>) = Offset(
            ox + ((p.first - xs.min()) * scale).toFloat(),
            size.height - oy - ((p.second - zs.min()) * scale).toFloat(),
        )
        val path = Path()
        result.profile.forEachIndexed { i, p -> val o = at(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        result.layers.forEach { l ->
            val o = at(section.profileAt(l.to))
            drawLine(tick, Offset(o.x, o.y - 6.dp.toPx()), Offset(o.x, o.y + 6.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
        drawCircle(tick, 4.dp.toPx(), at(result.profile.first()))
    }
}

@Composable
internal fun Num(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, signed: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> if (t.all { it.isDigit() || it == '.' || (signed && it == '-') }) onChange(t) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
internal fun PickList(
    title: String,
    items: List<SectionPick>,
    detail: (SectionPick) -> String,
    onDismiss: () -> Unit,
    onPick: (SectionPick) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (items.isEmpty()) {
                Text("当前工程里没有合适的记录。")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(items, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.title) },
                            supportingContent = { Text(detail(p)) },
                            modifier = Modifier.clickable { onPick(p) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun <T> List<T>.with(i: Int, v: T): List<T> = toMutableList().also { if (i in it.indices) it[i] = v }
internal fun <T> List<T>.without(i: Int): List<T> = filterIndexed { j, _ -> j != i }

/** Asks which end of the traverse is the base of the sequence before drawing the column. */
@Composable
private fun ColumnDialog(onDismiss: () -> Unit, onConfirm: (startIsBase: Boolean) -> Unit) {
    var base by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出柱状图") },
        text = {
            Column {
                Text("按各层真厚度画成 A4 柱状图（PDF），自动选比例尺，岩性按名称配花纹。", style = MaterialTheme.typography.bodyMedium)
                listOf(true to "导线起点在底部（自下而上测，第 1 层最老）", false to "导线起点在顶部（自上而下测）").forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { base = v }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = base == v, onClick = { base = v })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(base) }) { Text("导出") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
