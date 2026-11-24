package org.geocrew.feature.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.formats.FeatureFormat
import org.geocrew.core.ui.KindUi
import org.geocrew.core.ui.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(onBack: () -> Unit, viewModel: TransferViewModel = hiltViewModel()) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val assays by viewModel.assays.collectAsStateWithLifecycle()
    var exportFormat by remember { mutableStateOf<FeatureFormat?>(null) }
    var todayOnly by remember { mutableStateOf(false) }
    var withPhotos by remember { mutableStateOf(true) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(uri)
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = exportFormat
        if (uri != null && f != null) viewModel.export(f, uri)
    }
    val openAssays = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openAssays(uri)
    }
    val createLabels = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.exportLabels(uri)
    }
    val createReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.exportReport(uri, todayOnly, withPhotos)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入 / 导出") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { m ->
                val (text, color) = when (m) {
                    is TransferMessage.Info -> m.text to MaterialTheme.colorScheme.primary
                    is TransferMessage.Error -> m.text to MaterialTheme.colorScheme.error
                }
                Text(text, color = color, modifier = Modifier.padding(16.dp))
            }

            SectionHeader("导入到当前工程")
            Text(
                "支持 " + viewModel.formats.filter { it.canRead }.joinToString("、") { it.displayName } + "。同一文件再次导入会更新已有要素，不会重复。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FilledTonalButton(
                onClick = { viewModel.clearMessage(); openFile.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.Default.FileDownload, null)
                Text(" 选择文件")
            }

            SectionHeader("样品分析结果")
            Text(
                "化验室返回的结果表（CSV，可从 Excel 另存）：一列样品编号，其余每列一个元素或氧化物，第二行可以是单位行。按编号对上已登记的样品，结果存到样品上，同一记录各样品的平均值写成记录属性（低于检出限的「<0.05」按一半计），之后可直接用于图例着色、统计和插值。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FilledTonalButton(
                onClick = { viewModel.clearMessage(); openAssays.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel")) },
                enabled = !busy,
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.Default.FileDownload, null)
                Text(" 导入分析结果")
            }

            SectionHeader("导出当前工程")
            viewModel.formats.filter { it.canWrite }.forEach { f ->
                ListItem(
                    headlineContent = { Text(f.displayName) },
                    supportingContent = { Text(exportHint(f)) },
                    trailingContent = {
                        IconButton(enabled = !busy, onClick = {
                            viewModel.clearMessage()
                            exportFormat = f
                            createFile.launch(viewModel.exportName(f))
                        }) { Icon(Icons.Default.FileUpload, "导出 ${f.displayName}") }
                    },
                )
            }

            SectionHeader("野外记录报告（PDF）")
            Text(
                "按日期整理每条记录的位置、产状、属性、备注和照片，可直接打印或发给他人。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !todayOnly, onClick = { todayOnly = false }, label = { Text("全部") })
                FilterChip(selected = todayOnly, onClick = { todayOnly = true }, label = { Text("仅今天") })
            }
            ListItem(
                headlineContent = { Text("包含照片") },
                supportingContent = { Text("每条最多 3 张，文件会大一些") },
                trailingContent = { Switch(checked = withPhotos, onCheckedChange = { withPhotos = it }) },
            )
            FilledTonalButton(
                onClick = { viewModel.clearMessage(); createReport.launch(viewModel.reportName(todayOnly)) },
                enabled = !busy,
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.Default.PictureAsPdf, null)
                Text(" 生成报告")
            }

            SectionHeader("样品标签（PDF）")
            Text(
                "每件样品一张标签：编号、类型、岩性、点号、日期、坐标和编号二维码。A4 纸每页 3 × 8 张，可打印到不干胶标签纸上贴样品袋。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FilledTonalButton(
                onClick = { viewModel.clearMessage(); createLabels.launch(viewModel.labelsName()) },
                enabled = !busy,
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.Default.PictureAsPdf, null)
                Text(" 生成标签")
            }
        }
    }

    assays?.let { a ->
        val plan = a.plan
        AlertDialog(
            onDismissRequest = viewModel::cancelAssays,
            title = { Text("写入「${a.fileName}」的分析结果？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("对上 ${plan.sampleCount} 件样品，涉及 ${plan.records.size} 条记录。")
                    Text("分析项：" + plan.columns.joinToString("、"))
                    if (plan.belowLimit > 0) Text("其中 ${plan.belowLimit} 个值低于检出限，记录属性按检出限的一半计。", style = MaterialTheme.typography.bodySmall)
                    if (plan.unmatched.isNotEmpty()) {
                        Text(
                            "${plan.unmatched.size} 个编号在工程里找不到：" + plan.unmatched.take(8).joinToString("、") + if (plan.unmatched.size > 8) "……" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (plan.reserved.isNotEmpty()) {
                        Text("这些列名与应用自带属性重名，没有写入：" + plan.reserved.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("同名的分析项会被新值覆盖，原值可在记录的修改历史里找回。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmAssays) { Text("写入") } },
            dismissButton = { TextButton(onClick = viewModel::cancelAssays) { Text("取消") } },
        )
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("导入「${p.fileName}」？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${p.format.displayName}，共 ${p.result.features.size} 个要素：")
                    p.counts.forEach { (k, n) -> Text("· ${KindUi.label(k)} $n") }
                    p.result.warnings.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmImport) { Text("导入") } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text("取消") } },
        )
    }
}

private fun exportHint(f: FeatureFormat) = when (f.id) {
    "geojson" -> "保留全部属性，适合 QGIS 等 GIS 软件和备份"
    "kml" -> "可在奥维、Google 地球等软件中打开"
    "gpx" -> "点和轨迹（含时间），适合导航软件；面不导出"
    "samples" -> "每件样品一行：编号、类型、所在记录、坐标、岩性、采样时间，可直接做送样单"
    "sections" -> "实测剖面分层表：每层一行，起止斜距、岩性、产状、真厚度，可做剖面登记表"
    "routes" -> "地质路线记录表：每条路线按点序一行，位置、岩性、产状、点上与点间描述，另有目的和小结行"
    else -> f.extension
}
