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
import androidx.compose.ui.unit.dp
import org.geocrew.core.gis.MapSectionTool
import org.geocrew.core.gis.OutputProps
import org.geocrew.core.gis.SectionTool
import org.geocrew.core.gis.ToolOutput
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.double
import org.geocrew.core.model.string
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A plan strip of a section tool's result: units as coloured stretches along
 * the line, and each cut or projected attitude as a tick with its apparent
 * dip drawn below, start on the left. Any tool whose output carries
 * `from_m`/`to_m` stretches or `chainage_m` points gets one.
 */
internal data class Strip(val length: Double, val bands: List<Band>, val ticks: List<Tick>) {
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
            return if (length > 0) Strip(length, bands, ticks) else null
        }
    }
}

@Composable
internal fun SectionStrip(out: ToolOutput) {
    val strip = remember(out) { Strip.of(out) } ?: return
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

private fun color(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
