package org.geocrew.feature.compass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassScreen(
    onBack: () -> Unit,
    /** Called with the id of the feature that received the measurement. */
    onSaved: (String) -> Unit,
    viewModel: CompassViewModel = hiltViewModel(),
) {
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val held by viewModel.held.collectAsStateWithLifecycle()
    val declination by viewModel.declination.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val heldLine by viewModel.heldLine.collectAsStateWithLifecycle()
    val lineType by viewModel.lineType.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    LaunchedEffect(saved) {
        saved?.let {
            viewModel.savedHandled()
            onSaved(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.targetId != null) "测量产状" else "罗盘 · 产状") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!viewModel.available) {
                Text("这台手机没有方向传感器，无法使用罗盘。", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            val r = reading
            CompassRose(azimuth = r?.azimuth ?: 0.0, modifier = Modifier.fillMaxWidth(0.75f).aspectRatio(1f))
            Text(
                r?.let { "%03.0f°".format(it.azimuth) } ?: "—",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("手机顶端所指方位（真北）", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = mode == CompassMode.PLANE, onClick = { viewModel.setMode(CompassMode.PLANE) },
                    label = { Text("面状：层面、节理、断层面") },
                )
                androidx.compose.material3.FilterChip(
                    selected = mode == CompassMode.LINE, onClick = { viewModel.setMode(CompassMode.LINE) },
                    label = { Text("线状：线理、擦痕、枢纽") },
                )
            }

            val plane = held ?: r?.plane
            if (mode == CompassMode.LINE) {
                LineCard(r, heldLine, lineType, viewModel::setLineType)
            } else Card(
                Modifier.fillMaxWidth(),
                colors = if (held != null) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (held != null) "已锁定的产状" else "实时产状（手机背面所贴平面）", style = MaterialTheme.typography.labelLarge)
                    Text(plane?.format() ?: "—", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    plane?.let { Text("倾向 %.0f° · 倾角 %.0f° · 走向 %.0f°".format(it.dipDirection, it.dip, it.strike)) }
                    if (held == null && r != null) {
                        Text(
                            if (r.spread < 2) "读数稳定，可以锁定" else "读数在晃动（±%.0f°），请把手机贴稳".format(r.spread),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.spread < 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (r?.unreliable == true) {
                Text("磁力计精度低：远离金属物体，手机在空中画几次「8」字校准。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text("磁偏角 %+.1f°（%s）".format(declination.first, declination.second), style = MaterialTheme.typography.bodySmall)
            if (viewModel.targetId == null) {
                Text(
                    fix?.let { "保存时会在当前位置（±%.0f m）新建地质点".format(it.accuracy ?: 0f) } ?: "还没有定位，保存的地质点将没有坐标",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val anyHeld = held != null || heldLine != null
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!anyHeld) {
                    Button(onClick = viewModel::hold, enabled = r != null) { Text("锁定读数") }
                } else {
                    OutlinedButton(onClick = viewModel::release) { Text("重新测量") }
                    Button(onClick = viewModel::save) { Text(if (viewModel.targetId != null) "写入记录" else "保存为地质点") }
                }
            }
            Text(
                if (mode == CompassMode.LINE) {
                    "测量方法：把手机长边（侧面）贴着线理或擦痕放稳，屏幕朝哪边都可以；读的是线朝下倾伏的那一端，读数稳定后锁定。"
                } else {
                    "测量方法：把手机背面紧贴岩层面（或贴在放于层面上的平板上），屏幕朝上，读数稳定后锁定。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CompassRose(azimuth: Double, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.onSurface
    val north = Color(0xFFD32F2F)
    val tick = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.95f
        drawCircle(ring, radius, c, style = Stroke(width = 3f))
        rotate(-azimuth.toFloat(), c) {
            for (deg in 0 until 360 step 10) {
                val a = Math.toRadians(deg.toDouble())
                val inner = if (deg % 90 == 0) radius * 0.8f else if (deg % 30 == 0) radius * 0.87f else radius * 0.92f
                val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                drawLine(if (deg == 0) north else tick, c + dir * inner, c + dir * radius, strokeWidth = if (deg % 90 == 0) 6f else 3f)
            }
            val needle = Path().apply {
                moveTo(c.x, c.y - radius * 0.7f)
                lineTo(c.x - radius * 0.08f, c.y)
                lineTo(c.x + radius * 0.08f, c.y)
                close()
            }
            drawPath(needle, north)
            val south = Path().apply {
                moveTo(c.x, c.y + radius * 0.7f)
                lineTo(c.x - radius * 0.08f, c.y)
                lineTo(c.x + radius * 0.08f, c.y)
                close()
            }
            drawPath(south, tick)
        }
        // Fixed marker: the phone's top.
        drawLine(ring, Offset(c.x, c.y - radius * 1.0f), Offset(c.x, c.y - radius * 0.85f), strokeWidth = 8f)
    }
}

/** Live or locked line along the phone's long edge, with the kind of structure to save it as. */
@Composable
private fun LineCard(r: CompassReading?, held: org.geocrew.core.model.Plunge?, type: String, onType: (String) -> Unit) {
    val line = held ?: r?.line
    Card(
        Modifier.fillMaxWidth(),
        colors = if (held != null) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (held != null) "已锁定的倾伏向∠倾伏角" else "实时：手机长边所贴的线", style = MaterialTheme.typography.labelLarge)
            Text(line?.format() ?: "—", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            line?.let { Text("倾伏向 %.0f° · 倾伏角 %.0f°".format(it.trend, it.plunge)) }
            if (held == null && r != null) {
                if (r.line == null) {
                    Text("这台手机读不出长边方向", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        if (r.lineSpread < 2) "读数稳定，可以锁定" else "读数在晃动（±%.0f°），请把手机贴稳".format(r.lineSpread),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.lineSpread < 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(
                Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LINEATION_TYPES.forEach { t ->
                    androidx.compose.material3.FilterChip(selected = type == t, onClick = { onType(t) }, label = { Text(t) })
                }
            }
        }
    }
}
