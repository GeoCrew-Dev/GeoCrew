package org.geocrew.feature.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.geocrew.core.data.repo.BasemapDownload
import org.geocrew.core.data.repo.PausedDownload
import org.geocrew.core.model.Bounds
import org.geocrew.core.model.tiles.BulkDownload
import org.geocrew.core.model.tiles.TilePlan
import org.geocrew.core.model.tiles.TileSource
import kotlin.math.floor

/**
 * Picks the zoom levels for keeping the area on screen offline and shows what
 * that costs (requests, size) before anything is fetched. The deepest level
 * is capped so one download stays within [TilePlan.MAX_REQUESTS].
 */
@Composable
internal fun OfflineDownloadDialog(
    source: TileSource,
    bounds: Bounds,
    zoom: Double,
    onStart: (TilePlan, String) -> String?,
    onDismiss: () -> Unit,
    /** The map sheet being saved, e.g. "1:5万 J50E004003"; null for the current view. */
    sheet: String? = null,
) {
    val policy = BulkDownload.of(source)
    val layers = source.layers.size
    val sourceMax = minOf(source.maxZoom, source.layers.maxOfOrNull { it.maxZoom } ?: source.maxZoom)
    val first = floor(zoom).toInt().coerceIn(0, sourceMax)
    val deepest = TilePlan.deepestWithin(bounds, first, sourceMax, layers)
    var range by remember { mutableStateOf(first.toFloat()..(deepest ?: first).coerceAtMost(first + 4).toFloat()) }
    var name by remember { mutableStateOf(source.name + (sheet?.let { "（$it）" } ?: "（离线）")) }
    var error by remember { mutableStateOf<String?>(null) }
    val plan = TilePlan(bounds, range.start.toInt(), range.endInclusive.toInt(), layers)
    val fits = plan.requests <= TilePlan.MAX_REQUESTS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sheet?.let { "下载图幅 $it" } ?: "下载当前范围") },
        text = {
            Column {
                when (policy) {
                    is BulkDownload.Refused -> Text(policy.reason)
                    is BulkDownload.Allowed -> {
                        Text("把屏幕上的这片 ${source.name} 存到手机里，没信号时也能看。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("缩放级别 ${plan.minZoom}–${plan.maxZoom}（现在是 ${first} 级）", style = MaterialTheme.typography.labelLarge)
                        RangeSlider(
                            value = range,
                            onValueChange = { r -> range = floor(r.start)..floor(r.endInclusive) },
                            valueRange = 0f..sourceMax.toFloat(),
                            steps = (sourceMax - 1).coerceAtLeast(0),
                        )
                        Text(
                            "约 ${plan.requests} 张瓦片、${formatMb(plan.estimatedBytes)}" +
                                if (fits) "" else "，超过一次 ${TilePlan.MAX_REQUESTS} 张的上限，请减少级别或缩小范围",
                            color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(policy.note, style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            if (policy is BulkDownload.Allowed) {
                TextButton(enabled = fits, onClick = {
                    val e = onStart(plan, name)
                    if (e == null) onDismiss() else error = e
                }) { Text("开始下载") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (policy is BulkDownload.Allowed) "取消" else "知道了") } },
    )
}

/** Progress, then the outcome, of the running offline download. */
@Composable
internal fun DownloadCard(
    d: BasemapDownload,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onResume: (String) -> Unit,
    onUse: (TileSource) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            val text = when (d.state) {
                BasemapDownload.State.RUNNING -> "正在下载「${d.name}」${d.done} / ${d.total}"
                BasemapDownload.State.DONE -> "「${d.name}」已下载" + if (d.missing > 0) "，${d.missing} 张没有内容或下载失败" else ""
                BasemapDownload.State.FAILED -> d.message ?: "下载失败"
                BasemapDownload.State.CANCELLED -> "已取消下载「${d.name}」"
                BasemapDownload.State.PAUSED -> "「${d.name}」暂停在 ${d.done} / ${d.total}" + (d.message?.let { "：$it" } ?: "")
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (d.state == BasemapDownload.State.RUNNING || d.state == BasemapDownload.State.PAUSED) {
                LinearProgressIndicator(
                    progress = { if (d.total == 0L) 0f else d.done.toFloat() / d.total },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 6.dp),
                )
            }
            Row {
                when (d.state) {
                    BasemapDownload.State.RUNNING -> {
                        TextButton(onClick = onPause) { Text("暂停") }
                        TextButton(onClick = onCancel) { Text("取消并删除") }
                    }
                    BasemapDownload.State.DONE -> {
                        d.source?.let { s -> TextButton(onClick = { onUse(s); onDismiss() }) { Text("换成这张底图") } }
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                    BasemapDownload.State.PAUSED -> {
                        TextButton(onClick = { onDismiss(); onResume(d.id) }) { Text("继续") }
                        TextButton(onClick = onDismiss) { Text("稍后") }
                    }
                    else -> TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * Downloads that stopped part way earlier (the app was closed, the network
 * went): go on with one, or delete what it fetched.
 */
@Composable
internal fun PausedDownloadsCard(list: List<PausedDownload>, onResume: (String) -> Unit, onDiscard: (String) -> Unit) {
    var discarding by remember { mutableStateOf<PausedDownload?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            for (p in list.take(3)) {
                val pct = if (p.total == 0L) 0 else (p.done * 100 / p.total)
                Text("离线底图「${p.name}」下载到约 $pct% 停下了", style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { onResume(p.id) }) { Text("继续下载") }
                    TextButton(onClick = { discarding = p }) { Text("删除") }
                }
            }
            if (list.size > 3) {
                Text("还有 ${list.size - 3} 个没下完", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
    discarding?.let { p ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text("删除没下完的底图？") },
            text = { Text("「${p.name}」已下载的瓦片会一起删掉。") },
            confirmButton = { TextButton(onClick = { onDiscard(p.id); discarding = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text("取消") } },
        )
    }
}

private fun formatMb(bytes: Long): String = if (bytes < 1024L * 1024) "不到 1 MB" else "约 ${bytes / (1024L * 1024)} MB"
