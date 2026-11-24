package org.geocrew.feature.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.FormatShapes
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.lengthMeters
import org.geocrew.core.ui.KindUi

@Composable
internal fun ToolBar(
    recording: Boolean,
    hasFix: Boolean,
    onPointHere: () -> Unit,
    onPointCenter: () -> Unit,
    onTrack: () -> Unit,
    onLine: () -> Unit,
    onPolygon: () -> Unit,
    onMeasure: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tool(Icons.Default.GpsFixed, "定点", enabled = hasFix, onClick = onPointHere)
            Tool(Icons.Default.Place, "中心加点", onClick = onPointCenter)
            Tool(
                if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                if (recording) "停止轨迹" else "轨迹",
                tint = if (recording) MaterialTheme.colorScheme.error else null,
                onClick = onTrack,
            )
            Tool(Icons.Default.Timeline, "画线", onClick = onLine)
            Tool(Icons.Default.FormatShapes, "画面", onClick = onPolygon)
            Tool(Icons.Default.Straighten, "测量", onClick = onMeasure)
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean = true, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Column(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val color = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            tint != null -> tint
            else -> MaterialTheme.colorScheme.onSurface
        }
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
internal fun DrawBar(
    mode: MapMode.Draw,
    onAddCenter: () -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onToggleKind: () -> Unit = {},
    onRemovePicked: () -> Unit = {},
) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            val v = mode.vertices
            val measure = when {
                mode.single -> "点地图上的新位置，或把十字中心对准后点「移到中心」"
                mode.picked != null -> "已选中第 ${mode.picked + 1} 个节点：点地图把它移过去，再点它一下取消选中"
                mode.closed && v.size >= 3 -> {
                    val ring = Geometry.Polygon.of(v)
                    "面积 " + CoordinateFormatter.area(ring.areaSquareMeters()) +
                        " · 周长 " + CoordinateFormatter.distance(ring.lengthMeters())
                }
                v.size >= 2 -> {
                    val total = "长度 " + CoordinateFormatter.distance(Geometry.LineString(v).lengthMeters())
                    if (mode.closed) total else {
                        val a = v[v.size - 2]
                        val b = v.last()
                        total + " · 末段 " + CoordinateFormatter.distance(Geodesy.distance(a, b)) +
                            " 方位 " + "%.0f°".format(Geodesy.bearing(a, b))
                    }
                }
                mode.editing != null -> "点节点可选中移动，点线旁边加节点"
                else -> "点地图或用十字中心添加节点"
            }
            val title = when {
                mode.single -> "移动点"
                mode.editing != null -> "改形状"
                mode.measureOnly -> if (mode.closed) "测面积" else "测距离"
                mode.closed -> "画面"
                else -> "画线"
            }
            val count = if (mode.single) "" else "${v.size} 个节点 · "
            Text("$title：$count$measure", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val moves = mode.single || mode.picked != null
                Tool(Icons.Default.Place, if (moves) "移到中心" else "中心加节点", onClick = onAddCenter)
                Tool(Icons.AutoMirrored.Filled.Undo, "撤销", enabled = mode.undo.isNotEmpty(), onClick = onUndo)
                if (mode.picked != null) Tool(Icons.Default.Delete, "删节点", enabled = mode.canRemovePicked, onClick = onRemovePicked)
                if (mode.editing != null) {
                    Tool(Icons.Default.Check, "保存", enabled = mode.canFinish && mode.undo.isNotEmpty(), onClick = onFinish)
                    Tool(Icons.Default.Close, "取消", onClick = onCancel)
                } else if (mode.measureOnly) {
                    Tool(Icons.Default.SwapHoriz, if (mode.closed) "改测距离" else "改测面积", onClick = onToggleKind)
                    Tool(Icons.Default.Check, "存为要素", enabled = mode.canFinish, onClick = onFinish)
                    Tool(Icons.Default.Close, "结束", onClick = onCancel)
                } else {
                    Tool(Icons.Default.Check, "完成", enabled = mode.canFinish, onClick = onFinish)
                    Tool(Icons.Default.Close, "取消", onClick = onCancel)
                }
            }
        }
    }
}

