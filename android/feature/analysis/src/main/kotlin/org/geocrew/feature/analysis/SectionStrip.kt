package org.geocrew.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import org.geocrew.core.gis.MapSectionTool
import org.geocrew.core.gis.OutputProps
import org.geocrew.core.gis.SectionTool
import org.geocrew.core.gis.ToolOutput
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.double
import org.geocrew.core.model.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A plan strip of a section tool's result: units as coloured stretches along
 * the line, and each cut or projected attitude as a tick with its apparent
 * dip drawn below, start on the left. Any tool whose output carries
 * `from_m`/`to_m` stretches or `chainage_m` points gets one.
 */
internal data class Strip(
    val length: Double,
    val bands: List<Band>,
    val ticks: List<Tick>,
    /** Ground heights along the section, chainage to metres, when the tool sampled terrain. */
    val ground: List<Pair<Double, Double>>? = null,
) {
    /** Ground height at chainage [c], linearly between samples. */
    fun groundAt(c: Double): Double? {
        val g = ground ?: return null
        if (c <= g.first().first) return g.first().second
        if (c >= g.last().first) return g.last().second
        val i = g.indexOfFirst { it.first >= c }.coerceAtLeast(1)
        val (c0, h0) = g[i - 1]
        val (c1, h1) = g[i]
        return if (c1 > c0) h0 + (h1 - h0) * (c - c0) / (c1 - c0) else h0
    }

    /**
     * Vertical exaggeration that lets the relief fill at most [fill] of a
     * canvas [aspect] (height / width) tall: a round factor of 1 or more,
     * or less than 1 for very steep ground.
     */
    fun exaggeration(aspect: Double, fill: Double = 0.55): Double {
        val g = ground ?: return 1.0
        val relief = (g.maxOf { it.second } - g.minOf { it.second }).coerceAtLeast(1.0)
        val atOne = relief / length / aspect // fraction of the height the relief takes at 1:1
        return VE.lastOrNull { atOne * it <= fill } ?: (fill / atOne)
    }

    data class Band(val from: Double, val to: Double, val color: String)
    data class Tick(val at: Double, val dip: Double?, val color: String)

    companion object {
        fun of(out: ToolOutput): Strip? {
            val bands = out.features.filter { it.kind == FeatureKind.LINE }.mapNotNull { f ->
                val a = f.properties.double(MapSectionTool.FROM_M) ?: return@mapNotNull null
                val b = f.properties.double(MapSectionTool.TO_M) ?: return@mapNotNull null
                Band(a, b, f.properties.string(OutputProps.STROKE) ?: "#9E9E9E")
            }
            val ticks = out.features.filter { it.kind == FeatureKind.POINT }.mapNotNull { f ->
                val c = f.properties.double(MapSectionTool.CHAINAGE_M) ?: return@mapNotNull null
                Tick(c, f.properties.double(SectionTool.APPARENT_DIP), f.properties.string("marker-color") ?: "#212121")
            }
            if (bands.isEmpty() && ticks.isEmpty()) return null
            val whole = out.features.firstNotNullOfOrNull { it.properties.double(SectionTool.SECTION_LENGTH_M) } ?: 0.0
            val length = maxOf(whole, bands.maxOfOrNull { it.to } ?: 0.0, ticks.maxOfOrNull { it.at } ?: 0.0)
            val ground = out.features.firstOrNull { it.properties.string(MapSectionTool.ROLE) == MapSectionTool.ROLE_TERRAIN }
                ?.let { it.properties[MapSectionTool.PROFILE] as? JsonArray }
                ?.mapNotNull { row ->
                    val pair = row as? JsonArray ?: return@mapNotNull null
                    val c = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val h = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    c to h
                }
                ?.takeIf { it.size >= 2 }
            return if (length > 0) Strip(length, bands, ticks, ground) else null
        }

        private val VE = listOf(1.0, 2.0, 3.0, 5.0, 10.0, 20.0, 50.0, 100.0)
    }
}

