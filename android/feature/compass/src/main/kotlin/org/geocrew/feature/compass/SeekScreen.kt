package org.geocrew.feature.compass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.SeekGuide
import org.geocrew.core.ui.SectionHeader
import java.util.Locale

/**
 * Finding a point in the field: the arrow turns with the phone towards
 * the target, with distance, bearing and height difference. Reaching it
 * offers to open the record (to tick off an assigned task, say).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeekScreen(onBack: () -> Unit, onOpenRecord: (String) -> Unit, viewModel: SeekViewModel = hiltViewModel()) {
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val target by viewModel.target.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val heading by viewModel.heading.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(target?.title?.let { "前往 $it" } ?: "寻点") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        val t = target
        if (t == null || choosing) {
            TargetList(
                targets = targets,
                modifier = Modifier.padding(padding),
                onPick = { viewModel.choose(it); choosing = false },
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val g = guide
            when {
                !viewModel.hasLocationPermission -> Text("没有定位权限，请先在地图页允许定位", color = MaterialTheme.colorScheme.error)
                fix == null -> Text("正在定位…")
                g == null -> Unit
                else -> {
                    // With a compass the arrow is relative to the phone; without, north is up.
                    val angle = g.turn ?: g.bearing
                    Arrow(angle.toFloat(), arrived = g.arrived, modifier = Modifier.size(220.dp))
                    Text(
                        if (g.arrived) "已到达" else CoordinateFormatter.distance(g.distance),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        buildString {
                            append("方位 %.0f°（%s）".format(Locale.ROOT, g.bearing, SeekGuide.direction(g.bearing)))
                            g.turn?.let { if (!g.arrived) append(" · ").append(SeekGuide.turnLabel(it)) }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    g.climb?.let { c ->
                        Text(if (c >= 0) "目标高出 %.0f m".format(Locale.ROOT, c) else "目标低 %.0f m".format(Locale.ROOT, -c))
                    }
                    Text(
                        "定位精度约 %s，到达半径 %.0f m".format(
                            Locale.ROOT,
                            fix?.accuracy?.let { "%.0f m".format(Locale.ROOT, it) } ?: "未知",
                            g.arrivalRadius,
                        ) + if (heading == null) "；手机没有罗盘，箭头按正北朝上" else "",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            t.id?.let { id -> OutlinedButton(onClick = { onOpenRecord(id) }) { Text(if (guide?.arrived == true) "打开记录" else "查看记录") } }
            OutlinedButton(onClick = { choosing = true }) { Text("换一个点") }
        }
    }
}

@Composable
private fun TargetList(targets: List<SeekTarget>, modifier: Modifier, onPick: (SeekTarget) -> Unit) {
    LazyColumn(modifier.fillMaxSize()) {
        if (targets.isEmpty()) {
            item { Text("当前工程还没有点记录", modifier = Modifier.padding(16.dp)) }
        }
        val mine = targets.filter { it.mine }
        if (mine.isNotEmpty()) item { SectionHeader("我的未完成任务") }
        items(mine, key = { "m" + it.id }) { TargetRow(it, onPick) }
        val rest = targets.filter { !it.mine }
        if (mine.isNotEmpty() && rest.isNotEmpty()) item { SectionHeader("其他点") }
        items(rest, key = { "o" + it.id }) { TargetRow(it, onPick) }
    }
}

@Composable
private fun TargetRow(t: SeekTarget, onPick: (SeekTarget) -> Unit) {
    ListItem(
        headlineContent = { Text(t.title) },
        supportingContent = { Text(CoordinateFormatter.format(t.position, org.geocrew.core.model.CoordinateFormat.DECIMAL)) },
        leadingContent = { Icon(if (t.mine) Icons.Default.Flag else Icons.Default.Place, null) },
        modifier = Modifier.fillMaxWidth().clickable { onPick(t) },
    )
}

@Composable
private fun Arrow(angle: Float, arrived: Boolean, modifier: Modifier) {
    val color = if (arrived) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        drawCircle(ring, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        if (arrived) {
            drawCircle(color, radius = r * 0.35f, center = c)
            return@Canvas
        }
        rotate(angle, pivot = c) {
            val p = Path().apply {
                moveTo(c.x, c.y - r * 0.85f)
                lineTo(c.x + r * 0.38f, c.y + r * 0.55f)
                lineTo(c.x, c.y + r * 0.3f)
                lineTo(c.x - r * 0.38f, c.y + r * 0.55f)
                close()
            }
            drawPath(p, color)
        }
    }
}
