package org.geocrew.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * A [folded] palette shows one row, its last swatch opening the rest. Its
 * swatches are [swatch] across, with [margin] above and below the palette.
 */
@Composable
fun ColorPalette(
    selected: Color,
    modifier: Modifier = Modifier,
    folded: Boolean = false,
    swatch: Dp = 36.dp,
    margin: Dp = 8.dp,
    onMore: (() -> Unit)? = null,
    onPick: (Color) -> Unit,
) {
    val inPalette = PALETTE.any { rgb(it) == rgb(selected) }
    var open by remember { mutableStateOf(!folded) }
    // Opened in a dialog that scrolls, the palette scrolls into view: its new rows would be below the fold.
    val seen = remember { BringIntoViewRequester() }
    LaunchedEffect(open) {
        if (open && folded) {
            withFrameNanos { }
            seen.bringIntoView()
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().bringIntoViewRequester(seen).padding(vertical = margin)) {
        // How many swatches fit in a row, 8 dp apart.
        val perRow = ((maxWidth + 8.dp) / (swatch + 8.dp)).toInt().coerceAtLeast(2)
        val shown = if (open) PALETTE else PALETTE.take(perRow - 1)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (c in shown) Swatch(c, rgb(c) == rgb(selected), swatch) { onPick(c) }
            if (folded) {
                Box(
                    Modifier.size(swatch).clip(CircleShape)
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
                    Modifier.size(swatch).clip(CircleShape)
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
private fun Swatch(color: Color, ticked: Boolean, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color)
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
 * opacity on a bar beside it; red, green and blue on bars of their own; the
 * hex code; or a colour of the palette. On a phone in landscape the square is
 * on the left, filling the height, and on the right, all in view, a row with
 * the colour, the title and the hex code, then the red, green and blue bars,
 * the palette and the buttons (AppDialog). [onPick] gets the colour when it
 * is confirmed.
 */
@Composable
fun ColorPickerDialog(title: String, selected: Color, onDismiss: () -> Unit, alpha: Boolean = true, onPick: (Color) -> Unit) {
    val state = remember { PickerState(selected, alpha) }
    // Side by side the height is short: the title shares a row with the colour and its code instead of a line of its own.
    val beside = isShortLandscape()
    AppDialog(
        onDismissRequest = onDismiss,
        title = if (beside) null else { { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        side = { sideBySide ->
            if (sideBySide) {
                ColorArea(state, Modifier.fillMaxSize(), fillHeight = true)
            } else {
                Column {
                    ColorArea(state, Modifier.fillMaxWidth(), fillHeight = false)
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(state.color) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        Column {
            // Side by side all of it is to fit beside the square on a phone's height, so it sits closer together.
            if (beside) {
                HeaderRow(title, state)
                Spacer(Modifier.height(8.dp))
                Channels(state, gap = 8.dp)
                ColorPalette(state.color, folded = true, swatch = 32.dp, margin = 4.dp) { state.setColor(it) }
            } else {
                HexRow(state)
                Spacer(Modifier.height(16.dp))
                Channels(state, gap = 12.dp)
                ColorPalette(state.color, folded = true) { state.setColor(it) }
            }
        }
    }
}

// The bars beside and under the square, and the gaps between; and the red, green and blue bars.
private val BAR = 28.dp
private val GAP = 12.dp
private val CHANNEL = 24.dp

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



// The colour as it is, what it is for, and its hex code to read or type, in one row: for a picker short of height.
@Composable
private fun HeaderRow(title: String, state: PickerState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorDot(state.color, 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        CompactHexField(state, Modifier.width(148.dp))
    }
}

// The hex code in a field 40 dp high, outlined like Material's: Material's own is at least 56 dp.
@Composable
private fun CompactHexField(state: PickerState, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val name = stringResource(R.string.color_hex)
    BasicTextField(
        value = state.typed,
        onValueChange = state::type,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        interactionSource = interaction,
        modifier = modifier.height(40.dp).semantics { contentDescription = name },
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize()
                    .border(
                        if (focused) 2.dp else 1.dp,
                        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f)) { field() }
            }
        },
    )
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

// Red, green and blue from 0 to 255, each on a bar of its own between its name and its value, [gap] apart.
@Composable
private fun Channels(state: PickerState, gap: Dp) {
    val argb = state.color.toArgb()
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for ((name, shift) in listOf(R.string.color_red to 16, R.string.color_green to 8, R.string.color_blue to 0)) {
            val channel = (argb shr shift) and 0xff
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(name), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp))
                ChannelBar(state.color, shift, Modifier.weight(1f)) { v -> state.setColor(Color((argb and (0xff shl shift).inv()) or (v shl shift))) }
                Text(
                    channel.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(40.dp),
                )
            }
        }
    }
}

/**
 * One of red, green and blue of [color] ([shift] 16, 8 or 0 bits into its
 * ARGB) on a bar from none to all of it, the colour's other two as they are,
 * so the bar shows what moving it would make; a tap or a drag sets it.
 */
@Composable
private fun ChannelBar(color: Color, shift: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    val argb = color.toArgb() or (0xff shl 24)
    val none = Color(argb and (0xff shl shift).inv())
    val all = Color(argb or (0xff shl shift))
    val value = (argb shr shift) and 0xff
    // The thumb's centre runs from one end's rounding to the other's, so 0 and 255 are reached at the ends.
    fun at(x: Float, w: Float, r: Float) = latest((((x - r) / (w - 2 * r)).coerceIn(0f, 1f) * 255).roundToInt())
    Canvas(
        modifier.height(CHANNEL)
            .pointerInput(Unit) { detectTapGestures { at(it.x, size.width.toFloat(), size.height / 2f) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position.x, size.width.toFloat(), size.height / 2f) } },
    ) {
        val r = size.height / 2
        drawRoundRect(Brush.horizontalGradient(listOf(none, all)), cornerRadius = CornerRadius(r, r))
        val x = r + value / 255f * (size.width - 2 * r)
        drawCircle(Color(argb), radius = r - 2.dp.toPx(), center = Offset(x, r))
        drawCircle(Color.White, radius = r - 2.dp.toPx(), center = Offset(x, r), style = Stroke(3.dp.toPx()))
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