@Composable
internal fun AlignBar(
    mode: AlignMode,
    onTapCenter: () -> Unit,
    onUndo: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            val n = mode.pairs.size
            val step = if (mode.pending == null) {
                "第 ${n + 1} 对：先点图片上认得出的地物（路口、山头、河流交汇处）"
            } else {
                "再点它在底图上的真实位置"
            }
            val fit = when {
                n == 0 -> ""
                mode.preview == null -> " · 这几处点几乎在一条直线上"
                n == 1 -> " · 已平移"
                n == 2 -> " · 已旋转缩放"
                else -> mode.worstResidual?.let { " · 最大偏差约 ${Math.round(it)} 米" }.orEmpty()
            }
            Text("对齐「${mode.name}」：$step$fit", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Tool(Icons.Default.Place, "取十字中心", onClick = onTapCenter)
                Tool(Icons.AutoMirrored.Filled.Undo, "撤销", enabled = n > 0 || mode.pending != null, onClick = onUndo)
                Tool(Icons.Default.Check, "应用", enabled = n > 0 && mode.pending == null, onClick = onApply)
                Tool(Icons.Default.Close, "取消", onClick = onCancel)
            }
        }
    }
}

@Composable
internal fun SelectedFeatureCard(
    f: GeoFeature,
    format: CoordinateFormat,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    /** Move or reshape on the map; null hides the button. */
    onEdit: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(KindUi.icon(f.kind), null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(f.title ?: KindUi.label(f.kind), style = MaterialTheme.typography.titleMedium)
                val g = f.geometry
                val detail = when (g) {
                    is Geometry.Point -> CoordinateFormatter.format(g.position, format) +
                        (org.geocrew.core.model.MapSheet.of(g.position, org.geocrew.core.model.MapSheet.Scale.M50K)?.let { " · 1:5万 ${it.code}" } ?: "")
                    is Geometry.Polygon, is Geometry.MultiPolygon -> "面积 " + CoordinateFormatter.area(g.areaSquareMeters())
                    null -> KindUi.label(f.kind)
                    else -> "长度 " + CoordinateFormatter.distance(g.lengthMeters())
                }
                Text(KindUi.label(f.kind) + " · " + detail, style = MaterialTheme.typography.bodySmall)
            }
            onEdit?.let { IconButton(onClick = it) { Icon(Icons.Default.Edit, if (f.geometry is Geometry.Point) "移动" else "改形状") } }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayerSheet(
    base: BaseMapUi,
    display: org.geocrew.core.model.MapDisplay,
    /** Record kinds present in the project, offered as on/off chips. */
    kinds: List<org.geocrew.core.model.FeatureKind>,
    inTeam: Boolean,
    onDisplay: ((org.geocrew.core.model.MapDisplay) -> org.geocrew.core.model.MapDisplay) -> Unit,
    onChoose: (String) -> Unit,
    onManage: () -> Unit,
    /** Keep the area on screen of the current base map offline. */
    onDownload: () -> Unit,
    overlays: List<OverlayItem>,
    overlayActions: OverlayActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding()) {
            item { Text("显示", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            if (kinds.isNotEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    kinds.forEach { k ->
                        FilterChip(
                            selected = display.shows(k),
                            onClick = { onDisplay { it.toggle(k) } },
                            label = { Text(org.geocrew.core.ui.KindUi.label(k)) },
                        )
                    }
                }
            }
            item { DisplaySwitch("记录名称", "放大到一定比例后显示", display.names) { v -> onDisplay { it.copy(names = v) } } }
            item {
                ListItem(
                    headlineContent = { Text("公里网") },
                    supportingContent = {
                        Column {
                            Text("高斯-克吕格坐标网（CGCS2000），标注单位为千米，横坐标前是带号")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0 to "不显示", 3 to "3° 带", 6 to "6° 带").forEach { (w, label) ->
                                    FilterChip(selected = display.grid == w, onClick = { onDisplay { it.copy(grid = w) } }, label = { Text(label) })
                                }
                            }
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("图幅线") },
                    supportingContent = {
                        Column {
                            Text("国家基本比例尺地形图分幅（GB/T 13989），中间标注新图幅号；视野里图幅太多时不画")
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf<org.geocrew.core.model.MapSheet.Scale?>(null) + listOf(
                                    org.geocrew.core.model.MapSheet.Scale.M100K,
                                    org.geocrew.core.model.MapSheet.Scale.M50K,
                                    org.geocrew.core.model.MapSheet.Scale.M25K,
                                    org.geocrew.core.model.MapSheet.Scale.M10K,
                                )).forEach { s ->
                                    FilterChip(
                                        selected = display.sheetScale == s,
                                        onClick = { onDisplay { it.copy(sheets = s?.name) } },
                                        label = { Text(s?.label ?: "不显示") },
                                    )
                                }
                            }
                        }
                    },
                )
            }
            if (inTeam) {
                item { DisplaySwitch("组员位置", null, display.team) { v -> onDisplay { it.copy(team = v) } } }
                item { DisplaySwitch("只看我的任务", "只显示指派给我、还没完成的记录", display.onlyMyTasks) { v -> onDisplay { it.copy(onlyMyTasks = v) } } }
            }
            overlaySection(overlays, overlayActions)
            item { Text("底图", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            items(base.sources, key = { it.id }) { s ->
                val locked = s.id in base.locked
                ListItem(
                    headlineContent = { Text(s.name) },
                    supportingContent = {
                        val note = listOfNotNull(
                            s.group.ifBlank { null },
                            if (s.custom && !s.offline) "自定义" else null,
                            if (locked) "需要先在设置里填写 Key" else null,
                        ).joinToString(" · ")
                        if (note.isNotEmpty()) Text(note)
                    },
                    leadingContent = {
                        Icon(if (s.id == base.chosenId) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null)
                    },
                    trailingContent = { if (locked) Icon(Icons.Default.Lock, null) },
                    modifier = Modifier.clickable { onChoose(s.id) },
                )
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextButton(onClick = onDownload) { Text("下载当前范围") }
                    TextButton(onClick = onManage) { Text("管理 Key 和图源") }
                }
            }
        }
    }
}