@Composable
internal fun SectionStrip(out: ToolOutput) {
    val strip = remember(out) { Strip.of(out) } ?: return
    if (strip.ground != null) {
        SectionProfile(strip)
        return
    }
    val outline = MaterialTheme.colorScheme.outline
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("剖面示意（左为起点）", style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val bar = 14.dp.toPx()
            val top = 4.dp.toPx()
            fun x(m: Double) = (m / strip.length * size.width).toFloat()
            drawRect(outline, Offset(0f, top), Size(size.width, bar), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            strip.bands.forEach { b -> drawRect(color(b.color), Offset(x(b.from), top), Size((x(b.to) - x(b.from)).coerceAtLeast(1f), bar)) }
            val reach = size.height - top - bar - 4.dp.toPx()
            strip.ticks.forEach { t ->
                val cx = x(t.at)
                val c = color(t.color)
                drawLine(c, Offset(cx, top - 2.dp.toPx()), Offset(cx, top + bar + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                t.dip?.let { d ->
                    // The plane's trace in the section: down and toward the end for positive dips.
                    val r = abs(d) * PI / 180
                    val dx = (cos(r) * reach).toFloat() * if (d < 0) -1 else 1
                    val dy = (sin(r) * reach).toFloat()
                    drawLine(c, Offset(cx, top + bar), Offset(cx + dx, top + bar + dy), strokeWidth = 1.5.dp.toPx())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.bodySmall)
            Text(CoordinateFormatter.distance(strip.length), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The section drawn as a cross-section sketch: the ground line from the DEM,
 * the mapped units as a coloured skin along it, and each contact or fault
 * going down from where it crops out at its apparent dip (exaggerated with
 * the vertical scale, so the angles stay true to the drawing).
 */
@Composable
private fun SectionProfile(strip: Strip) {
    val ground = strip.ground ?: return
    val line = Color(0xFF6D4C41)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val ve = strip.exaggeration(PROFILE_HEIGHT_DP / maxWidth.value.coerceAtLeast(1f).toDouble())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(PROFILE_HEIGHT_DP.dp)) {
            val w = size.width
            val h = size.height
            val metresPerPx = strip.length / w
            val hi = ground.maxOf { it.second }
            val lo = ground.minOf { it.second }
            // Ground sits in the upper part, leaving room below for the dips.
            val top = hi + (h * 0.12) * metresPerPx / ve
            fun x(c: Double) = (c / strip.length * w).toFloat()
            fun y(z: Double) = ((top - z) * ve / metresPerPx).toFloat()
            val skin = 7.dp.toPx()

            strip.bands.forEach { b ->
                val pts = buildList {
                    add(b.from to strip.groundAt(b.from)!!)
                    ground.filter { it.first > b.from && it.first < b.to }.forEach { add(it) }
                    add(b.to to strip.groundAt(b.to)!!)
                }
                val path = Path().apply {
                    moveTo(x(pts[0].first), y(pts[0].second))
                    pts.drop(1).forEach { lineTo(x(it.first), y(it.second)) }
                    pts.asReversed().forEach { lineTo(x(it.first), y(it.second) + skin) }
                    close()
                }
                drawPath(path, color(b.color))
            }
            val g = Path().apply {
                moveTo(x(ground[0].first), y(ground[0].second))
                ground.drop(1).forEach { lineTo(x(it.first), y(it.second)) }
            }
            drawPath(g, line, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))

            val reach = 48.dp.toPx()
            strip.ticks.forEach { t ->
                val cx = x(t.at)
                val cy = y(strip.groundAt(t.at)!!)
                val c = color(t.color)
                drawLine(c, Offset(cx, cy - 6.dp.toPx()), Offset(cx, cy), strokeWidth = 2.dp.toPx())
                t.dip?.let { d ->
                    val a = if (abs(d) >= 89.9) PI / 2 else atan(ve * tan(abs(d) * PI / 180))
                    val dx = (cos(a) * reach).toFloat() * if (d < 0) -1 else 1
                    val dy = (sin(a) * reach).toFloat()
                    drawLine(c, Offset(cx, cy), Offset(cx + dx, cy + dy), strokeWidth = 1.5.dp.toPx())
                }
            }
            if (lo < hi) {
                // Height marks at the top and bottom of the ground.
                drawLine(line.copy(alpha = 0.4f), Offset(0f, y(hi)), Offset(6.dp.toPx(), y(hi)), 1.dp.toPx())
                drawLine(line.copy(alpha = 0.4f), Offset(0f, y(lo)), Offset(6.dp.toPx(), y(lo)), 1.dp.toPx())
            }
        }
        val hi = ground.maxOf { it.second }
        val lo = ground.minOf { it.second }
        Text(
            "地形 ${"%.0f".format(lo)}–${"%.0f".format(hi)} m · " +
                (if (ve > 1.05) "垂直放大 ×${"%.0f".format(ve)}" else if (ve < 0.95) "垂直压缩 ×${"%.2f".format(ve)}" else "垂直比例 1:1") +
                " · 左为起点",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.bodySmall)
            Text(CoordinateFormatter.distance(strip.length), style = MaterialTheme.typography.bodySmall)
        }
    }
    }
}

private const val PROFILE_HEIGHT_DP = 170.0

private fun color(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
