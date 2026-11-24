package org.geocrew.feature.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.geocrew.core.model.ImageOverlay

/** What the layer list can do with image overlays; ids are [ImageOverlay.id]. */
internal class OverlayActions(
    val add: () -> Unit,
    val change: (String, (ImageOverlay) -> ImageOverlay) -> Unit,
    /** Lay the overlay over the current view again. */
    val fit: (String) -> Unit,
    /** Line the overlay up by control points on the map. */
    val align: (String) -> Unit,
    val move: (String, up: Boolean) -> Unit,
    val remove: (String) -> Unit,
)

/** The "叠加图片" part of the layer sheet: one row per overlay, top of the map first. */
internal fun LazyListScope.overlaySection(list: List<OverlayItem>, actions: OverlayActions) {
    item { Text("叠加图片", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
    // Shown top first, as the eye reads a stack of transparencies.
    items(list.asReversed(), key = { "ovl-" + it.overlay.id }) { item -> OverlayRow(item, list.size, list.indexOf(item), actions) }
    item {
        ListItem(
            headlineContent = { Text("添加图片") },
            supportingContent = { Text("扫描的地质图、草图等（JPG/PNG）。有 world 文件（.jgw/.pgw/.tfw/.wld）就一起选，会自动定位；没有就先铺在当前视野里") },
            leadingContent = { Icon(Icons.Default.AddPhotoAlternate, null) },
            modifier = Modifier.clickable(onClick = actions.add),
        )
    }
}

@androidx.compose.runtime.Composable
private fun OverlayRow(item: OverlayItem, count: Int, index: Int, actions: OverlayActions) {
    val o = item.overlay
    var opacity by remember(o.id, o.opacity) { mutableFloatStateOf(o.opacity) }
    var confirmRemove by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(o.name) },
        supportingContent = {
            Column {
                Text(
                    when {
                        item.missing -> "图片文件不在这台设备上，请删掉后重新添加"
                        o.controlPoints > 0 -> "按 ${o.controlPoints} 对控制点对齐"
                        o.placement == ImageOverlay.Placement.WORLD_FILE -> "按 world 文件定位"
                        else -> "按视野铺放，位置只是大致的"
                    },
                )
                if (!item.missing) {
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it },
                        onValueChangeFinished = { actions.change(o.id) { it.copy(opacity = opacity) } },
                        valueRange = 0.1f..1f,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!item.missing) TextButton(onClick = { actions.align(o.id) }) { Text("对齐") }
                    if (!item.missing) TextButton(onClick = { actions.fit(o.id) }) { Text("铺到视野") }
                    if (index < count - 1) TextButton(onClick = { actions.move(o.id, true) }) { Text("上移") }
                    if (index > 0) TextButton(onClick = { actions.move(o.id, false) }) { Text("下移") }
                    TextButton(onClick = { confirmRemove = true }) { Text("删除") }
                }
            }
        },
        trailingContent = {
            if (!item.missing) Switch(checked = o.visible, onCheckedChange = { v -> actions.change(o.id) { it.copy(visible = v) } })
        },
    )
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("删除「${o.name}」？") },
            text = { Text("只从地图上拿掉这张图片，记录不受影响。") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; actions.remove(o.id) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } },
        )
    }
}
