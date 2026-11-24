package org.geocrew.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Legend
import org.geocrew.core.model.LegendEntry
import org.geocrew.core.model.LineStyle
import org.geocrew.core.model.lineStyle
import org.geocrew.core.ui.MapColors

/**
 * The map's key: the project legend's values on screen and their colours,
 * then the kinds of geological line drawn (contacts and faults, observed
 * and inferred). Collapses to one line when tapped, so it never hides much
 * map.
 */
@Composable
internal fun MapLegend(title: String, entries: List<LegendEntry>, lines: List<LineStyle>, modifier: Modifier = Modifier) {
    if (entries.isEmpty() && lines.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(true) }
    val heading = if (entries.isNotEmpty()) "图例 · $title" else "图例"
    Surface(
        modifier.widthIn(max = 200.dp).clickable { open = !open },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (open) heading else "$heading（${entries.size + lines.size}）", style = MaterialTheme.typography.labelMedium)
            if (open) {
                entries.take(MAX).forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(width = 16.dp, height = 10.dp).background(color(e.color), RoundedCornerShape(2.dp)))
                        Text(e.value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (entries.size > MAX) Text("另有 ${entries.size - MAX} 项", style = MaterialTheme.typography.bodySmall)
                lines.forEach { ls ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LineSample(ls)
                        Text(ls.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A short stroke drawn the way the map draws [style]. */
@Composable
private fun LineSample(style: LineStyle) {
    val c = color(if (style.fault) MapColors.FAULT else MapColors.CONTACT)
    Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
        val w = (if (style.fault) 3.dp else 2.dp).toPx()
        drawLine(
            c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = w,
            pathEffect = if (style.dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx())) else null,
        )
    }
}

/** The line styles among [features], in the legend's fixed order. */
internal fun lineStylesUsed(features: List<GeoFeature>): List<LineStyle> {
    val used = features.mapNotNullTo(HashSet()) { it.lineStyle }
    return LineStyle.entries.filter { it in used }
}

private const val MAX = 8

private fun color(hex: String): Color = if (Legend.isColor(hex)) Color(("FF" + hex.drop(1)).toLong(16)) else Color.Gray
