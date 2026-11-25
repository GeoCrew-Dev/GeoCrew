package org.geocrew.feature.compass

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.CoordinateParser
import org.geocrew.core.model.Ellipsoid
import org.geocrew.core.model.GaussKruger
import org.geocrew.core.model.LngLat
import org.geocrew.core.ui.InfoRow
import org.geocrew.core.ui.SectionHeader
import java.util.Locale

/**
 * Longitude/latitude to Gauss-Krüger plane coordinates (X northing,
 * Y easting) and back, in 3° or 6° zones, plus a table of every point
 * record for reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinatesScreen(onBack: () -> Unit, viewModel: CoordinatesViewModel = hiltViewModel()) {
    val points by viewModel.points.collectAsStateWithLifecycle()
    val locating by viewModel.locating.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() } }

    var tab by rememberSaveable { mutableStateOf(0) }
    var width by rememberSaveable { mutableStateOf(3) }
    var ellipsoid by rememberSaveable { mutableStateOf(Ellipsoid.CGCS2000) }
    var picking by remember { mutableStateOf(false) }
    val grid by viewModel.grid.collectAsStateWithLifecycle()
    val gridRole by viewModel.gridRole.collectAsStateWithLifecycle()
    var useGrid by rememberSaveable { mutableStateOf(true) }
    val active = grid?.takeIf { useGrid }

    var geoText by rememberSaveable { mutableStateOf("") }
    var geoNote by rememberSaveable { mutableStateOf<String?>(null) }
    var zoneText by rememberSaveable { mutableStateOf("") }
    var xText by rememberSaveable { mutableStateOf("") }
    var yText by rememberSaveable { mutableStateOf("") }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(context.applicationContext, uri, width, ellipsoid, active)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("坐标换算") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("经纬度 → 平面") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("平面 → 经纬度") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("本地坐标系") })
                }
                if (locating) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (tab != 2) item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    grid?.let { g ->
                        FilterChip(selected = useGrid, onClick = { useGrid = !useGrid }, label = { Text(if (useGrid) g.name else "本地坐标系") })
                    }
                    if (active == null) {
                        for (w in GaussKruger.WIDTHS) {
                            FilterChip(selected = width == w, onClick = { width = w }, label = { Text("$w° 带") })
                        }
                        EllipsoidMenu(ellipsoid) { ellipsoid = it }
                    }
                }
            }
            if (tab == 2) {
                item {
                    LocalGridEditor(
                        saved = grid,
                        onSave = { viewModel.saveGrid(it); useGrid = true },
                        onClear = { viewModel.saveGrid(null) },
                        onLocate = { done -> viewModel.locate { p, _ -> done(p) } },
                        locating = locating,
                        lockedNote = if (gridRole == GridRole.MEMBER) "工作组里本地坐标系由组长统一设置，组员这里只能查看和试算。" else null,
                        saveLabel = if (gridRole == GridRole.LEADER) "保存并同步给组员" else "保存到本工程",
                    )
                }
            } else if (tab == 0) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = geoText,
                            onValueChange = { geoText = it; geoNote = null },
                            label = { Text("经纬度") },
                            placeholder = { Text("116.391, 39.907 或 东经116°23′28″ 北纬39°54′25″") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                viewModel.locate { p, acc ->
                                    geoText = "%.7f, %.7f".format(Locale.ROOT, p.lng, p.lat)
                                    geoNote = acc?.let { "当前位置，精度约 %.0f m".format(Locale.ROOT, it) } ?: "当前位置"
                                }
                            }, enabled = !locating) { Text("当前位置") }
                            if (points.isNotEmpty()) OutlinedButton(onClick = { picking = true }) { Text("选记录点") }
                        }
                        geoNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (active == null) ZoneField(zoneText, { zoneText = it }, width, "带号（留空按经度自动选；跨带时可指定相邻带）")
                    }
                }
                item {
                    val p = CoordinateParser.parse(geoText)
                    val zone = zoneText.toIntOrNull()
                    val zoneOk = zone == null || zone in 1..GaussKruger.maxZone(width)
                    when {
                        geoText.isBlank() -> Unit
                        p == null -> Problem("看不懂这个经纬度，请写成「经度, 纬度」或带东经北纬的度分秒")
                        active != null -> {
                            val (x, y) = active.toGrid(org.geocrew.core.model.Geodetic(p, p.alt ?: 0.0))
                            val yz = active.zone * 1_000_000.0 + y
                            val cm = GaussKruger.centralMeridian(active.zone, active.width)
                            val offset = kotlin.math.abs(org.geocrew.core.model.Geodesy.wrapLongitude(p.lng - cm))
                            ResultCard(
                                rows = listOf(
                                    "X（北）" to "%.3f m".format(Locale.ROOT, x),
                                    "Y（东）" to "%.3f m".format(Locale.ROOT, y),
                                    "Y（含带号）" to "%.3f".format(Locale.ROOT, yz),
                                    "坐标系" to active.name,
                                    "转换" to active.shift.describe(),
                                ),
                                warning = if (offset > active.width / 2.0 + 0.01) "这个点离中央子午线 %.2f°，已在本带之外".format(Locale.ROOT, offset) else null,
                                onCopy = { clipboard.setText(AnnotatedString("X=%.3f Y=%.3f".format(Locale.ROOT, x, yz))) },
                            )
                        }
                        !zoneOk -> Problem("${width}° 带的带号是 1 到 ${GaussKruger.maxZone(width)}")
                        else -> {
                            val r = GaussKruger.project(p, width, ellipsoid, zone)
                            val offset = kotlin.math.abs(org.geocrew.core.model.Geodesy.wrapLongitude(p.lng - r.centralMeridian))
                            ResultCard(
                                rows = listOf(
                                    "X（北）" to "%.3f m".format(Locale.ROOT, r.x),
                                    "Y（东）" to "%.3f m".format(Locale.ROOT, r.y),
                                    "Y（含带号）" to "%.3f".format(Locale.ROOT, r.yWithZone),
                                    "带号" to "${r.width}° 带第 ${r.zone} 带",
                                    "中央子午线" to "%.0f°".format(Locale.ROOT, r.centralMeridian),
                                ),
                                warning = if (offset > width / 2.0 + 0.01) "这个点离中央子午线 %.2f°，已在本带之外，变形会变大".format(Locale.ROOT, offset) else null,
                                onCopy = { clipboard.setText(AnnotatedString("X=%.3f Y=%.3f".format(Locale.ROOT, r.x, r.yWithZone))) },
                            )
                        }
                    }
                }
            } else {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecimalField(xText, { xText = it }, "X（北）m")
                        DecimalField(yText, { yText = it }, "Y（东）m，可带带号，如 39447924.48")
                        if (active == null && (yText.toDoubleOrNull() ?: 0.0) < 1_000_000) ZoneField(zoneText, { zoneText = it }, width, "带号（Y 不带带号时必填）")
                    }
                }
                item {
                    val x = xText.toDoubleOrNull()
                    val y = yText.toDoubleOrNull()
                    if (x != null && y != null) {
                        val r = runCatching {
                            active?.let { g ->
                                if (y >= 1_000_000 && kotlin.math.floor(y / 1_000_000).toInt() != g.zone) error("Y 前面的带号与本地坐标系（第 ${g.zone} 带）不一致")
                                g.fromGrid(x, y)
                            } ?: GaussKruger.unproject(x, y, width, ellipsoid, zoneText.toIntOrNull())
                        }
                        r.onSuccess { p -> GeoResult(p) { clipboard.setText(AnnotatedString("%.8f, %.8f".format(Locale.ROOT, p.lng, p.lat))) } }
                        r.onFailure { Problem(it.message ?: "无法换算") }
                    }
                }
            }
            item {
                Text(
                    if (active != null) {
                        "按本工程的本地坐标系换算：${active.shift.describe()}。手机定位的经纬度按 CGCS2000 处理，差别在野外精度以内。"
                    } else {
                        "直接选北京 54、西安 80 只换用对应椭球做投影，不做基准转换，与真实旧坐标可差上百米；" +
                            "要得到旧坐标，请在「本地坐标系」里填转换参数或用公共点求解。手机定位得到的经纬度可直接按 CGCS2000 换算。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item { SectionHeader("导出") }
            item {
                ListItem(
                    headlineContent = { Text("导出全部点的平面坐标") },
                    supportingContent = { Text("当前工程的点记录存成 CSV 表（名称、经纬度、高程、X、Y、带号），可直接用表格软件打开；全部点放在同一带里") },
                    trailingContent = { Icon(Icons.Default.FileDownload, null) },
                    modifier = Modifier.clickable(enabled = points.isNotEmpty()) { save.launch("平面坐标-${width}度带.csv") },
                )
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("选择记录点") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(points, key = { it.id }) { pick ->
                        ListItem(
                            headlineContent = { Text(pick.title) },
                            supportingContent = { Text("%.6f, %.6f".format(Locale.ROOT, pick.position.lng, pick.position.lat)) },
                            modifier = Modifier.clickable {
                                geoText = "%.8f, %.8f".format(Locale.ROOT, pick.position.lng, pick.position.lat)
                                geoNote = "记录：${pick.title}"
                                picking = false
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EllipsoidMenu(value: Ellipsoid, onChange: (Ellipsoid) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = true, onClick = { open = true }, label = { Text(value.label.substringBefore('（')) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (e in Ellipsoid.entries) {
                DropdownMenuItem(text = { Text(e.label) }, onClick = { onChange(e); open = false })
            }
        }
    }
}

@Composable
private fun ZoneField(value: String, onChange: (String) -> Unit, width: Int, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> if (t.length <= 3 && t.all { it.isDigit() }) onChange(t) },
        label = { Text(label) },
        supportingText = { Text("${width}° 带：1–${GaussKruger.maxZone(width)}") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DecimalField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> if (t.all { it.isDigit() || it == '.' || it == '-' }) onChange(t) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Problem(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
}

@Composable
private fun ResultCard(rows: List<Pair<String, String>>, warning: String?, onCopy: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((k, v) in rows) InfoRow(k, v)
            warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, null)
                Text("  复制 X、Y", fontFamily = FontFamily.Default)
            }
        }
    }
}

@Composable
private fun GeoResult(p: LngLat, onCopy: () -> Unit) {
    ResultCard(
        rows = listOf(
            "经度" to "%.8f°".format(Locale.ROOT, p.lng),
            "纬度" to "%.8f°".format(Locale.ROOT, p.lat),
            "度分秒" to "${CoordinateFormatter.dms(p.lng, "E", "W")} ${CoordinateFormatter.dms(p.lat, "N", "S")}",
        ),
        warning = null,
        onCopy = onCopy,
    )
}
