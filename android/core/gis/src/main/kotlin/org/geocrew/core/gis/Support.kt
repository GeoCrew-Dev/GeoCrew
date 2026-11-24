package org.geocrew.core.gis

import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Props
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.PropertiesBuilder
import org.geocrew.core.model.double
import kotlin.math.PI
import kotlin.math.cos

/** Property keys tools write. Colours follow the GeoJSON simplestyle convention so other GIS show them too. */
object OutputProps {
    const val TOOL = "tool"
    const val VALUE = "value"
    const val FILL = "fill"
    const val FILL_OPACITY = "fill-opacity"
    const val STROKE = "stroke"
    const val STROKE_WIDTH = "stroke-width"
}

/** Numeric properties found on any of [features], most common first. */
fun numericFields(features: List<GeoFeature>): List<String> =
    features.flatMap { f -> f.properties.entries.filter { (_, v) -> (v as? JsonPrimitive)?.let { !it.isString && it.content.toDoubleOrNull() != null } == true }.map { it.key } }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }

/**
 * Text properties that work as categories (lithology, formation, rock type):
 * a limited set of values, not names, notes, people or times. Most common first.
 */
fun categoryFields(features: List<GeoFeature>, maxValues: Int = 40): List<String> {
    val values = mutableMapOf<String, MutableSet<String>>()
    val counts = mutableMapOf<String, Int>()
    for (f in features) for ((k, v) in f.properties) {
        if (k in NOT_CATEGORIES) continue
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null } ?: continue
        values.getOrPut(k) { mutableSetOf() }.add(text)
        counts[k] = (counts[k] ?: 0) + 1
    }
    return counts.keys.filter { (values[it]?.size ?: 0) <= maxValues }.sortedByDescending { counts[it] }
}

private val NOT_CATEGORIES = setOf(
    Props.KIND, Props.TITLE, Props.NOTE, Props.STARTED_AT, Props.ENDED_AT, Props.ASSIGNEE, Props.ASSIGNEE_NAME,
    OutputProps.TOOL, OutputProps.FILL, OutputProps.STROKE,
)

fun GeoFeature.number(field: String): Double? = properties.double(field)?.takeIf { it.isFinite() }

/**
 * Flat metric coordinates around an origin, good to well under a metre over
 * the few kilometres of a field area.
 */
internal class LocalPlane(origin: LngLat) {
    private val lng0 = origin.lng
    private val lat0 = origin.lat
    private val mx = METERS_PER_DEGREE * cos(lat0 * PI / 180)

    fun x(p: LngLat) = (p.lng - lng0) * mx
    fun y(p: LngLat) = (p.lat - lat0) * METERS_PER_DEGREE
    fun toLngLat(x: Double, y: Double) = LngLat(lng0 + x / mx, lat0 + y / METERS_PER_DEGREE)

    companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}

/** Equal-interval colour classes over [min]..[max]. */
internal class ColorRamp(private val min: Double, private val max: Double, private val colors: List<String> = VIRIDIS) {
    fun color(v: Double): String {
        if (max <= min) return colors[colors.size / 2]
        val i = ((v - min) / (max - min) * colors.size).toInt().coerceIn(0, colors.size - 1)
        return colors[i]
    }

    fun legend(format: (Double) -> String): List<LegendEntry> = colors.indices.map { i ->
        val lo = min + (max - min) * i / colors.size
        val hi = min + (max - min) * (i + 1) / colors.size
        LegendEntry(colors[i], "${format(lo)} – ${format(hi)}")
    }

    companion object {
        val VIRIDIS = listOf("#440154", "#414487", "#2A788E", "#22A884", "#7AD151", "#FDE725")
    }
}

internal fun PropertiesBuilder.style(fill: String? = null, fillOpacity: Double? = null, stroke: String? = null, strokeWidth: Double? = null) {
    fill?.let { this[OutputProps.FILL] = it }
    fillOpacity?.let { this[OutputProps.FILL_OPACITY] = it }
    stroke?.let { this[OutputProps.STROKE] = it }
    strokeWidth?.let { this[OutputProps.STROKE_WIDTH] = it }
}

internal fun fmt(v: Double, digits: Int = 2): String = String.format(java.util.Locale.ROOT, "%.${digits}f", v)
