package org.geocrew.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import org.geocrew.core.map.MapCameraState
import org.geocrew.core.model.MapScale

/**
 * Scale bar for the map centre: a round length and the approximate scale
 * (约 1:2.5万). Web Mercator stretches with latitude, so it holds for the
 * middle of the screen.
 */
@Composable
internal fun ScaleBar(camera: MapCameraState, modifier: Modifier = Modifier) {
    val perDp = MapScale.metersPerDp(camera.center.lat, camera.zoom)
    val bar = MapScale.bar(perDp, MAX_DP)
    if (bar.meters <= 0) return
    val color = MaterialTheme.colorScheme.onSurface
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                MapScale.lengthLabel(bar.meters) + "  约 " + MapScale.ratioLabel(MapScale.denominator(perDp)),
                style = MaterialTheme.typography.labelSmall,
            )
            Canvas(Modifier.width(bar.dp.dp).height(6.dp)) {
                val y = size.height - 1.dp.toPx()
                val w = 2.dp.toPx()
                drawLine(color, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Square)
                drawLine(color, Offset(0f, 0f), Offset(0f, y), w)
                drawLine(color, Offset(size.width, 0f), Offset(size.width, y), w)
            }
        }
    }
}

private const val MAX_DP = 96.0
