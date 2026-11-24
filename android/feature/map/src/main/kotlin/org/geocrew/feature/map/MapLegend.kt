package org.geocrew.feature.map

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.geocrew.core.model.Legend
import org.geocrew.core.model.LegendEntry

/**
 * The map's key for the project legend: the values on screen and their
 * colours. Collapses to one line when tapped, so it never hides much map.
 */
@Composable
internal fun MapLegend(title: String, entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(true) }
    Surface(
        modifier.widthIn(max = 200.dp).clickable { open = !open },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (open) "图例 · $title" else "图例 · $title（${entries.size}）", style = MaterialTheme.typography.labelMedium)
            if (open) {
                entries.take(MAX).forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(width = 16.dp, height = 10.dp).background(color(e.color), RoundedCornerShape(2.dp)))
                        Text(e.value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (entries.size > MAX) Text("另有 ${entries.size - MAX} 项", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val MAX = 8

private fun color(hex: String): Color = if (Legend.isColor(hex)) Color(("FF" + hex.drop(1)).toLong(16)) else Color.Gray
