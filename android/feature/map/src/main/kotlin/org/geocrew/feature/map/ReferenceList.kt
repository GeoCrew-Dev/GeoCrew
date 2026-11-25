package org.geocrew.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.geocrew.core.model.ReferenceLayer

/** What the layer list can do with reference layers; ids are [ReferenceLayer.id]. */
internal class ReferenceActions(
    val change: (String, (ReferenceLayer) -> ReferenceLayer) -> Unit,
    /** Move the map to the layer's extent. */
    val fit: (String) -> Unit,
    val remove: (String) -> Unit,
)

/**
 * The "参考图层" part of the layer sheet, shown when the project has any.
 * New ones come from 导入, where a file can be brought in as a reference layer.
 */
internal fun LazyListScope.referenceSection(list: List<ReferenceLayer>, actions: ReferenceActions) {
    if (list.isEmpty()) return
    item { Text("参考图层", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
    items(list.asReversed(), key = { "ref-" + it.id }) { ReferenceRow(it, actions) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceRow(l: ReferenceLayer, actions: ReferenceActions) {
    var opacity by remember(l.id, l.opacity) { mutableFloatStateOf(l.opacity) }
    var confirmRemove by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(l.name) },
        supportingContent = {
            Column {
                Text("${l.count} 个图形" + (l.colorBy?.let { " · 按「$it」着色" } ?: "") + " · 只在这台设备上")
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { actions.change(l.id) { it.copy(opacity = opacity) } },
                    valueRange = 0.1f..1f,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { actions.fit(l.id) }) { Text("定位") }
                    TextButton(onClick = { confirmRemove = true }) { Text("删除") }
                }
            }
        },
        trailingContent = { Switch(checked = l.visible, onCheckedChange = { v -> actions.change(l.id) { it.copy(visible = v) } }) },
    )
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("删除「${l.name}」？") },
            text = { Text("只从这台设备上拿掉这个参考图层，记录不受影响。") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; actions.remove(l.id) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } },
        )
    }
}

/** The attributes of a tapped reference shape. */
@Composable
internal fun ReferenceTapDialog(tap: ReferenceTap, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tap.layer) },
        text = {
            if (tap.rows.isEmpty()) Text("这个图形没有属性。")
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(tap.sorted) { (k, v) ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
