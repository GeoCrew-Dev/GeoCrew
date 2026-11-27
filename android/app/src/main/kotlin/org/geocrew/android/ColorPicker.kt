package org.geocrew.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Colours to choose from: strong enough to read on imagery, and told apart by colour-blind eyes as far as can be. */
internal val PALETTE = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
    0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E,
    0xFF6D4C41, 0xFF757575, 0xFF546E7A, 0xFF000000, 0xFFFFFFFF,
).map { Color(it) }

/**
 * The [PALETTE] as round swatches, [selected] ticked; a tap gives [onPick] the
 * colour. With [onMore], a last swatch opens the full picker; it shows
 * [selected] when that is not in the palette.
 */
@Composable
fun ColorPalette(selected: Color, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null, onPick: (Color) -> Unit) {
    val inPalette = PALETTE.any { it.toArgb() == selected.toArgb() }
    FlowRow(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (c in PALETTE) Swatch(c, c.toArgb() == selected.toArgb()) { onPick(c) }
        if (onMore != null) {
            val custom = if (inPalette) null else selected
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(custom ?: MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (custom != null) Icons.Rounded.Check else Icons.Rounded.Colorize,
                    stringResource(R.string.color_more),
                    tint = custom?.let(::onColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, ticked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (ticked) Icon(Icons.Rounded.Check, null, tint = onColor(color))
    }
}

// What reads on top of [color].
private fun onColor(color: Color): Color = if (color.luminance() > 0.5f) Color.Black else Color.White

/**
 * A colour to choose for [title], starting from [selected]: hue, saturation and
 * brightness on a square and a bar, red, green and blue on sliders, the hex
 * code, or a colour of the palette. [onPick] gets it when confirmed.
 */
@Composable
fun ColorPickerDialog(title: String, selected: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(selected.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var brightness by remember { mutableFloatStateOf(start[2]) }
    val color = Color.hsv(hue, saturation, brightness)
    // The hex code as typed; it follows the colour unless it is being typed and is not a colour yet.
    var typed by remember { mutableStateOf(hexOf(selected)) }
    fun set(c: Color, fromTyping: Boolean = false) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
        // Greys and black have no hue of their own: keep the one there was.
        if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0]
        if (hsv[2] > 0f) saturation = hsv[1]
        brightness = hsv[2]
        if (!fromTyping) typed = hexOf(c)
    }
    fun moved(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h
        saturation = s
        brightness = v
        typed = hexOf(Color.hsv(h, s, v))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(color).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { text ->
                            val clean = text.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            typed = clean
                            parseHex(clean)?.let { set(it, fromTyping = true) }
                        },
                        prefix = { Text("#") },
                        label = { Text(stringResource(R.string.color_hex)) },
                        singleLine = true,
                        isError = parseHex(typed) == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                SaturationBrightness(hue, saturation, brightness) { s, v -> moved(s = s, v = v) }
                Spacer(Modifier.height(12.dp))
                HueBar(hue) { moved(h = it) }
                Spacer(Modifier.height(8.dp))
                val argb = color.toArgb()
                for ((name, shift) in listOf(R.string.color_red to 16, R.string.color_green to 8, R.string.color_blue to 0)) {
                    val channel = (argb shr shift) and 0xff
                    Text("${stringResource(name)} $channel", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    Slider(
                        value = channel.toFloat(),
                        onValueChange = { v -> set(Color(((argb and (0xff shl shift).inv()) or (v.roundToInt() shl shift)) or (0xff shl 24))) },
                        valueRange = 0f..255f,
                    )
                }
                ColorPalette(color) { set(it) }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// Saturation across, brightness up, for [hue]; a tap or a drag sets both.
@Composable
private fun SaturationBrightness(hue: Float, saturation: Float, brightness: Float, onChange: (Float, Float) -> Unit) {
    fun at(o: Offset, w: Float, h: Float) = onChange((o.x / w).coerceIn(0f, 1f), 1f - (o.y / h).coerceIn(0f, 1f))
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1.6f).clip(MaterialTheme.shapes.medium)
            .pointerInput(Unit) { detectTapGestures { at(it, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position, size.width.toFloat(), size.height.toFloat()) } },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(saturation * size.width, (1f - brightness) * size.height)
        drawCircle(Color.White, radius = 10.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.4f), radius = 12.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
    }
}

// The hues from red round to red; a tap or a drag sets it.
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val hues = remember { (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) } }
    fun at(x: Float, w: Float) = onChange((x / w).coerceIn(0f, 1f) * 359.9f)
    Canvas(
        Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) { detectTapGestures { at(it.x, size.width.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position.x, size.width.toFloat()) } },
    ) {
        val r = size.height / 2
        drawRoundRect(Brush.horizontalGradient(hues), cornerRadius = CornerRadius(r, r))
        val x = (hue / 360f * size.width).coerceIn(r, size.width - r)
        drawCircle(Color.hsv(hue, 1f, 1f), radius = r - 2.dp.toPx(), center = Offset(x, r))
        drawCircle(Color.White, radius = r - 2.dp.toPx(), center = Offset(x, r), style = Stroke(3.dp.toPx()))
    }
}

private fun hexOf(color: Color): String = "%06X".format(color.toArgb() and 0xffffff)

private fun parseHex(text: String): Color? = if (text.length == 6) text.toLongOrNull(16)?.let { Color(0xFF000000 or it) } else null
