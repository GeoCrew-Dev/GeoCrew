package org.geocrew.feature.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import org.geocrew.core.map.MapCapture
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.MapPrint
import org.geocrew.core.model.MapScale
import java.io.OutputStream

/** What goes on a printed map besides the picture. */
internal data class MapPrintSpec(
    val title: String,
    /** Who made it and when, e.g. "制图：老王 · 2026-11-25". */
    val byline: String,
    /** The base map's name and attribution, which must stay on the sheet. */
    val source: String,
    /** Legend swatches as (#RRGGBB, label), with what they colour by. */
    val legend: List<Pair<String, String>> = emptyList(),
    val legendTitle: String = "图例",
    /** Kinds of geological line on the map. */
    val lines: List<org.geocrew.core.model.LineStyle> = emptyList(),
    /** Record names to write next to their places. */
    val labels: List<Pair<LngLat, String>> = emptyList(),
)

/**
 * Lays the map view out on one A4 sheet like a printed map: the picture at
 * a round scale it follows exactly in its centre, a frame with longitude and
 * latitude ticks and crosses, a north arrow, a scale bar and its ratio, the
 * legend, record names, and the base map's source. Landscape or portrait
 * follows the picture. The map must face north.
 */
internal class MapPrintPdf {
    fun write(capture: MapCapture, spec: MapPrintSpec, out: OutputStream) {
        val bmp = capture.bitmap
        val landscape = bmp.width >= bmp.height
        val pageW = if (landscape) 842 else 595
        val pageH = if (landscape) 595 else 842
        val keys = spec.legend.size + spec.lines.size
        val legendW = if (keys > 0 && landscape) 120f else 0f
        val legendH = if (keys > 0 && !landscape) 18f + 14f * ((keys + 2) / 3) else 0f
        // Room for the title above, tick labels on the left and below, and the scale and notes at the bottom.
        val box = RectF(MARGIN + 46f, MARGIN + 40f, pageW - MARGIN - 10f - legendW, pageH - MARGIN - 64f - legendH)

        val b = capture.bounds
        val midLat = (b.south + b.north) / 2
        val midLng = (b.west + b.east) / 2
        val groundW = Geodesy.distance(LngLat(b.west, midLat), LngLat(b.east, midLat))
        require(groundW > 0) { "地图范围无效" }
        val heightRatio = bmp.height.toDouble() / bmp.width
        // The largest round scale at which the whole view fits the box, both ways.
        var scale = MapPrint.scaleToFit(groundW, box.width() / PT_PER_MM)
        while (MapPrint.paperMm(groundW, scale) * PT_PER_MM * heightRatio > box.height() + 0.5) {
            scale = MapPrint.SCALES.firstOrNull { it > scale } ?: (scale + 1_000_000)
        }
        val wPt = (MapPrint.paperMm(groundW, scale) * PT_PER_MM).toFloat()
        val hPt = (wPt * heightRatio).toFloat()
        val frame = RectF(box.left + (box.width() - wPt) / 2, box.top, box.left + (box.width() - wPt) / 2 + wPt, box.top + hPt)
        val k = wPt / bmp.width // points per picture pixel
        fun place(p: LngLat): Pair<Float, Float> = capture.toPixel(p).let { (x, y) -> frame.left + x * k to frame.top + y * k }

        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
            val c = page.canvas
            c.drawText(spec.title, pageW / 2f - titlePaint.measureText(spec.title) / 2, MARGIN + 18f, titlePaint)

            c.save()
            c.clipRect(frame)
            c.drawBitmap(bmp, null, frame, Paint(Paint.FILTER_BITMAP_FLAG))
            val lngTicks = MapPrint.ticks(b.west, b.east, lat = false)
            val latTicks = MapPrint.ticks(b.south, b.north, lat = true)
            // Graticule crosses where the ticks meet, as on a topographic sheet.
            for (x in lngTicks) for (y in latTicks) {
                val (px, py) = place(LngLat(x.value, y.value))
                c.drawLine(px - 5f, py, px + 5f, py, cross)
                c.drawLine(px, py - 5f, px, py + 5f, cross)
            }
            for ((p, name) in spec.labels) {
                val (px, py) = place(p)
                if (!frame.contains(px, py)) continue
                c.drawText(name, px + 4f, py - 3f, haloPaint)
                c.drawText(name, px + 4f, py - 3f, namePaint)
            }
            northArrow(c, frame.right - 22f, frame.top + 12f)
            c.restore()
            c.drawRect(frame, framePaint)

            for (t in lngTicks) {
                val x = place(LngLat(t.value, midLat)).first
                if (x <= frame.left + 1 || x >= frame.right - 1) continue
                c.drawLine(x, frame.top, x, frame.top - 4f, framePaint)
                c.drawLine(x, frame.bottom, x, frame.bottom + 4f, framePaint)
                c.drawText(t.label, x - tickPaint.measureText(t.label) / 2, frame.bottom + 13f, tickPaint)
            }
            for (t in latTicks) {
                val y = place(LngLat(midLng, t.value)).second
                if (y <= frame.top + 1 || y >= frame.bottom - 1) continue
                c.drawLine(frame.left, y, frame.left - 4f, y, framePaint)
                c.drawLine(frame.right, y, frame.right + 4f, y, framePaint)
                c.drawText(t.label, frame.left - 6f - tickPaint.measureText(t.label), y + 3f, tickPaint)
            }

            // Scale: the ratio, then a bar of alternate black and white parts.
            val baseY = frame.bottom + 34f
            val ratio = "比例尺 " + MapScale.ratioLabel(scale)
            c.drawText(ratio, frame.left, baseY, labelPaint)
            scaleBar(c, frame.left + labelPaint.measureText(ratio) + 14f, baseY, scale, maxMm = 60.0)

            var noteY = baseY + 16f
            for (line in listOfNotNull(
                "经纬度（WGS84 / CGCS2000），按网络墨卡托投影显示，比例尺在图幅中部准确" + if (scale > 1_000_000) "，范围较大时边缘有变形" else "",
                spec.source.takeIf { it.isNotBlank() }?.let { "底图：$it" },
                spec.byline + " · 同勘 GeoCrew 生成",
            )) {
                c.drawText(line, frame.left, noteY, smallPaint)
                noteY += 11f
            }

            if (legendW > 0) legendColumn(c, spec, frame.right + 18f, frame.top + 8f, pageW - MARGIN)
            else if (legendH > 0) legendRows(c, spec, frame.left, pageH - MARGIN - legendH + 10f, frame.right)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun northArrow(c: Canvas, x: Float, y: Float) {
        val p = Path().apply {
            moveTo(x, y)
            lineTo(x + 6f, y + 18f)
            lineTo(x, y + 13f)
            lineTo(x - 6f, y + 18f)
            close()
        }
        c.drawPath(p, blackFill)
        c.drawText("N", x - labelPaint.measureText("N") / 2, y + 28f, labelPaint)
    }

    private fun scaleBar(c: Canvas, x: Float, y: Float, scale: Long, maxMm: Double) {
        val bar = MapPrint.bar(scale, maxMm)
        if (bar.meters <= 0) return
        val parts = 4
        val w = (bar.dp * PT_PER_MM).toFloat()
        val part = w / parts
        for (i in 0 until parts) {
            val r = RectF(x + i * part, y - 4f, x + (i + 1) * part, y)
            c.drawRect(r, if (i % 2 == 0) blackFill else whiteFill)
            c.drawRect(r, framePaint)
        }
        c.drawText("0", x - 2f, y + 9f, smallPaint)
        val end = MapScale.lengthLabel(bar.meters)
        c.drawText(end, x + w - smallPaint.measureText(end) / 2, y + 9f, smallPaint)
    }

    private fun legendColumn(c: Canvas, spec: MapPrintSpec, x: Float, top: Float, right: Float) {
        c.drawText(if (spec.legend.isEmpty()) "图例" else spec.legendTitle, x, top, labelPaint)
        var y = top + 16f
        for ((hex, name) in spec.legend.take(30)) {
            swatch(c, x, y, hex)
            c.drawText(fit(name, right - x - 22f), x + 20f, y, labelPaint)
            y += 14f
        }
        for (l in spec.lines) {
            lineSwatch(c, x, y, l)
            c.drawText(l.label, x + 20f, y, labelPaint)
            y += 14f
        }
    }

    private fun legendRows(c: Canvas, spec: MapPrintSpec, left: Float, top: Float, right: Float) {
        c.drawText(if (spec.legend.isEmpty()) "图例" else spec.legendTitle, left, top, labelPaint)
        val col = (right - left) / 3
        val items = spec.legend.take(30).map { it.second to { x: Float, y: Float -> swatch(c, x, y, it.first) } } +
            spec.lines.map { l -> l.label to { x: Float, y: Float -> lineSwatch(c, x, y, l) } }
        items.forEachIndexed { i, (name, draw) ->
            val x = left + (i % 3) * col
            val y = top + 16f + (i / 3) * 14f
            draw(x, y)
            c.drawText(fit(name, col - 24f), x + 20f, y, labelPaint)
        }
    }

    private fun swatch(c: Canvas, x: Float, y: Float, hex: String) {
        swatchFill.color = runCatching { Color.parseColor(hex) }.getOrDefault(Color.GRAY)
        val r = RectF(x, y - 8f, x + 14f, y + 1f)
        c.drawRect(r, swatchFill)
        c.drawRect(r, framePaint)
    }

    private fun lineSwatch(c: Canvas, x: Float, y: Float, l: org.geocrew.core.model.LineStyle) {
        val p = stroke(if (l.fault) 1.4f else 0.9f, if (l.fault) 0xFFC62828.toInt() else Color.BLACK)
        if (l.dashed) p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 2f), 0f)
        c.drawLine(x, y - 3.5f, x + 14f, y - 3.5f, p)
    }

    private fun fit(s: String, width: Float): String {
        if (labelPaint.measureText(s) <= width) return s
        var t = s
        while (t.isNotEmpty() && labelPaint.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    private val titlePaint = text(15f, bold = true)
    private val labelPaint = text(8f)
    private val tickPaint = text(7f)
    private val smallPaint = text(6.5f, color = 0xFF5F6368.toInt())
    private val namePaint = text(6.5f)
    private val haloPaint = text(6.5f, color = Color.WHITE).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val framePaint = stroke(0.7f, Color.BLACK)
    private val cross = stroke(0.5f, 0xFF424242.toInt())
    private val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val swatchFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private companion object {
        const val MARGIN = 28f
        const val PT_PER_MM = 72.0 / 25.4

        fun text(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        fun stroke(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.color = color
        }
    }
}