/**
 * Asks for a position typed or pasted from a document, in any common
 * notation, and shows how it was read before going there.
 */
@Composable
internal fun GoToDialog(
    format: org.geocrew.core.model.CoordinateFormat,
    onGo: (org.geocrew.core.model.LngLat, addPoint: Boolean) -> Unit,
    /** Show a map sheet typed by its code, e.g. J50E004003. */
    onSheet: (org.geocrew.core.model.MapSheet.Sheet) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val parsed = androidx.compose.runtime.remember(text) { org.geocrew.core.model.CoordinateParser.parse(text) }
    val sheet = androidx.compose.runtime.remember(text) { if (parsed == null) org.geocrew.core.model.MapSheet.parse(text) else null }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("前往坐标") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { androidx.compose.material3.Text("经纬度或图幅号") },
                    placeholder = { androidx.compose.material3.Text("116.391, 39.907 或 N39°54′25″ E116°23′28″") },
                    minLines = 2,
                )
                androidx.compose.material3.Text(
                    when {
                        text.isBlank() -> "可粘贴十进制度或度分秒，带不带 N/E、经度/纬度 都可以；也可以输入图幅号，如 J50E004003。"
                        sheet != null -> "图幅：${sheet.scale.label} ${sheet.code}"
                        parsed == null -> "读不出坐标或图幅号，请检查格式"
                        else -> "读作：" + org.geocrew.core.model.CoordinateFormatter.format(parsed, format)
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = if (text.isNotBlank() && parsed == null && sheet == null) {
                        androidx.compose.material3.MaterialTheme.colorScheme.error
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                if (sheet != null) {
                    androidx.compose.material3.TextButton(onClick = { onSheet(sheet) }) { androidx.compose.material3.Text("前往图幅") }
                } else {
                    androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let { onGo(it, true) } }) {
                        androidx.compose.material3.Text("前往并标点")
                    }
                    androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let { onGo(it, false) } }) {
                        androidx.compose.material3.Text("前往")
                    }
                }
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("取消") } },
    )
}

@Composable
private fun DisplaySwitch(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = hint?.let { { Text(it) } },
        trailingContent = { androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
