package org.geocrew.feature.record

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import java.io.File
import kotlin.math.max

/**
 * Draws a record's details on a copy of a photo, the way field cameras
 * stamp them, and saves the copy to the shared Pictures/GeoCrew folder.
 * The original attachment is never changed.
 */
internal object PhotoStamp {
    private const val MAX_SIDE = 3200

    fun render(file: File, lines: List<String>): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }) ?: return null
        val turn = PhotoExif.rotation(file)
        if (turn != 0) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true).also { bmp.recycle() }

        val canvas = Canvas(bmp)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = max(18f, minOf(bmp.width, bmp.height) / 26f)
            typeface = Typeface.DEFAULT
            setShadowLayer(textSize / 10, 0f, 0f, Color.BLACK)
        }
        val gap = text.textSize * 0.35f
        val pad = text.textSize * 0.6f
        val bandHeight = pad * 2 + lines.size * text.textSize + (lines.size - 1) * gap
        val band = Paint().apply { color = Color.argb(110, 0, 0, 0) }
        canvas.drawRect(0f, bmp.height - bandHeight, bmp.width.toFloat(), bmp.height.toFloat(), band)
        var y = bmp.height - bandHeight + pad + text.textSize * 0.85f
        for (line in lines) {
            canvas.drawText(fit(line, text, bmp.width - pad * 2), pad, y, text)
            y += text.textSize + gap
        }
        return bmp
    }

    /** Renders and saves; true when the picture landed in the gallery. */
    fun save(context: Context, file: File, lines: List<String>, name: String): Boolean {
        val bmp = render(file, lines) ?: return false
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeoCrew")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val written = runCatching {
                resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true
            }.getOrDefault(false)
            if (!written) {
                resolver.delete(uri, null, null)
                return false
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return true
        } finally {
            bmp.recycle()
        }
    }

    private fun fit(line: String, paint: Paint, width: Float): String {
        if (paint.measureText(line) <= width) return line
        var s = line
        while (s.length > 1 && paint.measureText("$s…") > width) s = s.dropLast(1)
        return "$s…"
    }
}
