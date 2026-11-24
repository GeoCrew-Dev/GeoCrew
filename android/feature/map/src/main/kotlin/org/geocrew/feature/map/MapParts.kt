package org.geocrew.feature.map

import androidx.compose.foundation.clickable
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
) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            val v = mode.vertices
            val measure = when {
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
                else -> "点地图或用十字中心添加节点"
            }
            val title = when {
                mode.measureOnly -> if (mode.closed) "测面积" else "测距离"
                mode.closed -> "画面"
                else -> "画线"
            }
            Text("$title：${v.size} 个节点 · $measure", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Tool(Icons.Default.Place, "中心加节点", onClick = onAddCenter)
                Tool(Icons.AutoMirrored.Filled.Undo, "撤销", enabled = v.isNotEmpty(), onClick = onUndo)
                if (mode.measureOnly) {
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
internal fun SelectedFeatureCard(f: GeoFeature, format: CoordinateFormat, onOpen: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(KindUi.icon(f.kind), null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(f.title ?: KindUi.label(f.kind), style = MaterialTheme.typography.titleMedium)
                val g = f.geometry
                val detail = when (g) {
                    is Geometry.Point -> CoordinateFormatter.format(g.position, format)
                    is Geometry.Polygon, is Geometry.MultiPolygon -> "面积 " + CoordinateFormatter.area(g.areaSquareMeters())
                    null -> KindUi.label(f.kind)
                    else -> "长度 " + CoordinateFormatter.distance(g.lengthMeters())
                }
                Text(KindUi.label(f.kind) + " · " + detail, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayerSheet(base: BaseMapUi, onChoose: (String) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding()) {
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
                TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("管理 Key 和自定义图源")
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
    onDismiss: () -> Unit,
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val parsed = androidx.compose.runtime.remember(text) { org.geocrew.core.model.CoordinateParser.parse(text) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("前往坐标") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { androidx.compose.material3.Text("经纬度") },
                    placeholder = { androidx.compose.material3.Text("116.391, 39.907 或 N39°54′25″ E116°23′28″") },
                    minLines = 2,
                )
                androidx.compose.material3.Text(
                    when {
                        text.isBlank() -> "可粘贴十进制度或度分秒，带不带 N/E、经度/纬度 都可以。"
                        parsed == null -> "读不出坐标，请检查格式"
                        else -> "读作：" + org.geocrew.core.model.CoordinateFormatter.format(parsed, format)
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = if (text.isNotBlank() && parsed == null) {
                        androidx.compose.material3.MaterialTheme.colorScheme.error
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let { onGo(it, true) } }) {
                    androidx.compose.material3.Text("前往并标点")
                }
                androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let { onGo(it, false) } }) {
                    androidx.compose.material3.Text("前往")
                }
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("取消") } },
    )
}
