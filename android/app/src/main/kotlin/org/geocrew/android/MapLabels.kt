package org.geocrew.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression

/**
 * Point labels without glyph files: MapLibre draws text only from glyphs it
 * downloads, so each label is drawn by Android into a small image and shown as
 * an icon instead. Works offline, for any script.
 */
internal object MapLabels {
    /** The property that names a feature's label image. */
    const val PROPERTY = "_label"

    private const val PREFIX = "label:"

    // The most characters a label shows; longer values end in an ellipsis.
    private const val MOST = 40

    /** What a label shows of [value]: its first line, cut to [MOST] characters; null when there is nothing to show. */
    internal fun shown(value: String): String? {
        val line = value.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return if (line.length > MOST) line.take(MOST - 1).trimEnd() + "…" else line
    }

    /**
     * [features] with each one's [field] as its label (none with null), a
     * [second] line under it in smaller type (who last edited it, say), and
     * the label images added to [style] that it does not have yet.
     */
    fun label(features: JsonArray, field: String?, style: Style, density: Float, second: (JsonObject) -> String? = { null }): JsonArray = JsonArray(
        features.map { feature ->
            val f = feature as? JsonObject ?: return@map feature
            val properties = f["properties"] as? JsonObject ?: return@map feature
            val image = image(textOf(properties, field), second(f)?.let(::shown), style, density) ?: return@map feature
            JsonObject(f + ("properties" to JsonObject(properties + (PROPERTY to JsonPrimitive(image)))))
        },
    )

    // What [field] of [properties] shows as a label; null for none.
    private fun textOf(properties: JsonObject?, field: String?): String? =
        field?.let { (properties?.get(it) as? JsonPrimitive)?.contentOrNull?.let(::shown) }

    // The name of the label image of [text] over [under], added to [style] the first time; null with neither.
    private fun image(text: String?, under: String?, style: Style, density: Float): String? {
        if (text == null && under == null) return null
        val image = PREFIX + text.orEmpty() + (under?.let { "\n$it" } ?: "")
        if (style.getImage(image) == null) style.addImage(image, draw(text, under, density))
        return image
    }

    /** The property that turns a line's label to run with the line, degrees clockwise. */
    const val ROTATE = "_label_rotate"

    /** The property that says what a label names: "line" or "area". */
    const val KIND = "_label_kind"

