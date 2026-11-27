package org.geocrew.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.files.NaturalOrder

/** One value of a categorized style's field and the colour it is drawn in. */
data class StyleClass(val value: String, val color: Color)

/** One range of a graduated style's field, from [from] up to [to] (the last range takes [to] too), and its colour. */
data class StyleRange(val from: Double, val to: Double, val color: Color)

/** How a style colours its features: in one colour, by the values of a field, or by ranges of a number field. */
enum class StyleMode { SINGLE, CATEGORIZED, GRADUATED }

/**
 * How a layer is drawn (ADR 0006): its colour, a point's radius and a line's
 * width in dp, an area's fill opacity. A categorized style also has a [field]
 * and a colour for each of its values ([classes]); features with any other
 * value keep [color]. A graduated style has a number [field] and [ranges]
 * instead. [label] is the field whose values label the features: null for
 * the layer's name field, empty for no labels.
 */
data class LayerStyle(
    val color: Color,
    val size: Float = DEFAULT_SIZE,
    val width: Float = DEFAULT_WIDTH,
    val fill: Float = DEFAULT_FILL,
    val field: String? = null,
    val classes: List<StyleClass> = emptyList(),
    val label: String? = null,
    val ranges: List<StyleRange>? = null,
) {
    /** How it colours its features. */
    val mode: StyleMode
        get() = when {
            // In a getter, field is the property's own: the style's field is this.field.
            this.field == null -> StyleMode.SINGLE
            ranges != null -> StyleMode.GRADUATED
            else -> StyleMode.CATEGORIZED
        }

    private val byValue: Map<String, String> by lazy { classes.associate { it.value to mapColor(it.color) } }
    private val byRange: List<String> by lazy { ranges.orEmpty().map { mapColor(it.color) } }

    /** The colour a feature with [properties] is drawn in, as MapLibre takes it (see [mapColor]). */
    fun mapColorFor(properties: JsonObject?): String {
        val other = mapColor(color)
        val field = field ?: return other
        val value = (properties?.get(field) as? JsonPrimitive)?.contentOrNull ?: return other
        val ranges = ranges ?: return byValue[value] ?: other
        return rangeOf(ranges, value.toDoubleOrNull() ?: return other)?.let { byRange[it] } ?: other
    }

    /** As layer.style takes it. */
    fun json(): JsonObject = buildJsonObject {
        put("color", colorHex(color))
        put("size", size.toDouble())
        put("width", width.toDouble())
        put("fill", fill.toDouble())
        if (field != null) {
            put("field", field)
            val ranges = ranges
            if (ranges != null) {
                putJsonArray("ranges") {
                    for (r in ranges) addJsonObject { put("from", r.from); put("to", r.to); put("color", colorHex(r.color)) }
                }
            } else {
                putJsonArray("classes") {
                    for (c in classes) addJsonObject { put("value", c.value); put("color", colorHex(c.color)) }
                }
            }
        }
        if (label != null) put("label", label)
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
    val color = (style?.get("color") as? JsonPrimitive)?.contentOrNull?.let(::colorOf)
    val field = (style?.get("field") as? JsonPrimitive)?.contentOrNull
    val classes = (style?.get("classes") as? JsonArray).orEmpty().mapNotNull { c ->
        val o = c as? JsonObject ?: return@mapNotNull null
        val value = (o["value"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val shown = (o["color"] as? JsonPrimitive)?.contentOrNull?.let(::colorOf) ?: return@mapNotNull null
        StyleClass(value, shown)
    }
    val ranges = (style?.get("ranges") as? JsonArray)?.mapNotNull { r ->
        val o = r as? JsonObject ?: return@mapNotNull null
        val from = (o["from"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val to = (o["to"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val shown = (o["color"] as? JsonPrimitive)?.contentOrNull?.let(::colorOf) ?: return@mapNotNull null
        StyleRange(from, to, shown)
    }
    return LayerStyle(
        color ?: fallback,
        number("size") ?: LayerStyle.DEFAULT_SIZE,
        number("width") ?: LayerStyle.DEFAULT_WIDTH,
        number("fill") ?: LayerStyle.DEFAULT_FILL,
        field,
        if (field == null) emptyList() else classes,
        (style?.get("label") as? JsonPrimitive)?.takeIf { it.isString }?.content,
        if (field == null) null else ranges,
    )
}

/** The field that labels a layer's features on the map, by its [style]: null for none. */
fun labelField(style: LayerStyle, collection: JsonObject?): String? = when (val label = style.label) {
    null -> nameField(collection)
    "" -> null
    else -> label.takeIf { it in fieldNames(collection) }
}

private fun colorOf(hex: String): Color? = if (hex.startsWith("#")) colorOfCode(hex.substring(1)) else null

/** A colour as a style keeps it: #rrggbb, or #rrggbbaa when it is not opaque. */
fun colorHex(color: Color): String = "#" + hexCode(color, alpha = true).lowercase()

/** A colour as MapLibre takes it: #rrggbb, or rgba() when it is not opaque. */
fun mapColor(color: Color): String {
    val argb = color.toArgb()
    val a = (argb ushr 24) and 0xff
    if (a == 0xff) return "#%06x".format(argb and 0xffffff)
    return "rgba(%d, %d, %d, %.3f)".format(Locale.ROOT, (argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff, a / 255.0)
}

/** The most values a categorized style lists; features with others keep the layer's colour. */
const val MAX_CLASSES = 100

/**
 * The classes for [field] of [features]: those in [kept] first, as they were,
 * then each other value there is, in natural order, in the next colours of the
 * palette. Blank values are left to the layer's colour.
 */
fun classesFor(features: JsonArray, field: String, kept: List<StyleClass>): List<StyleClass> {
    val values = features.mapNotNull { f ->
        (((f as? JsonObject)?.get("properties") as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }.distinct().sortedWith(NaturalOrder)
    val known = kept.map { it.value }.toSet()
    val added = values.filter { it !in known }.mapIndexed { i, v -> StyleClass(v, CLASS_COLORS[(kept.size + i) % CLASS_COLORS.size]) }
    return (kept + added).take(MAX_CLASSES)
}

/** The field types a graduated style can range over. */
val NUMBER_TYPES = setOf("integer", "integer64", "real")

// How many ranges a graduated style makes: at least, at most, and to begin with; and its ramp to begin with.
private const val MIN_RANGES = 2
private const val MAX_RANGES = 9
private const val DEFAULT_RANGES = 5
private val RAMP_START = Color(0xFFFFF59D)
private val RAMP_END = Color(0xFFC62828)

/** The range of [ranges] that [value] falls in, by its place; null for none. */
fun rangeOf(ranges: List<StyleRange>, value: Double): Int? = ranges.indices.firstOrNull { i ->
    val r = ranges[i]
    value >= r.from && (value < r.to || (i == ranges.lastIndex && value <= r.to))
}

/**
 * [count] ranges over the numbers of [field] in [features]: of equal width
 * from the least to the most, or ([quantile]) with about as many values in
 * each; coloured along the ramp from [start] to [end]. Ranges that would have
 * no width are merged; none when the field has no numbers.
 */
fun rangesFor(features: JsonArray, field: String, count: Int, quantile: Boolean, start: Color, end: Color): List<StyleRange> {
    val values = features.mapNotNull { f ->
        (((f as? JsonObject)?.get("properties") as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }.sorted()
    if (values.isEmpty()) return emptyList()
    val least = values.first()
    val most = values.last()
    val breaks = (0..count).map { i ->
        when {
            i == count -> most
            quantile -> values[i * values.size / count]
            else -> least + (most - least) * i / count
        }
    }.distinct()
    val sides = if (breaks.size < 2) listOf(least to most) else breaks.zipWithNext()
    return sides.mapIndexed { i, (a, b) -> StyleRange(a, b, lerp(start, end, if (sides.size == 1) 0f else i.toFloat() / (sides.size - 1))) }
}

/** A range as people read it: 10 – 20. */
@Composable
fun rangeText(range: StyleRange): String = stringResource(R.string.style_range, boundText(range.from), boundText(range.to))

/** A range's bound as people read it: up to six significant digits, no trailing zeros, never in exponent form. */
fun boundText(value: Double): String = BigDecimal(value).round(MathContext(6)).stripTrailingZeros().toPlainString()

// The palette in an order that puts unlike colours next to each other, for classes; no white or black.
private val CLASS_COLORS = listOf(5, 0, 9, 14, 2, 7, 12, 16, 3, 10, 1, 13, 6, 18, 4, 11, 15, 8, 17).map { PALETTE[it] }

/**
 * A layer's style to change: one colour from the palette; a colour for each
 * value of a field of [collection] (the layer's features as layer.features
 * gives them, null while they load); or colours for ranges of a number field,
 * made from a colour ramp; with the layer's colour for the rest. By what the
 * layer holds ([geometry], an OGC name), a point's size, a line's width, an
 * area's fill, with a preview; and which field labels the features, if any.
 * [onSave] gets the style; [onReset] goes back to the default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleDialog(
    layer: String,
    geometry: String?,
    style: LayerStyle,
    collection: JsonObject?,
    onDismiss: () -> Unit,
    onSave: (LayerStyle) -> Unit,
    onReset: () -> Unit,
) {
    var color by remember { mutableStateOf(style.color) }
    var size by remember { mutableFloatStateOf(style.size) }
    var width by remember { mutableFloatStateOf(style.width) }
    var fill by remember { mutableFloatStateOf(style.fill) }
    var mode by remember { mutableStateOf(style.mode) }
    // The field coloured by its values, and the colour of each.
    var field by remember { mutableStateOf(style.field.takeIf { style.mode == StyleMode.CATEGORIZED }) }
    var classes by remember { mutableStateOf(style.classes) }
    // The number field coloured by ranges, and how the ranges are made.
    var number by remember { mutableStateOf(style.field.takeIf { style.mode == StyleMode.GRADUATED }) }
    var ranges by remember { mutableStateOf(style.ranges.orEmpty()) }
    var count by remember { mutableIntStateOf(style.ranges?.size?.coerceIn(MIN_RANGES, MAX_RANGES) ?: DEFAULT_RANGES) }
    var quantile by remember { mutableStateOf(false) }
    var rampStart by remember { mutableStateOf(style.ranges?.firstOrNull()?.color ?: RAMP_START) }
    var rampEnd by remember { mutableStateOf(style.ranges?.lastOrNull()?.color ?: RAMP_END) }
    var label by remember { mutableStateOf(style.label) }
    var picking by remember { mutableStateOf<Pick?>(null) }
    val point = geometry == null || geometry.contains("POINT")
    val area = holdsAreas(geometry)
    val features = collection?.get("features") as? JsonArray ?: JsonArray(emptyList())
    val formFields = formFields(collection)
    val fields = formFields.map { it.name }
    val numbers = formFields.filter { it.type in NUMBER_TYPES }.map { it.name }
    fun categorize(f: String) {
        field = f
        classes = classesFor(features, f, if (style.mode == StyleMode.CATEGORIZED && style.field == f) style.classes else emptyList())
    }
    fun grade() {
        val f = number ?: return
        ranges = rangesFor(features, f, count, quantile, rampStart, rampEnd)
    }
    // Once the features are read: values added since the style was saved get colours of their own, and ranges are made.
    LaunchedEffect(collection) {
        if (collection == null) return@LaunchedEffect
        field?.let { if (mode == StyleMode.CATEGORIZED) classes = classesFor(features, it, classes) }
        if (mode == StyleMode.GRADUATED && ranges.isEmpty()) grade()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Palette, null) },
        title = { Text(stringResource(R.string.style_title, layer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    SegmentedButton(selected = mode == StyleMode.SINGLE, onClick = { mode = StyleMode.SINGLE }, shape = SegmentedButtonDefaults.itemShape(0, 3)) {
                        Text(stringResource(R.string.style_single), maxLines = 1)
                    }
                    SegmentedButton(
                        selected = mode == StyleMode.CATEGORIZED,
                        enabled = fields.isNotEmpty(),
                        onClick = {
                            mode = StyleMode.CATEGORIZED
                            if (field == null) categorize(style.field?.takeIf { it in fields && style.mode == StyleMode.CATEGORIZED } ?: fields.first())
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 3),
                    ) { Text(stringResource(R.string.style_categorized), maxLines = 1) }
                    SegmentedButton(
                        selected = mode == StyleMode.GRADUATED,
                        enabled = numbers.isNotEmpty(),
                        onClick = {
                            mode = StyleMode.GRADUATED
                            if (number == null) {
                                number = numbers.first()
                                grade()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(2, 3),
                    ) { Text(stringResource(R.string.style_graduated), maxLines = 1) }
                }
                val shown = when (mode) {
                    StyleMode.CATEGORIZED -> classes.firstOrNull()?.color
                    StyleMode.GRADUATED -> ranges.firstOrNull()?.color
                    StyleMode.SINGLE -> null
                } ?: color
                Preview(shown, size, width, fill, point, area)
                when (mode) {
                    StyleMode.CATEGORIZED -> {
                        val chosen = field
                        FieldChips(fields, chosen) { if (it != chosen) categorize(it) }
                        classes.forEachIndexed { i, c -> ClassRow(c.value, c.color) { picking = Pick.Class(i) } }
                        ClassRow(stringResource(R.string.style_other), color) { picking = Pick.Other }
                    }
                    StyleMode.GRADUATED -> {
                        val chosen = number
                        FieldChips(numbers, chosen) {
                            if (it != chosen) {
                                number = it
                                grade()
                            }
                        }
                        StyleSlider(stringResource(R.string.style_count), count.toFloat(), MIN_RANGES.toFloat()..MAX_RANGES.toFloat(), steps = MAX_RANGES - MIN_RANGES - 1) {
                            val n = it.roundToInt()
                            if (n != count) {
                                count = n
                                grade()
                            }
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            for ((i, q) in listOf(false, true).withIndex()) {
                                SegmentedButton(
                                    selected = quantile == q,
                                    onClick = {
                                        quantile = q
                                        grade()
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                                ) { Text(stringResource(if (q) R.string.style_quantile else R.string.style_equal), maxLines = 1) }
                            }
                        }
                        // The colour ramp, from its first colour to its last; a tap on either chooses another.
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.style_ramp), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            ColorDot(rampStart, 32.dp, Modifier.clickable { picking = Pick.RampStart })
                            Box(
                                Modifier.padding(horizontal = 8.dp).width(64.dp).height(12.dp).clip(CircleShape)
                                    .background(Brush.horizontalGradient(listOf(rampStart, rampEnd))),
                            )
                            ColorDot(rampEnd, 32.dp, Modifier.clickable { picking = Pick.RampEnd })
                        }
                        ranges.forEachIndexed { i, r -> ClassRow(rangeText(r), r.color) { picking = Pick.Range(i) } }
                        ClassRow(stringResource(R.string.style_other), color) { picking = Pick.Other }
                    }
                    // A colour of the palette keeps the opacity there was.
                    StyleMode.SINGLE -> ColorPalette(color, onMore = { picking = Pick.Other }) { color = it.copy(alpha = color.alpha) }
                }
                if (point) StyleSlider(stringResource(R.string.style_size), size, 2f..16f) { size = it }
                if (!point) StyleSlider(stringResource(R.string.style_width), width, 1f..10f) { width = it }
                if (area) StyleSlider(stringResource(R.string.style_fill), fill, 0f..1f, percent = true) { fill = it }
                if (collection != null) {
                    // Which field labels the features: the name field until another is chosen, or none.
                    val labelled = labelField(LayerStyle(color, label = label), collection)
                    Text(stringResource(R.string.style_label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = labelled == null, onClick = { label = "" }, label = { Text(stringResource(R.string.style_label_none)) })
                        for (f in fields) FilterChip(selected = f == labelled, onClick = { label = f }, label = { Text(f) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        LayerStyle(
                            color, size, width, fill,
                            field = when (mode) {
                                StyleMode.CATEGORIZED -> field
                                StyleMode.GRADUATED -> number
                                StyleMode.SINGLE -> null
                            },
                            classes = if (mode == StyleMode.CATEGORIZED) classes else emptyList(),
                            label = label,
                            ranges = if (mode == StyleMode.GRADUATED) ranges else null,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.style_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    picking?.let { p ->
        val (title, current) = when (p) {
            is Pick.Class -> classes.getOrNull(p.index)?.let { it.value to it.color } ?: (layer to color)
            is Pick.Range -> ranges.getOrNull(p.index)?.let { rangeText(it) to it.color } ?: (layer to color)
            Pick.Other -> (if (mode == StyleMode.SINGLE) layer else stringResource(R.string.style_other)) to color
            Pick.RampStart -> stringResource(R.string.style_ramp) to rampStart
            Pick.RampEnd -> stringResource(R.string.style_ramp) to rampEnd
        }
        ColorPickerDialog(title = title, selected = current, onDismiss = { picking = null }) { picked ->
            picking = null
            when (p) {
                is Pick.Class -> classes = classes.mapIndexed { i, c -> if (i == p.index) c.copy(color = picked) else c }
                is Pick.Range -> ranges = ranges.mapIndexed { i, r -> if (i == p.index) r.copy(color = picked) else r }
                Pick.Other -> color = picked
                Pick.RampStart -> {
                    rampStart = picked
                    grade()
                }
                Pick.RampEnd -> {
                    rampEnd = picked
                    grade()
                }
            }
        }
    }
}

// Whose colour is being chosen: a class's, a range's, the rest's, or an end of the ramp.
private sealed interface Pick {
    data class Class(val index: Int) : Pick

    data class Range(val index: Int) : Pick

    data object Other : Pick

    data object RampStart : Pick

    data object RampEnd : Pick
}

// The fields to choose from, [chosen] marked.
@Composable
private fun FieldChips(fields: List<String>, chosen: String?, onChoose: (String) -> Unit) {
    Text(stringResource(R.string.style_field), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in fields) FilterChip(selected = f == chosen, onClick = { onChoose(f) }, label = { Text(f) })
    }
    Spacer(Modifier.height(8.dp))
}

// A value of a categorized style and its colour; a tap chooses another.
@Composable
private fun ClassRow(value: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(color, 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StyleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, percent: Boolean = false, steps: Int = 0, onChange: (Float) -> Unit) {
    val shown = if (percent) "${(value * 100).roundToInt()}%" else String.format(Locale.ROOT, "%.0f", value)
    Text("$label $shown", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
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
