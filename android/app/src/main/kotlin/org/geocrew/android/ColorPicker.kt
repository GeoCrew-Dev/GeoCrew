package org.geocrew.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Colours to choose from: strong enough to read on imagery, and told apart by colour-blind eyes as far as can be. */
internal val PALETTE = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
    0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E,
    0xFF6D4C41, 0xFF757575, 0xFF546E7A, 0xFF000000, 0xFFFFFFFF,
).map { Color(it) }

/**
 * The [PALETTE] as round swatches, the one with [selected]'s red, green and
 * blue ticked; a tap gives [onPick] the colour. With [onMore], a last swatch
 * opens the full picker; it shows [selected] when that is not in the palette.
 * A [folded] palette shows one row, its last swatch opening the rest.
 */
@Composable
fun ColorPalette(selected: Color, modifier: Modifier = Modifier, folded: Boolean = false, onMore: (() -> Unit)? = null, onPick: (Color) -> Unit) {
    val inPalette = PALETTE.any { rgb(it) == rgb(selected) }
    var open by remember { mutableStateOf(!folded) }
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // How many swatches fit in a row: 36 dp each, 8 dp apart.
        val perRow = ((maxWidth + 8.dp) / 44.dp).toInt().coerceAtLeast(2)
        val shown = if (open) PALETTE else PALETTE.take(perRow - 1)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (c in shown) Swatch(c, rgb(c) == rgb(selected)) { onPick(c) }
            if (folded) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { open = !open },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        stringResource(if (open) R.string.color_fewer else R.string.color_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onMore != null) {
                val custom = if (inPalette) null else selected
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .then(if (custom != null) Modifier.checkered() else Modifier)
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

/** A round swatch of [color], on a checkerboard so that its opacity shows. */
@Composable
fun ColorDot(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).checkered().background(color).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
}

/** [color]'s hex code without the #: RRGGBB, and AA after it when [alpha] and the colour is not opaque. */
fun hexCode(color: Color, alpha: Boolean): String {
    val argb = color.toArgb()
    val a = (argb ushr 24) and 0xff
    return if (alpha && a != 0xff) "%06X%02X".format(argb and 0xffffff, a) else "%06X".format(argb and 0xffffff)
}

/** The colour of a hex code without the #, RRGGBB or RRGGBBAA in either case; null for anything else. */
fun colorOfCode(code: String): Color? {
    if ((code.length != 6 && code.length != 8) || !code.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
    val a = if (code.length == 8) code.substring(6).toLong(16) else 0xffL
    return Color((a shl 24) or code.substring(0, 6).toLong(16))
}

/**
 * A colour to choose for [title], starting from [selected]: saturation and
 * brightness on a square, the hue on a bar under it and, when [alpha], the
 * opacity on a bar beside it; red, green and blue on sliders; the hex code; or
 * a colour of the palette. When the window is wider than it is tall (a phone
 * on its side) the square is on the left, filling the height, and the rest on
 * the right, scrolling above the buttons. [onPick] gets the colour when it is
 * confirmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerDialog(title: String, selected: Color, onDismiss: () -> Unit, alpha: Boolean = true, onPick: (Color) -> Unit) {
    val state = remember { PickerState(selected, alpha) }
    val confirm = { onPick(state.color) }
    // The part of the window clear of the system bars and cutouts, which the dialog has to fit in.
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val clear = WindowInsets.safeDrawing
    val clearWidth = with(density) { (window.width - clear.getLeft(this, LayoutDirection.Ltr) - clear.getRight(this, LayoutDirection.Ltr)).toDp() }
    val clearHeight = with(density) { (window.height - clear.getTop(this) - clear.getBottom(this)).toDp() }
    val sideBySide = clearWidth > clearHeight && clearHeight >= SIDE_BY_SIDE_MIN
    // Side by side the dialog is wider than a dialog usually is, so it sets its own width and height.
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = !sideBySide)) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                if (sideBySide) {
                    // 16 dp clear above and below the dialog, 24 dp of padding inside it.
                    Row(Modifier.padding(24.dp).height((clearHeight - 32.dp - 48.dp).coerceAtMost(SIDE_BY_SIDE_HEIGHT))) {
                        ColorArea(state, Modifier.weight(1f).fillMaxHeight(), fillHeight = true)
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            DialogTitle(title)
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                HexRow(state)
                                Spacer(Modifier.height(8.dp))
                                Channels(state)
                                ColorPalette(state.color, folded = true) { state.setColor(it) }
                            }
                            DialogButtons(12.dp, onDismiss, confirm)
                        }
                    }
                } else {
                    Column(Modifier.padding(24.dp)) {
                        DialogTitle(title)
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            HexRow(state)
                            Spacer(Modifier.height(16.dp))
                            ColorArea(state, Modifier.fillMaxWidth(), fillHeight = false)
                            Spacer(Modifier.height(8.dp))
                            Channels(state)
                            ColorPalette(state.color, folded = true) { state.setColor(it) }
                        }
                        DialogButtons(24.dp, onDismiss, confirm)
                    }
                }
            }
        }
    }
}

// The bars beside and under the square, and the gaps between.
private val BAR = 28.dp
private val GAP = 12.dp

// The most the side-by-side picker grows to between its padding, and the least clear height it needs.
private val SIDE_BY_SIDE_HEIGHT = 360.dp
private val SIDE_BY_SIDE_MIN = 280.dp

/**
 * What the picker holds: the colour as hue, saturation, brightness and alpha,
 * and the hex code as typed, which follows the colour unless it is being typed
 * and is not a colour yet. Opaque throughout unless [withAlpha].
 */
private class PickerState(start: Color, val withAlpha: Boolean) {
    var hue by mutableFloatStateOf(0f)
    var saturation by mutableFloatStateOf(0f)
    var brightness by mutableFloatStateOf(0f)
    var alpha by mutableFloatStateOf(1f)
    var typed by mutableStateOf("")

    init {
        setColor(start, keepAlpha = false)
    }

    val color: Color get() = Color.hsv(hue, saturation, brightness, alpha)

    /** Takes [c]'s red, green and blue, and its opacity too unless [keepAlpha]. */
    fun setColor(c: Color, keepAlpha: Boolean = true, fromTyping: Boolean = false) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
        // Greys and black have no hue of their own, and black no saturation: keep the ones there were.
        if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0]
        if (hsv[2] > 0f) saturation = hsv[1]
        brightness = hsv[2]
        if (!keepAlpha && withAlpha) alpha = c.alpha
        if (!fromTyping) typed = hexCode(color, withAlpha)
    }

    fun setHsv(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h
        saturation = s
        brightness = v
        typed = hexCode(color, withAlpha)
    }

    fun changeAlpha(a: Float) {
        alpha = a
        typed = hexCode(color, withAlpha)
    }

    // Six digits set the colour and keep the opacity; eight set both.
    fun type(text: String) {
        val clean = text.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(if (withAlpha) 8 else 6)
        typed = clean
        if (clean.length == 6 || clean.length == 8) colorOfCode(clean)?.let { setColor(it, keepAlpha = clean.length == 6, fromTyping = true) }
    }
}

