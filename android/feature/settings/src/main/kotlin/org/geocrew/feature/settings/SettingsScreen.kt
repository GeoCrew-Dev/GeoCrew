package org.geocrew.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.tiles.TileSource
import org.geocrew.core.ui.SectionHeader

private const val TIANDITU_SITE = "https://www.tianditu.gov.cn/"

private fun displayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? =
    runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val s = settings
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val sizes by viewModel.sizes.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TileSource?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val resolver = LocalContext.current.contentResolver
    val pickMbTiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBasemap(uri, displayName(resolver, uri) ?: "离线底图")
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (s == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("天地图 Key") }
            item {
                KeySection(
                    saved = s.tiandituKey,
                    hasBuildKey = viewModel.hasBuildKey,
                    checking = checking,
                    check = check,
                    onSaveAndCheck = viewModel::saveAndCheck,
                    onClear = viewModel::clearKey,
                )
            }

            item { SectionHeader("自定义图源") }
            item {
                Text(
                    "可以添加任何 XYZ 瓦片或 WMTS（KVP）图源，地址里用 {z}/{x}/{y} 表示级别和行列，{s} 表示子域名，{-y} 表示 TMS 行号。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(s.customSources.filterNot { it.offline }, key = { it.id }) { src ->
                ListItem(
                    headlineContent = { Text(src.name) },
                    supportingContent = { Text(src.layers.firstOrNull()?.url.orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { IconButton(onClick = { viewModel.deleteSource(src.id) }) { Icon(Icons.Default.Delete, "删除") } },
                )
            }
            item {
                TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("添加图源")
                }
            }

            item { SectionHeader("离线底图") }
            item {
                Text(
                    "没有信号的测区，可以提前用桌面软件（如 QGIS、MOBAC）把底图导出成 MBTiles 栅格瓦片包，再在这里导入。导入后存在手机里，地图的底图切换里会多出这一项。也可以在地图的「图层 · 下载当前范围」里直接把屏幕上的天地图或自定义底图存下来（OpenStreetMap 官方瓦片不允许批量下载）。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(s.customSources.filter { it.offline }, key = { it.id }) { src ->
                val l = src.layers.firstOrNull()
                ListItem(
                    headlineContent = { Text(src.name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(l?.let { "${it.minZoom}–${it.maxZoom} 级" }, sizes[src.id]?.let(::formatBytes)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = { IconButton(onClick = { deleting = src }) { Icon(Icons.Default.Delete, "删除") } },
                )
            }
            item {
                val p = importing
                if (p == null) {
                    TextButton(onClick = { pickMbTiles.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("导入 MBTiles")
                    }
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            if (p.total == 0) "正在读取文件…" else "正在解包瓦片 ${p.done} / ${p.total}，请停留在本页",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (p.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = viewModel::cancelImport) { Text("取消") }
                    }
                }
            }

            item { SectionHeader("地图缓存") }
            item {
                Text(
                    "联网时看过的底图会存在手机里，没信号时这些地方（看过的缩放级别）还能显示。出发前在有网的地方把测区浏览一遍，缓存够大就能带进山里。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    org.geocrew.core.map.MapCache.SIZES_MB.forEach { mb ->
                        FilterChip(
                            selected = s.mapCacheMb == mb,
                            onClick = { viewModel.setMapCache(mb) },
                            label = { Text(if (mb >= 1000) "${mb / 1000} GB" else "$mb MB") },
                        )
                    }
                }
            }
            item {
                TextButton(onClick = viewModel::clearMapCache, modifier = Modifier.padding(horizontal = 8.dp)) { Text("清空缓存") }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("显示") }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = s.coordinateFormat == CoordinateFormat.DECIMAL,
                        onClick = { viewModel.setCoordinateFormat(CoordinateFormat.DECIMAL) },
                        label = { Text("十进制度") },
                    )
                    FilterChip(
                        selected = s.coordinateFormat == CoordinateFormat.DMS,
                        onClick = { viewModel.setCoordinateFormat(CoordinateFormat.DMS) },
                        label = { Text("度分秒") },
                    )
                }
            }

            item { SectionHeader("罗盘") }
            item { DeclinationRow(s.declination, viewModel::setDeclination) }

            item { SectionHeader("定点") }
            item {
                Text(
                    "用「定点」在当前位置打点时，先连续定位一段时间再取加权平均，适合采样点和控制点。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 10, 30, 60).forEach { sec ->
                        FilterChip(
                            selected = s.pointAverageSeconds == sec,
                            onClick = { viewModel.setPointAverage(sec) },
                            label = { Text(if (sec == 0) "不平均" else "$sec 秒") },
                        )
                    }
                }
            }

            item { SectionHeader("轨迹记录") }
            item {
                NumberRow("记录间隔（秒）", s.trackIntervalSeconds) { viewModel.setTrackInterval(it) }
            }
            item {
                NumberRow("最小间距（米）", s.trackMinDistanceMeters) { viewModel.setTrackMinDistance(it) }
            }

            item { SectionHeader("工作组") }
            item { NicknameRow(s.nickname, viewModel::setNickname) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    deleting?.let { src ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除离线底图") },
            text = { Text("删除「${src.name}」和手机里的瓦片文件？用到它的工程会改回默认底图。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBasemap(src)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    if (adding) {
        AddSourceDialog(onDismiss = { adding = false }, onAdd = { viewModel.addSource(it); adding = false })
    }
}

@Composable
private fun KeySection(
    saved: String,
    hasBuildKey: Boolean,
    checking: Boolean,
    check: KeyCheck?,
    onSaveAndCheck: (String) -> Unit,
    onClear: () -> Unit,
) {
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    val uri = LocalUriHandler.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "同勘不内置天地图 Key，每位用户使用自己的 Key，调用额度互不影响。申请方法：在天地图官网注册开发者账号，" +
                "进入控制台创建应用，应用类型选「服务端」，把得到的 Key 粘贴到下面。",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { uri.openUri(TIANDITU_SITE) }) { Text("打开天地图官网") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.trim() },
            label = { Text("天地图 Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onSaveAndCheck(text) }, enabled = text.isNotBlank() && !checking) { Text("保存并校验") }
            if (saved.isNotBlank()) OutlinedButton(onClick = { text = ""; onClear() }) { Text("清除") }
            if (checking) CircularProgressIndicator(Modifier.padding(start = 4.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp)
        }
        when (check) {
            KeyCheck.Ok -> Text("Key 可用，已保存。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            is KeyCheck.Failed -> Text(check.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            null -> if (saved.isBlank() && hasBuildKey) {
                Text("当前为开发版，暂用编译时提供的开发 Key；正式版必须填写自己的 Key。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DeclinationRow(value: Float?, onChange: (Float?) -> Unit) {
    var manual by remember(value) { mutableStateOf(value != null) }
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("磁偏角自动计算", modifier = Modifier.weight(1f))
            Switch(checked = !manual, onCheckedChange = { auto ->
                manual = !auto
                if (auto) onChange(null) else onChange(text.toFloatOrNull() ?: 0f)
            })
        }
        if (manual) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; it.toFloatOrNull()?.takeIf { v -> v in -90f..90f }?.let(onChange) },
                label = { Text("磁偏角（度，东偏为正）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("按位置和日期用地磁模型计算，一般无需手动设置。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NumberRow(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit); text.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun NicknameRow(value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text("我的昵称（加入工作组时显示）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (CustomSourceForm) -> Unit) {
    var form by remember { mutableStateOf(CustomSourceForm()) }
    var tried by remember { mutableStateOf(false) }
    val error = form.error()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加图源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(form.name, { form = form.copy(name = it) }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(
                    form.url,
                    { form = form.copy(url = it.trim()) },
                    label = { Text("瓦片地址") },
                    placeholder = { Text("https://{s}.example.com/{z}/{x}/{y}.png") },
                )
                OutlinedTextField(form.subdomains, { form = form.copy(subdomains = it) }, label = { Text("子域名（如 a,b,c 或 0-7，可空）") }, singleLine = true)
                OutlinedTextField(
                    form.maxZoom,
                    { form = form.copy(maxZoom = it.filter(Char::isDigit)) },
                    label = { Text("最大级别") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(form.attribution, { form = form.copy(attribution = it) }, label = { Text("版权说明（可空）") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(form.tms, { form = form.copy(tms = it) })
                    Text("TMS 行号（y 轴向上）")
                }
                if (tried && error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { tried = true; if (error == null) onAdd(form) }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