    /**
     * Labels for the lines and areas of [features] that have a [field] value,
     * as points: a line's in the middle of its length, turned to run with it
     * and kept upright; an area's at its centre. The images go into [style];
     * [tag] adds what the map needs to each (its layer's opacity, say).
     */
    fun shapes(
        features: JsonArray,
        field: String?,
        style: Style,
        density: Float,
        second: (JsonObject) -> String? = { null },
        tag: (JsonObject) -> JsonObject,
    ): List<JsonObject> =
        features.mapNotNull { feature ->
            val f = feature as? JsonObject ?: return@mapNotNull null
            val (at, rotate, kind) = anchor(f["geometry"] as? JsonObject) ?: return@mapNotNull null
            val image = image(textOf(f["properties"] as? JsonObject, field), second(f)?.let(::shown), style, density) ?: return@mapNotNull null
            tag(
                buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") {
                        put(PROPERTY, image)
                        put(ROTATE, rotate)
                        put(KIND, kind)
                    }
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") { add(at.first); add(at.second) }
                    }
                },
            )
        }

    /**
     * Where a line's or an area's label goes: (longitude, latitude), how far
     * it turns (degrees clockwise) and its kind. A line's is the middle of its
     * length (of its longest part), along the side there; an area's the centre
     * of its outer ring (of its biggest part), unturned. Null for anything else.
     */
    internal fun anchor(geometry: JsonObject?): Triple<Pair<Double, Double>, Double, String>? {
        val type = (geometry?.get("type") as? JsonPrimitive)?.contentOrNull ?: return null
        val coordinates = geometry["coordinates"] as? JsonArray ?: return null
        fun positions(e: JsonElement?): List<Pair<Double, Double>> = (e as? JsonArray).orEmpty().mapNotNull { p ->
            val xy = p as? JsonArray ?: return@mapNotNull null
            val x = (xy.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val y = (xy.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            x to y
        }
        // Lengths and areas in degrees, the longitude shrunk by the latitude's cosine: near enough to choose and place by.
        fun scale(line: List<Pair<Double, Double>>) = cos(Math.toRadians(line.map { it.second }.average()))
        fun length(line: List<Pair<Double, Double>>): Double {
            val k = scale(line)
            return line.zipWithNext().sumOf { (a, b) -> hypot((b.first - a.first) * k, b.second - a.second) }
        }
        return when (type) {
            "LineString", "MultiLineString" -> {
                val line = if (type == "LineString") positions(coordinates) else coordinates.map(::positions).maxByOrNull(::length).orEmpty()
                if (line.size < 2) return null
                val k = scale(line)
                var left = length(line) / 2
                for ((a, b) in line.zipWithNext()) {
                    val step = hypot((b.first - a.first) * k, b.second - a.second)
                    if (step >= left && step > 0) {
                        val t = left / step
                        val at = (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
                        // On a conformal map the side runs at this angle from east, counterclockwise; MapLibre turns clockwise.
                        var rotate = -Math.toDegrees(atan2(b.second - a.second, (b.first - a.first) * k))
                        if (rotate > 90) rotate -= 180
                        if (rotate < -90) rotate += 180
                        return Triple(at, rotate, "line")
                    }
                    left -= step
                }
                null
            }
            "Polygon", "MultiPolygon" -> {
                val ring = if (type == "Polygon") positions(coordinates.firstOrNull()) else coordinates.map { positions((it as? JsonArray)?.firstOrNull()) }.maxByOrNull { abs(signedArea(it)) }.orEmpty()
                if (ring.size < 3) return null
                val area = signedArea(ring)
                if (area == 0.0) {
                    val open = if (ring.first() == ring.last()) ring.dropLast(1) else ring
                    return Triple(open.map { it.first }.average() to open.map { it.second }.average(), 0.0, "area")
                }
                // The centroid of the ring, by the shoelace formula.
                var cx = 0.0
                var cy = 0.0
                for ((a, b) in ring.zipWithNext() + (ring.last() to ring.first())) {
                    val cross = a.first * b.second - b.first * a.second
                    cx += (a.first + b.first) * cross
                    cy += (a.second + b.second) * cross
                }
                Triple((cx / (6 * area)) to (cy / (6 * area)), 0.0, "area")
            }
            else -> null
        }
    }

    /**
     * The turn for line labels when the map faces [bearing]: each label's own,
     * or half a turn more where that would leave it upside down on the screen.
     */
    fun upright(bearing: Double): Expression {
        val rotate = Expression.get(ROTATE)
        // The label's angle on the screen, from -180 to 180.
        val onScreen = Expression.subtract(Expression.mod(Expression.sum(Expression.subtract(rotate, Expression.literal(bearing)), Expression.literal(540)), Expression.literal(360)), Expression.literal(180))
        return Expression.switchCase(
            Expression.any(Expression.gt(onScreen, Expression.literal(90)), Expression.lt(onScreen, Expression.literal(-90))),
            Expression.sum(rotate, Expression.literal(180)),
            rotate,
        )
    }

    private fun signedArea(ring: List<Pair<Double, Double>>): Double =
        if (ring.size < 3) 0.0 else (ring.zipWithNext() + (ring.last() to ring.first())).sumOf { (a, b) -> a.first * b.second - b.first * a.second } / 2

    /**
     * The mark of a feature with a conflict to settle (protocol v1 6.5): an
     * amber disc with a white exclamation mark and a white rim. GeoCrew's own
     * mark, drawn over the map, not a field of the feature.
     */
    fun conflictMark(density: Float): Bitmap {
        val size = ceil(18f * density).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val r = size / 2f
        canvas.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(r, r, r - 1.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 143, 0) })
        val bang = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val metrics = bang.fontMetrics
        canvas.drawText("!", r, r - (metrics.ascent + metrics.descent) / 2, bang)
        return bitmap
    }

    // [text] in white bold over [under] in smaller, plainer type, each with a dark halo to read on any map.
    private fun draw(text: String?, under: String?, density: Float): Bitmap {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f * density
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        val small = Paint(fill).apply {
            textSize = 11f * density
            typeface = Typeface.DEFAULT
            color = Color.rgb(230, 230, 230)
        }
        fun halo(paint: Paint) = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val lines = listOfNotNull(text?.let { it to fill }, under?.let { it to small })
        val margin = 3f * density
        val bitmap = createBitmap(
            ceil(lines.maxOf { (t, paint) -> paint.measureText(t) } + 2 * margin).toInt(),
            ceil(lines.sumOf { (_, paint) -> (paint.fontMetrics.descent - paint.fontMetrics.ascent).toDouble() } + 2 * margin).toInt(),
        )
        val canvas = Canvas(bitmap)
        var top = margin
        for ((t, paint) in lines) {
            val metrics = paint.fontMetrics
            canvas.drawText(t, margin, top - metrics.ascent, halo(paint))
            canvas.drawText(t, margin, top - metrics.ascent, paint)
            top += metrics.descent - metrics.ascent
        }
        return bitmap
    }
}