@Composable
private fun DialogTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        color = AlertDialogDefaults.titleContentColor,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 16.dp),
    )
}

@Composable
private fun DialogButtons(above: Dp, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = above), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok)) }
    }
}

// The colour as it is, and its hex code to read or type.
@Composable
private fun HexRow(state: PickerState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorDot(state.color, 48.dp)
        Spacer(Modifier.width(16.dp))
        OutlinedTextField(
            value = state.typed,
            onValueChange = state::type,
            prefix = { Text("#") },
            label = { Text(stringResource(R.string.color_hex)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.weight(1f),
        )
    }
}

// Red, green and blue from 0 to 255.
@Composable
private fun Channels(state: PickerState) {
    val argb = state.color.toArgb()
    for ((name, shift) in listOf(R.string.color_red to 16, R.string.color_green to 8, R.string.color_blue to 0)) {
        val channel = (argb shr shift) and 0xff
        Text("${stringResource(name)} $channel", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Slider(
            value = channel.toFloat(),
            onValueChange = { v -> state.setColor(Color((argb and (0xff shl shift).inv()) or (v.roundToInt() shl shift))) },
            valueRange = 0f..255f,
        )
    }
}

/**
 * The square with the opacity bar on its right and the hue bar under it, the
 * opacity in percent in the corner between the bars. With [fillHeight] it
 * fills the height it is given; otherwise its height follows its width.
 */
@Composable
private fun ColorArea(state: PickerState, modifier: Modifier, fillHeight: Boolean) {
    BoxWithConstraints(modifier) {
        val beside = if (state.withAlpha) BAR + GAP else 0.dp
        val squareWidth = (maxWidth - beside).coerceAtLeast(BAR)
        val squareHeight = if (fillHeight) (maxHeight - BAR - GAP).coerceAtLeast(BAR) else squareWidth / 1.4f
        Column {
            Row(Modifier.height(squareHeight)) {
                SaturationBrightness(state.hue, state.saturation, state.brightness, Modifier.width(squareWidth).fillMaxHeight()) { s, v ->
                    state.setHsv(s = s, v = v)
                }
                if (state.withAlpha) {
                    Spacer(Modifier.width(GAP))
                    AlphaBar(state.color, state.alpha, Modifier.width(BAR).fillMaxHeight(), state::changeAlpha)
                }
            }
            Spacer(Modifier.height(GAP))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HueBar(state.hue, Modifier.width(squareWidth)) { state.setHsv(h = it) }
                if (state.withAlpha) {
                    Spacer(Modifier.width(GAP))
                    Text(
                        stringResource(R.string.color_percent, (state.alpha * 100).roundToInt()),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(BAR),
                    )
                }
            }
        }
    }
}

// Saturation across, brightness up, for [hue]; a tap or a drag sets both.
@Composable
private fun SaturationBrightness(hue: Float, saturation: Float, brightness: Float, modifier: Modifier, onChange: (Float, Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    fun at(o: Offset, w: Float, h: Float) = latest((o.x / w).coerceIn(0f, 1f), 1f - (o.y / h).coerceIn(0f, 1f))
    Canvas(
        modifier.clip(MaterialTheme.shapes.medium)
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

// The opacity of [color], opaque at the top and clear at the bottom; a tap or a drag sets it.
@Composable
private fun AlphaBar(color: Color, alpha: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    fun at(y: Float, h: Float) = latest(1f - (y / h).coerceIn(0f, 1f))
    Canvas(
        modifier.clip(RoundedCornerShape(50)).checkered()
            .pointerInput(Unit) { detectTapGestures { at(it.y, size.height.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position.y, size.height.toFloat()) } },
    ) {
        val opaque = color.copy(alpha = 1f)
        drawRect(Brush.verticalGradient(listOf(opaque, opaque.copy(alpha = 0f))))
        val r = size.width / 2
        val y = ((1f - alpha) * size.height).coerceIn(r, size.height - r)
        drawCircle(color, radius = r - 2.dp.toPx(), center = Offset(r, y))
        drawCircle(Color.White, radius = r - 2.dp.toPx(), center = Offset(r, y), style = Stroke(3.dp.toPx()))
    }
}

// The hues from red round to red; a tap or a drag sets it.
@Composable
private fun HueBar(hue: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    val hues = remember { (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) } }
    fun at(x: Float, w: Float) = latest((x / w).coerceIn(0f, 1f) * 359.9f)
    Canvas(
        modifier.height(BAR)
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

// A light checkerboard under what is drawn next, showing through a colour that is not opaque.
private fun Modifier.checkered(): Modifier = drawBehind { checkerboard() }

private fun DrawScope.checkerboard() {
    val cell = 6.dp.toPx()
    drawRect(Color.White)
    for (row in 0 until ceil(size.height / cell).toInt()) {
        for (column in 0 until ceil(size.width / cell).toInt()) {
            if ((row + column) % 2 == 1) drawRect(CHECKER, topLeft = Offset(column * cell, row * cell), size = Size(cell, cell))
        }
    }
}

private val CHECKER = Color(0xFFCCCCCC)

private fun rgb(color: Color): Int = color.toArgb() and 0xffffff

// What reads on top of [color].
private fun onColor(color: Color): Color = if (color.luminance() > 0.5f) Color.Black else Color.White
