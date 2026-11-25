package org.geocrew.core.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.geocrew.core.ui.MapColors
import org.maplibre.android.maps.Style

/**
 * Strike-and-dip symbols as map icons, drawn at runtime so they need no
 * sprite sheet. The symbol icon is drawn for north and rotated by the map
 * to the dip direction: the long bar is the strike, the short tick points
 * down dip. The dip angle is a separate, unrotated icon per value, added
 * only for values that occur, because raster base maps carry no glyphs.
 */
internal object AttitudeSymbols {
    const val INCLINED = "gc-att"
    const val HORIZONTAL = "gc-att-h"
    const val VERTICAL = "gc-att-v"
    const val DIP_PREFIX = "gc-dip-"
    /** Lineation arrow, drawn pointing north and rotated to the trend. */
    const val LINEATION = "gc-lin"
    const val PLUNGE_PREFIX = "gc-pl-"

    private val density get() = Resources.getSystem().displayMetrics.density

    fun install(style: Style) {
        style.addImage(INCLINED, symbol(ticks = 1))
        style.addImage(VERTICAL, symbol(ticks = 2))
        style.addImage(HORIZONTAL, horizontal())
        style.addImage(LINEATION, arrow())
    }

    /** Adds labels for plunge values the style does not have yet. */
    fun ensurePlungeLabels(style: Style, plunges: Collection<Int>) {
        for (d in plunges) {
            val id = PLUNGE_PREFIX + d
            if (style.getImage(id) == null) style.addImage(id, label(d.toString(), MapColors.LINEATION))
        }
    }

    /** An arrow from the centre toward the top: the point sits at its tail, the head shows the trend. */
    private fun arrow(): Bitmap {
        val s = (44 * density).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val mid = s / 2f
        val len = 19 * density
        val head = 6 * density
        val tip = mid - len
        for (p in listOf(stroke(5 * density, Color.WHITE), stroke(2.2f * density, Color.parseColor(MapColors.LINEATION)))) {
            c.drawLine(mid, mid, mid, tip, p)
            c.drawLine(mid, tip, mid - head * 0.6f, tip + head, p)
            c.drawLine(mid, tip, mid + head * 0.6f, tip + head, p)
        }
        return bmp
    }

    /** Adds labels for dip values the style does not have yet. */
    fun ensureDipLabels(style: Style, dips: Collection<Int>) {
        for (d in dips) {
            val id = DIP_PREFIX + d
            if (style.getImage(id) == null) style.addImage(id, label(d.toString()))
        }
    }

    private fun stroke(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private fun symbol(ticks: Int): Bitmap {
        val s = (36 * density).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val mid = s / 2f
        val half = 15 * density
        val tick = 8 * density
        // A white halo under the colour keeps the symbol readable on imagery.
        for (p in listOf(stroke(5 * density, Color.WHITE), stroke(2.5f * density, Color.parseColor(MapColors.ATTITUDE)))) {
            c.drawLine(mid - half, mid, mid + half, mid, p)
            c.drawLine(mid, mid, mid, mid - tick, p)
            if (ticks == 2) c.drawLine(mid, mid, mid, mid + tick, p)
        }
        return bmp
    }

    private fun horizontal(): Bitmap {
        val s = (28 * density).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val mid = s / 2f
        val r = 9 * density
        for (p in listOf(stroke(5 * density, Color.WHITE), stroke(2.2f * density, Color.parseColor(MapColors.ATTITUDE)))) {
            c.drawCircle(mid, mid, r, p)
            c.drawLine(mid - r, mid, mid + r, mid, p)
            c.drawLine(mid, mid - r, mid, mid + r, p)
        }
        return bmp
    }

    private fun label(text: String, colour: String = MapColors.ATTITUDE): Bitmap {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(colour)
            textSize = 12 * density
            typeface = Typeface.DEFAULT_BOLD
        }
        val halo = Paint(fill).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            strokeJoin = Paint.Join.ROUND
        }
        val pad = 2 * density
        val w = (fill.measureText(text) + 2 * pad).toInt() + 1
        val fm = fill.fontMetrics
        val h = (fm.descent - fm.ascent + 2 * pad).toInt() + 1
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val y = pad - fm.ascent
        c.drawText(text, pad, y, halo)
        c.drawText(text, pad, y, fill)
        return bmp
    }
}
