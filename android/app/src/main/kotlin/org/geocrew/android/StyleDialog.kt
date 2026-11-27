package org.geocrew.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.files.NaturalOrder

/** One value of a categorized style's field and the colour it is drawn in. */
data class StyleClass(val value: String, val color: Color)

/**
 * How a layer is drawn (ADR 0006): its colour, a point's radius and a line's
 * width in dp, an area's fill opacity. A categorized style also has a [field]
 * and a colour for each of its values ([classes]); features with any other
 * value keep [color]. [label] is the field whose values label the features:
 * null for the layer's name field, empty for no labels.
 */
data class LayerStyle(
    val color: Color,
    val size: Float = DEFAULT_SIZE,
    val width: Float = DEFAULT_WIDTH,
    val fill: Float = DEFAULT_FILL,
    val field: String? = null,
    val classes: List<StyleClass> = emptyList(),
    val label: String? = null,
) {
    private val byValue: Map<String, String> by lazy { classes.associate { it.value to mapColor(it.color) } }

    /** The colour a feature with [properties] is drawn in, as MapLibre takes it (see [mapColor]). */
    fun mapColorFor(properties: JsonObject?): String {
        val other = mapColor(color)
        val field = field ?: return other
        val value = (properties?.get(field) as? JsonPrimitive)?.contentOrNull ?: return other
        return byValue[value] ?: other
    }

    /** As layer.style takes it. */
    fun json(): JsonObject = buildJsonObject {
        put("color", colorHex(color))
        put("size", size.toDouble())
        put("width", width.toDouble())
        put("fill", fill.toDouble())
        if (field != null) {
            put("field", field)
            putJsonArray("classes") {
                for (c in classes) addJsonObject { put("value", c.value); put("color", colorHex(c.color)) }
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
    return LayerStyle(
        color ?: fallback,
        number("size") ?: LayerStyle.DEFAULT_SIZE,
        number("width") ?: LayerStyle.DEFAULT_WIDTH,
        number("fill") ?: LayerStyle.DEFAULT_FILL,
        field,
        if (field == null) emptyList() else classes,
        (style?.get("label") as? JsonPrimitive)?.takeIf { it.isString }?.content,
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

// The palette in an order that puts unlike colours next to each other, for classes; no white or black.
private val CLASS_COLORS = listOf(5, 0, 9, 14, 2, 7, 12, 16, 3, 10, 1, 13, 6, 18, 4, 11, 15, 8, 17).map { PALETTE[it] }

/**
 * A layer's style to change: one colour from the palette, or a colour for each
 * value of a field of [collection] (the layer's features as layer.features
 * gives them, null while they load) with the layer's colour for the rest; by
 * what the layer holds ([geometry], an OGC name), a point's size, a line's
 * width, an area's fill, with a preview; and which field labels the features,
 * if any. [onSave] gets the style; [onReset] goes back to the default.
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
    var field by remember { mutableStateOf(style.field) }
    var classes by remember { mutableStateOf(style.classes) }
    var label by remember { mutableStateOf(style.label) }
    // Whose colour is being chosen: a class by its index, OTHER for the rest; null for no one.
    var picking by remember { mutableStateOf<Int?>(null) }
    val point = geometry == null || geometry.contains("POINT")
    val area = holdsAreas(geometry)
    val features = collection?.get("features") as? JsonArray ?: JsonArray(emptyList())
    val fields = formFields(collection).map { it.name }
    // Values added to the field since the style was saved get colours of their own once the features are read.
    LaunchedEffect(collection) {
        val f = field
        if (collection != null && f != null) classes = classesFor(features, f, classes)
    }
    fun categorize(f: String) {
        field = f
        classes = classesFor(features, f, if (style.field == f) style.classes else emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Palette, null) },
        title = { Text(stringResource(R.string.style_title, layer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    SegmentedButton(
                        selected = field == null,
                        onClick = { field = null },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.style_single)) }
                    SegmentedButton(
                        selected = field != null,
                        enabled = fields.isNotEmpty(),
                        onClick = { if (field == null) categorize(style.field?.takeIf { it in fields } ?: fields.first()) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.style_categorized)) }
                }
                val chosen = field
                Preview(if (chosen != null) classes.firstOrNull()?.color ?: color else color, size, width, fill, point, area)
                if (chosen != null) {
                    Text(stringResource(R.string.style_field), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in fields) {
                            FilterChip(selected = f == chosen, onClick = { if (f != chosen) categorize(f) }, label = { Text(f) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    classes.forEachIndexed { i, c -> ClassRow(c.value, c.color) { picking = i } }
                    ClassRow(stringResource(R.string.style_other), color) { picking = OTHER }
                } else {
                    // A colour of the palette keeps the opacity there was.
                    ColorPalette(color, onMore = { picking = OTHER }) { color = it.copy(alpha = color.alpha) }
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
            TextButton(onClick = { onSave(LayerStyle(color, size, width, fill, field, if (field == null) emptyList() else classes, label)) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.style_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    picking?.let { i ->
        val c = classes.getOrNull(i)
        ColorPickerDialog(
            title = c?.value ?: if (field == null) layer else stringResource(R.string.style_other),
            selected = c?.color ?: color,
            onDismiss = { picking = null },
        ) { picked ->
            picking = null
            if (c != null) classes = classes.toMutableList().apply { set(i, c.copy(color = picked)) } else color = picked
        }
    }
}

// Whose colour is chosen when it is the rest's, the features with no class of their own.
private const val OTHER = -1

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
