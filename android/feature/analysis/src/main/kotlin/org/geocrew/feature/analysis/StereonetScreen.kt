package org.geocrew.feature.analysis

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.gis.FisherStats
import org.geocrew.core.gis.Lineation
import org.geocrew.core.gis.NetPoint
import org.geocrew.core.gis.Rose
import org.geocrew.core.gis.Stereonet
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.Props
import org.geocrew.core.model.string
import javax.inject.Inject

enum class NetDisplay(val label: String) { POLES("极点"), PLANES("大圆"), BOTH("两者"), DENSITY("等密图"), ROSE("玫瑰图") }

data class StereonetUi(
    val attitudes: List<Attitude> = emptyList(),
    /** Lithology values present, for filtering; "" stands for records without one. */
    val groups: List<String> = emptyList(),
    val group: String? = null,
    val display: NetDisplay = NetDisplay.POLES,
    val stats: FisherStats? = null,
    /** Pole density contours, worked out only while [NetDisplay.DENSITY] is shown. */
    val density: Stereonet.Density? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StereonetViewModel @Inject constructor(
    projects: ProjectRepository,
    features: FeatureRepository,
) : ViewModel() {
    private val group = MutableStateFlow<String?>(null)
    private val display = MutableStateFlow(NetDisplay.POLES)

    private val readings = projects.current.map { it.id }.distinctUntilChanged()
        .flatMapLatest { features.observeProject(it) }
        .map { list ->
            list.mapNotNull { f -> Attitude.fromProperties(f.properties)?.let { (f.properties.string(Props.LITHOLOGY)?.trim().orEmpty()) to it } }
        }

    val ui: StateFlow<StereonetUi> = combine(readings, group, display) { all, g, d ->
        val chosen = all.filter { g == null || it.first == g }.map { it.second }
        StereonetUi(
            attitudes = chosen,
            groups = all.map { it.first }.distinct().sorted(),
            group = g,
            display = d,
            stats = Stereonet.fisher(chosen.map(Stereonet::pole)),
            density = if (d == NetDisplay.DENSITY) Stereonet.density(chosen.map(Stereonet::pole)) else null,
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, StereonetUi())

    fun setGroup(g: String?) { group.value = g }
    fun setDisplay(d: NetDisplay) { display.value = d }
}

/**
 * Lower-hemisphere equal-area net of every attitude in the open project,
 * with the Fisher mean of their poles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StereonetScreen(onBack: () -> Unit, viewModel: StereonetViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("赤平投影") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                NetDisplay.entries.forEachIndexed { i, d ->
                    SegmentedButton(
                        selected = ui.display == d,
                        onClick = { viewModel.setDisplay(d) },
                        shape = SegmentedButtonDefaults.itemShape(i, NetDisplay.entries.size),
                    ) { Text(d.label) }
                }
            }
            if (ui.groups.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = ui.group == null, onClick = { viewModel.setGroup(null) }, label = { Text("全部") })
                    ui.groups.forEach { g ->
                        FilterChip(selected = ui.group == g, onClick = { viewModel.setGroup(g) }, label = { Text(g.ifEmpty { "未填岩性" }) })
                    }
                }
            }
            if (ui.display == NetDisplay.ROSE) {
                RoseDiagram(ui, Modifier.fillMaxWidth().aspectRatio(1f))
                RoseSummary(ui)
            } else {
                Net(ui, Modifier.fillMaxWidth().aspectRatio(1f))
                Summary(ui)
            }
        }
    }
}

@Composable
private fun Summary(ui: StereonetUi) {
    val s = ui.stats
    if (s == null) {
        Text("当前工程还没有产状记录。用罗盘测量，或在记录详情里填写倾向和倾角。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("共 ${s.count} 条产状 · 下半球等面积投影（施密特网）", style = MaterialTheme.typography.bodyMedium)
        Text("平均产状 ${Stereonet.planeOfPole(s.mean).format()}", style = MaterialTheme.typography.titleMedium)
        Text(
            "平均极点 %03.0f°/%02.0f° · R̄ %.3f".format(s.mean.trend, s.mean.plunge, s.resultant) +
                (s.kappa?.let { " · k %.1f".format(it) } ?: "") +
                (s.alpha95?.let { " · α95 %.1f°".format(it) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.display == NetDisplay.DENSITY) {
            val d = ui.density
            Text(
                if (d == null) "至少要 3 条产状才能画等密图。"
                else "极点等密线 " + d.levels.joinToString("、") { (if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()) + "%" } + "（每 1% 面积），最大 %.1f%%".format(d.max),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.count < 5) {
            Text("读数较少，统计量仅供参考。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Grid lines of a Schmidt net every 10°, computed once. */
private val GRID: List<List<NetPoint>> by lazy {
    buildList {
        for (dip in 10..80 step 10) {
            add(Stereonet.greatCircle(Attitude.of(90.0, dip.toDouble())))
            add(Stereonet.greatCircle(Attitude.of(270.0, dip.toDouble())))
        }
        for (a in 10..80 step 10) {
            addAll(Stereonet.smallCircle(Lineation(0.0, 0.0), a.toDouble()))
            addAll(Stereonet.smallCircle(Lineation(180.0, 0.0), a.toDouble()))
        }
    }
}

@Composable
private fun Net(ui: StereonetUi, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val plane = MaterialTheme.colorScheme.primary
    val pole = MaterialTheme.colorScheme.tertiary
    val mean = MaterialTheme.colorScheme.error
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelMedium.copy(color = outline)
    val poles = remember(ui.attitudes) { ui.attitudes.map { Stereonet.project(Stereonet.pole(it)) } }
    val circles = remember(ui.attitudes) { ui.attitudes.map { Stereonet.greatCircle(it, steps = 60) } }

    Canvas(modifier.padding(16.dp)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        fun at(p: NetPoint) = Offset(c.x + p.x.toFloat() * r, c.y - p.y.toFloat() * r)
        fun line(points: List<NetPoint>, color: Color, width: Float) {
            if (points.size < 2) return
            val path = Path().apply {
                moveTo(at(points[0]).x, at(points[0]).y)
                points.drop(1).forEach { val o = at(it); lineTo(o.x, o.y) }
            }
            drawPath(path, color, style = Stroke(width))
        }

        GRID.forEach { line(it, grid, 1f) }
        drawCircle(outline, r, c, style = Stroke(2.dp.toPx()))
        drawLine(outline, Offset(c.x, c.y - 6.dp.toPx()), Offset(c.x, c.y + 6.dp.toPx()), 1.5f)
        drawLine(outline, Offset(c.x - 6.dp.toPx(), c.y), Offset(c.x + 6.dp.toPx(), c.y), 1.5f)
        val n = measurer.measure("N", label)
        drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y - r - n.size.height - 2.dp.toPx()))

        val density = ui.density
        if (ui.display == NetDisplay.DENSITY && density != null) {
            val rim = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
            clipPath(rim) {
                val top = (density.levels.size - 1).coerceAtLeast(1)
                density.lines.forEach { (level, pts) ->
                    val i = density.levels.indexOf(level).coerceAtLeast(0)
                    line(pts, plane.copy(alpha = 0.35f + 0.65f * i / top), (1f + i * 0.4f).dp.toPx())
                }
            }
            poles.forEach { drawCircle(pole.copy(alpha = 0.6f), 2.dp.toPx(), at(it)) }
        }
        if (ui.display == NetDisplay.PLANES || ui.display == NetDisplay.BOTH) circles.forEach { line(it, plane.copy(alpha = 0.7f), 1.5.dp.toPx()) }
        if (ui.display == NetDisplay.POLES || ui.display == NetDisplay.BOTH) poles.forEach { drawCircle(pole, 3.5.dp.toPx(), at(it)) }
        ui.stats?.let { s -> meanMarker(at(Stereonet.project(s.mean)), mean) }
    }
}

