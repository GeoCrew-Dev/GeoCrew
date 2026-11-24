package org.geocrew.feature.compass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.BearingFix
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.CoordinateFormatter

/** Fixes a point from compass bearings: forward intersection or resection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BearingFixScreen(onBack: () -> Unit, onOpenRecord: (String) -> Unit, viewModel: BearingFixViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val points by viewModel.points.collectAsStateWithLifecycle()
    val heading by viewModel.heading.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val format by viewModel.coordinateFormat.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    LaunchedEffect(saved) {
        saved?.let { id -> viewModel.savedHandled(); onOpenRecord(id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("方位交会") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                FixMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = ui.mode == m,
                        onClick = { viewModel.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, FixMode.entries.size),
                    ) { Text(m.label) }
                }
            }
            Text(ui.mode.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("方位是磁方位", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (ui.magnetic) "按磁偏角 ${"%+.1f".format(ui.declination)}° 改成真方位再算" else "直接当真方位用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.magnetic, onCheckedChange = viewModel::setMagnetic)
            }
            if (viewModel.hasCompass) {
                Text(
                    heading?.let { "手机现在朝 ${"%03.0f".format(if (ui.magnetic) it else (it + ui.declination + 360) % 360)}°（竖起手机用后摄像头对准目标，或平放用顶边对准）" } ?: "罗盘读数还没出来",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            ui.sights.forEachIndexed { i, s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (ui.mode == FixMode.FORWARD) "站点 " else "地物 ") + (i + 1),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (ui.sights.size > 2) IconButton(onClick = { viewModel.removeSight(i) }) { Icon(Icons.Default.Close, "删去这条视线") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { choosing = i }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Place, null)
                                Text(s.point?.title ?: "选已知点", Modifier.padding(start = 4.dp))
                            }
                            if (ui.mode == FixMode.FORWARD && fix != null) {
                                IconButton(onClick = { viewModel.useHere(i) }) { Icon(Icons.Default.MyLocation, "用我现在的位置") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = s.bearing,
                                onValueChange = { viewModel.setBearing(i, it) },
                                label = { Text(if (ui.mode == FixMode.FORWARD) "向目标的方位（°）" else "向这个地物的方位（°）") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            if (viewModel.hasCompass) {
                                TextButton(onClick = { viewModel.readCompass(i) }, enabled = heading != null) {
                                    Icon(Icons.Default.Explore, null)
                                    Text("读罗盘", Modifier.padding(start = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = viewModel::addSight) {
                Icon(Icons.Default.Add, null)
                Text("再加一条视线（三条以上能看出读数准不准）", Modifier.padding(start = 4.dp))
            }

            ui.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ui.result?.let { r -> FixResult(r, ui.mode, format, viewModel::save) }
        }
    }

    choosing?.let { i ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(if (ui.mode == FixMode.FORWARD) "选站点" else "选已知地物") },
            text = {
                if (points.isEmpty()) Text("工程里还没有点记录。先在地图上标出已知点（如山顶、三角点），再回来选。")
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(points, key = { it.id ?: it.title }) { p ->
                        ListItem(
                            headlineContent = { Text(p.title) },
                            supportingContent = { Text(CoordinateFormatter.format(p.position, format)) },
                            modifier = Modifier.clickable { viewModel.setPoint(i, p); choosing = null },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun FixResult(r: BearingFix.Result, mode: FixMode, format: CoordinateFormat, onSave: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (mode == FixMode.FORWARD) "目标位置" else "你的位置", style = MaterialTheme.typography.titleSmall)
            Text(CoordinateFormatter.format(r.position, format), style = MaterialTheme.typography.titleMedium)
            Text(
                "离各站点 " + r.ranges.joinToString("、") { CoordinateFormatter.distance(it) } +
                    " · 交会角 ${"%.0f".format(r.minAngle)}°" +
                    if (r.misses.size > 2) " · 视线最大偏离 ${CoordinateFormatter.distance(r.worstMiss)}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (r.minAngle < BearingFix.WEAK_ANGLE) {
                Text("交会角只有 ${"%.0f".format(r.minAngle)}°，方位差 1° 位置就会偏很多；最好换成夹角在 30°–150° 之间的点。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (r.behind) {
                Text(
                    if (mode == FixMode.FORWARD) "交点落在某个站点的背后，可能有方位读反了（差 180°）。" else "交点落在某个地物的另一侧，可能有方位读反了（差 180°）。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.padding(top = 4.dp)) {
                Button(onClick = onSave) { Text("存为地质点") }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            }
        }
    }
}
