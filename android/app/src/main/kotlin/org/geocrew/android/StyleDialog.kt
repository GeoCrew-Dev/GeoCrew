package org.geocrew.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

/**
 * How a layer is drawn (ADR 0006, v1: one symbol): its colour, a point's
 * radius and a line's width in dp, an area's fill opacity.
 */
data class LayerStyle(val color: Color, val size: Float = DEFAULT_SIZE, val width: Float = DEFAULT_WIDTH, val fill: Float = DEFAULT_FILL) {
    /** The colour as MapLibre and the project take it, #rrggbb. */
    val hex: String get() = "#%06x".format(color.toArgb() and 0xffffff)

    /** As layer.style takes it. */
    fun json(): JsonObject = buildJsonObject {
        put("color", hex)
        put("size", size.toDouble())
        put("width", width.toDouble())
        put("fill", fill.toDouble())
    }

    companion object {
        const val DEFAULT_SIZE = 6f
        const val DEFAULT_WIDTH = 3f
        const val DEFAULT_FILL = 0.25f
    }
}

/** A layer's style as project.current lists it, with [fallback] for its colour when it has none of its own. */
fun styleOf(layer: JsonObject, fallback: Color): LayerStyle {
    val style = layer["style"] as? JsonObject
    fun number(key: String) = (style?.get(key) as? JsonPrimitive)?.floatOrNull
    val color = (style?.get("color") as? JsonPrimitive)?.contentOrNull?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    return LayerStyle(
        color ?: fallback,
        number("size") ?: LayerStyle.DEFAULT_SIZE,
        number("width") ?: LayerStyle.DEFAULT_WIDTH,
        number("fill") ?: LayerStyle.DEFAULT_FILL,
    )
}

// Colours to choose from: strong enough to read on imagery, and told apart by colour-blind eyes as far as can be.
private val PALETTE = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
    0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E,
    0xFF6D4C41, 0xFF757575, 0xFF546E7A, 0xFF000000, 0xFFFFFFFF,
).map { Color(it) }

/**
 * A layer's style to change: a colour from the palette and, by what the layer
 * holds ([geometry], an OGC name), a point's size, a line's width, an area's
 * fill, with a preview. [onSave] gets the style; [onReset] goes back to the default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StyleDialog(layer: String, geometry: String?, style: LayerStyle, onDismiss: () -> Unit, onSave: (LayerStyle) -> Unit, onReset: () -> Unit) {
    var color by remember { mutableStateOf(style.color) }
    var size by remember { mutableFloatStateOf(style.size) }
    var width by remember { mutableFloatStateOf(style.width) }
    var fill by remember { mutableFloatStateOf(style.fill) }
    val point = geometry == null || geometry.contains("POINT")
    val area = holdsAreas(geometry)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Palette, null) },
        title = { Text(stringResource(R.string.style_title, layer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Preview(color, size, width, fill, point, area)
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in PALETTE) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(c)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c.toArgb() == color.toArgb()) Icon(Icons.Rounded.Check, null, tint = if (c.luminance() > 0.5f) Color.Black else Color.White)
                        }
                    }
                }
                if (point) StyleSlider(stringResource(R.string.style_size), size, 2f..16f) { size = it }
                if (!point) StyleSlider(stringResource(R.string.style_width), width, 1f..10f) { width = it }
                if (area) StyleSlider(stringResource(R.string.style_fill), fill, 0f..1f, percent = true) { fill = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(LayerStyle(color, size, width, fill)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.style_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun StyleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, percent: Boolean = false, onChange: (Float) -> Unit) {
    val shown = if (percent) "${(value * 100).roundToInt()}%" else String.format(Locale.ROOT, "%.0f", value)
    Text("$label $shown", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

// The symbol as the map draws it: a point, a line, or an area with its outline.
@Composable
private fun Preview(color: Color, size: Float, width: Float, fill: Float, point: Boolean, area: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(72.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val d = density
        when {
            point -> {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                drawCircle(Color.White, radius = (size + 2) * d, center = c)
                drawCircle(color, radius = size * d, center = c)
            }
            area -> {
                val path = Path().apply {
                    moveTo(this@Canvas.size.width * 0.25f, this@Canvas.size.height * 0.2f)
                    lineTo(this@Canvas.size.width * 0.75f, this@Canvas.size.height * 0.3f)
                    lineTo(this@Canvas.size.width * 0.65f, this@Canvas.size.height * 0.8f)
                    lineTo(this@Canvas.size.width * 0.3f, this@Canvas.size.height * 0.75f)
                    close()
                }
                drawPath(path, color.copy(alpha = fill))
                drawPath(path, color, style = Stroke(width * d, join = StrokeJoin.Round))
            }
            else -> {
                val path = Path().apply {
                    moveTo(this@Canvas.size.width * 0.15f, this@Canvas.size.height * 0.7f)
                    lineTo(this@Canvas.size.width * 0.4f, this@Canvas.size.height * 0.3f)
                    lineTo(this@Canvas.size.width * 0.6f, this@Canvas.size.height * 0.6f)
                    lineTo(this@Canvas.size.width * 0.85f, this@Canvas.size.height * 0.25f)
                }
                drawPath(path, color, style = Stroke(width * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