private fun DrawScope.meanMarker(o: Offset, color: Color) {
    val d = 7.dp.toPx()
    drawLine(color, Offset(o.x - d, o.y - d), Offset(o.x + d, o.y + d), 2.5.dp.toPx())
    drawLine(color, Offset(o.x - d, o.y + d), Offset(o.x + d, o.y - d), 2.5.dp.toPx())
}

/** 10° sectors give a readable rose for the tens to hundreds of readings a field day yields. */
private const val ROSE_BIN = 10

@Composable
private fun RoseSummary(ui: StereonetUi) {
    val m = remember(ui.attitudes) { Rose.axialMean(ui.attitudes.map { it.strike }) }
    if (m == null) {
        Text("当前工程还没有产状记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("共 ${m.count} 条走向 · ${ROSE_BIN}° 分组 · 花瓣面积与条数成正比", style = MaterialTheme.typography.bodyMedium)
        Text("优势走向 %03.0f°–%03.0f°".format(m.direction, m.direction + 180), style = MaterialTheme.typography.titleMedium)
        Text(
            "集中程度 R̄ %.2f".format(m.resultant) + when {
                m.resultant > 0.7 -> "（走向集中）"
                m.resultant < 0.3 -> "（走向分散或存在多组，均值意义不大）"
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Strike rose: each sector is drawn twice, 180° apart, with radius ∝ √count (equal area). */
@Composable
private fun RoseDiagram(ui: StereonetUi, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val petal = MaterialTheme.colorScheme.primary
    val mean = MaterialTheme.colorScheme.error
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelMedium.copy(color = outline)
    val bins = remember(ui.attitudes) { Rose.axialBins(ui.attitudes.map { it.strike }, ROSE_BIN) }
    val avg = remember(ui.attitudes) { Rose.axialMean(ui.attitudes.map { it.strike }) }
    val max = bins.maxOrNull()?.takeIf { it > 0 } ?: 1

    Canvas(modifier.padding(16.dp)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        for (k in 1..4) drawCircle(grid, r * kotlin.math.sqrt(k / 4f), c, style = Stroke(1f))
        for (a in 0 until 360 step 30) {
            val t = Math.toRadians(a.toDouble())
            drawLine(grid, c, Offset(c.x + r * kotlin.math.sin(t).toFloat(), c.y - r * kotlin.math.cos(t).toFloat()), 1f)
        }
        drawCircle(outline, r, c, style = Stroke(2.dp.toPx()))
        bins.forEachIndexed { i, n ->
            if (n == 0) return@forEachIndexed
            val rr = r * kotlin.math.sqrt(n.toFloat() / max)
            for (offset in listOf(0, 180)) {
                // Compose angles start at three o'clock; azimuths start at north.
                val start = (i * ROSE_BIN + offset - 90).toFloat()
                drawArc(petal.copy(alpha = 0.55f), start, ROSE_BIN.toFloat(), useCenter = true, topLeft = Offset(c.x - rr, c.y - rr), size = androidx.compose.ui.geometry.Size(2 * rr, 2 * rr))
                drawArc(petal, start, ROSE_BIN.toFloat(), useCenter = true, topLeft = Offset(c.x - rr, c.y - rr), size = androidx.compose.ui.geometry.Size(2 * rr, 2 * rr), style = Stroke(1.dp.toPx()))
            }
        }
        avg?.let { m ->
            val t = Math.toRadians(m.direction)
            val dx = (r * kotlin.math.sin(t)).toFloat()
            val dy = (r * kotlin.math.cos(t)).toFloat()
            drawLine(mean, Offset(c.x - dx, c.y + dy), Offset(c.x + dx, c.y - dy), 2.dp.toPx())
        }
        val n = measurer.measure("N", label)
        drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y - r - n.size.height - 2.dp.toPx()))
        val top = measurer.measure("$max", label.copy(color = grid.copy(alpha = 1f)))
        drawText(top, topLeft = Offset(c.x + 4.dp.toPx(), c.y - r + 2.dp.toPx()))
    }
}
