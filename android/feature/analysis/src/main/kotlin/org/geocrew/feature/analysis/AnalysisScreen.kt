package org.geocrew.feature.analysis

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.gis.GisTool
import org.geocrew.core.gis.LegendEntry
import org.geocrew.core.gis.ToolParam
import org.geocrew.core.gis.GeometryType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(onBack: () -> Unit, onShowOnMap: () -> Unit, viewModel: AnalysisViewModel = hiltViewModel()) {
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val t = tool
    BackHandler(enabled = t != null) { viewModel.choose(null) }

    // A tool from another app asks to start its screen; its result goes back to the waiting run.
    val pluginRequest by viewModel.plugins.request.collectAsStateWithLifecycle()
    val pluginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.plugins.finish(it.resultCode)
    }
    LaunchedEffect(pluginRequest) {
        val r = pluginRequest ?: return@LaunchedEffect
        viewModel.plugins.launched(r.id)
        try {
            pluginLauncher.launch(r.intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.plugins.finish(PluginHost.LAUNCH_FAILED)
        } catch (_: SecurityException) {
            viewModel.plugins.finish(PluginHost.LAUNCH_FAILED)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t?.name ?: "空间分析") },
                navigationIcon = {
                    IconButton(onClick = { if (t != null) viewModel.choose(null) else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (t == null) ToolList(viewModel.registry.all, viewModel::choose) else ToolForm(t, viewModel, onShowOnMap)
        }
    }
}

@Composable
private fun ToolList(tools: List<GisTool>, onChoose: (GisTool) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "分析当前工程的记录，结果作为新的记录保存，可同步给组员，也可导出。",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(tools, key = { it.id }) { t ->
            ListItem(
                headlineContent = { Text(t.name) },
                supportingContent = { Text(t.description) },
                leadingContent = { Icon(Icons.Default.Analytics, null) },
                modifier = Modifier.clickable { onChoose(t) },
            )
        }
    }
}

/** Recent features offered for a feature parameter; older ones can be renamed or redrawn. */
private const val MAX_FEATURE_CHOICES = 30

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolForm(tool: GisTool, vm: AnalysisViewModel, onShowOnMap: () -> Unit) {
    val values by vm.values.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val fields by vm.fields.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(tool.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val referenceLayers by vm.referenceLayers.collectAsStateWithLifecycle()
        val withReference by vm.withReference.collectAsStateWithLifecycle()
        val referenceCount by vm.referenceCount.collectAsStateWithLifecycle()
        Text(
            "将使用当前工程中的 ${input.size - referenceCount} 条记录" + if (referenceCount > 0) "和 $referenceCount 个参考图形" else "",
            style = MaterialTheme.typography.labelLarge,
        )
        if (referenceLayers.isNotEmpty()) {
            Text("也用参考图层里的图形（例如拿前人地质图的图斑做图切剖面、面内统计）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                referenceLayers.forEach { l ->
                    FilterChip(selected = l.id in withReference, onClick = { vm.toggleReference(l.id) }, label = { Text(l.name) })
                }
            }
        }

        tool.params.forEach { p ->
            when (p) {
                is ToolParam.Field -> {
                    Text(p.label, style = MaterialTheme.typography.titleSmall)
                    p.help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val choices = if (p.numeric) fields else categories
                    if (choices.isEmpty()) {
                        Text(
                            if (p.numeric) "记录里没有数值属性。可在记录详情中添加，例如 grade = 3.2。" else "记录里没有可分类的文字属性，例如岩性。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            choices.forEach { f ->
                                FilterChip(
                                    selected = values[p.key] == f,
                                    onClick = { vm.set(p.key, if (values[p.key] == f) "" else f) },
                                    label = { Text(f) },
                                )
                            }
                        }
                    }
                }
                is ToolParam.Number -> OutlinedTextField(
                    value = values[p.key].orEmpty(),
                    onValueChange = { vm.set(p.key, it) },
                    label = { Text(p.label + (p.unit?.let { "（$it）" } ?: "")) },
                    placeholder = { Text(p.default?.let { trim(it) } ?: "自动") },
                    supportingText = { p.help?.let { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                is ToolParam.Feature -> {
                    Text(p.label, style = MaterialTheme.typography.titleSmall)
                    p.help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val candidates = input.filter { GeometryType.of(it.geometry) in p.types }.sortedByDescending { it.sync.updatedAt }
                    if (candidates.isEmpty()) {
                        Text("工程里还没有可选的记录，先在地图上画一条。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            candidates.take(MAX_FEATURE_CHOICES).forEach { f ->
                                FilterChip(
                                    selected = values[p.key] == f.id,
                                    onClick = { vm.set(p.key, if (values[p.key] == f.id) "" else f.id) },
                                    label = { Text(f.title ?: f.id.take(8)) },
                                )
                            }
                        }
                    }
                }
                is ToolParam.Choice -> {
                    Text(p.label, style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.options.forEach { o ->
                            FilterChip(
                                selected = (values[p.key] ?: p.default) == o.value,
                                onClick = { vm.set(p.key, o.value) },
                                label = { Text(o.label) },
                            )
                        }
                    }
                }
            }
        }

        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (run.running) {
            LinearProgressIndicator(progress = { run.progress }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        } else {
            Button(onClick = vm::start, enabled = problem == null, modifier = Modifier.fillMaxWidth()) { Text("运行") }
        }

        run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        run.output?.let { out ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("结果", style = MaterialTheme.typography.titleMedium)
                    Text(out.summary, style = MaterialTheme.typography.bodyMedium)
                    SectionStrip(out)
                    if (remember(out) { Strip.of(out)?.ground != null }) {
                        val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
                            uri?.let(vm::exportSection)
                        }
                        OutlinedButton(
                            onClick = { exportPdf.launch("${Strip.of(out)?.title ?: "剖面"} 图切剖面.pdf") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("导出剖面图 PDF") }
                        run.exported?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                    if (out.legend.isNotEmpty()) Legend(out.legend)
                    if (out.features.isNotEmpty()) {
                        val saved = run.saved
                        if (saved == null) {
                            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("保存 ${out.features.size} 个结果到工程") }
                        } else {
                            Text("已保存 $saved 个结果", color = MaterialTheme.colorScheme.primary)
                            OutlinedButton(onClick = onShowOnMap, modifier = Modifier.fillMaxWidth()) { Text("在地图上查看") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(entries: List<LegendEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).background(parse(e.color), RoundedCornerShape(3.dp)))
                Text("  ${e.label}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun parse(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
