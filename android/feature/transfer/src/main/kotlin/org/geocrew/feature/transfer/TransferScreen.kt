package org.geocrew.feature.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
    var exportFormat by remember { mutableStateOf<FeatureFormat?>(null) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(uri)
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = exportFormat
        if (uri != null && f != null) viewModel.export(f, uri)
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
        }
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
    else -> f.extension
}
