package org.geocrew.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.maplibre.android.maps.Style

/**
 * Point labels without glyph files: MapLibre draws text only from glyphs it
 * downloads, so each label is drawn by Android into a small image and shown as
 * an icon instead. Works offline, for any script.
 */
internal object MapLabels {
    /** The property that names a feature's label image. */
    const val PROPERTY = "_label"

    private const val PREFIX = "label:"

    /**
     * [features] with each one's [field] as its label, and the label images
     * added to [style] that it does not have yet.
     */
    fun label(features: JsonArray, field: String, style: Style, density: Float): JsonArray = JsonArray(
        features.map { feature ->
            val f = feature as? JsonObject ?: return@map feature
            val properties = f["properties"] as? JsonObject ?: return@map feature
            val text = (properties[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@map feature
            val image = PREFIX + text
            if (style.getImage(image) == null) style.addImage(image, draw(text, density))
            JsonObject(f + ("properties" to JsonObject(properties + (PROPERTY to JsonPrimitive(image)))))
        },
    )

    // White text with a dark halo, readable on imagery.
    private fun draw(text: String, density: Float): Bitmap {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f * density
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        val halo = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val metrics = fill.fontMetrics
        val margin = halo.strokeWidth
        val bitmap = createBitmap(
            ceil(fill.measureText(text) + 2 * margin).toInt(),
            ceil(metrics.descent - metrics.ascent + 2 * margin).toInt(),
        )
        val canvas = Canvas(bitmap)
        canvas.drawText(text, margin, margin - metrics.ascent, halo)
        canvas.drawText(text, margin, margin - metrics.ascent, fill)
        return bitmap
    }
}
