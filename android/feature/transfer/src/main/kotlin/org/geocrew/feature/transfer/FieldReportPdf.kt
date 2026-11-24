package org.geocrew.feature.transfer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.geocrew.core.formats.FieldReport
import java.io.OutputStream
import java.time.format.DateTimeFormatter

/**
 * Lays a [FieldReport] out on A4 pages with the platform's [PdfDocument]:
 * a title block, one heading per day, and per record its facts as a
 * two-column table, the note, and up to [photosPerEntry] photos with their
 * captions. Plain text and bitmaps only, so it needs no PDF library and
 * the output opens in any viewer.
 */
internal class FieldReportPdf(
    /** Decodes a photo by hash at about the given pixel size, upright; null to skip it. */
    private val photo: (sha: String, maxSide: Int) -> Bitmap?,
    /** One line under a photo, e.g. its shooting direction; null for none. */
    private val caption: (featureId: String, sha: String) -> String? = { _, _ -> null },
    private val photosPerEntry: Int = 3,
) {
    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 48f
    private val contentWidth get() = pageWidth - 2 * margin
    private val bottom get() = pageHeight - margin - 18f

    private val title = text(18f, bold = true)
    private val subtitle = text(10f, color = GREY)
    private val dayPaint = text(13f, bold = true)
    private val headingPaint = text(11f, bold = true)
    private val keyPaint = text(9f, color = GREY)
    private val valuePaint = text(9.5f)
    private val notePaint = text(9.5f)
    private val captionPaint = text(8f, color = GREY)
    private val footerPaint = text(8f, color = GREY)
    private val rule = Paint().apply { color = LIGHT; strokeWidth = 0.7f }

    private lateinit var doc: PdfDocument
    private var page: PdfDocument.Page? = null
    private var pageNo = 0
    private var y = 0f
    private val canvas: Canvas get() = page!!.canvas

    /** Writes the whole report to [out]; returns the number of pages. */
    fun write(report: FieldReport, out: OutputStream): Int {
        doc = PdfDocument()
        pageNo = 0
        try {
            newPage()
            block(report.title, title, contentWidth)
            y += 4f
            block(report.subtitle, subtitle, contentWidth)
            y += 10f
            if (report.days.isEmpty()) block("没有符合条件的记录。", notePaint, contentWidth)
            for (day in report.days) {
                need(48f)
                y += 6f
                block(DAY.format(day.date) + "  ·  ${day.entries.size} 条", dayPaint, contentWidth)
                canvas.drawLine(margin, y + 2f, margin + contentWidth, y + 2f, rule)
                y += 10f
                day.entries.forEach { entry(it) }
            }
            finishPage()
            doc.writeTo(out)
            return pageNo
        } finally {
            doc.close()
        }
    }

    private fun entry(e: FieldReport.Entry) {
        val heading = layout(e.heading, headingPaint, contentWidth)
        val firstFact = e.facts.firstOrNull()?.let { factHeight(it) } ?: 0f
        need(heading.height + firstFact + 8f)
        draw(heading, margin)
        y += heading.height + 3f
        for (f in e.facts) {
            val h = factHeight(f)
            need(h)
            draw(layout(f.first, keyPaint, KEY_WIDTH), margin + 8f)
            draw(layout(f.second, valuePaint, contentWidth - KEY_WIDTH - 16f), margin + 8f + KEY_WIDTH + 8f)
            y += h
        }
        e.note?.let { note ->
            y += 2f
            // Long notes may run over a page; draw them paragraph by paragraph.
            for (para in note.split('\n')) block(para.ifBlank { " " }, notePaint, contentWidth - 8f, x = margin + 8f)
        }
        photos(e)
        y += 12f
    }

    private fun photos(e: FieldReport.Entry) {
        val shas = e.photos.take(photosPerEntry)
        if (shas.isEmpty()) return
        val gap = 8f
        val cell = (contentWidth - 8f - gap * (photosPerEntry - 1)) / photosPerEntry
        val boxHeight = cell * 0.75f
        val captionHeight = 22f
        need(boxHeight + captionHeight + 6f)
        y += 6f
        shas.forEachIndexed { i, sha ->
            val left = margin + 8f + i * (cell + gap)
            val bmp = runCatching { photo(sha, PHOTO_PX) }.getOrNull()
            if (bmp == null) {
                canvas.drawRect(RectF(left, y, left + cell, y + boxHeight), rule.apply { style = Paint.Style.STROKE })
                rule.style = Paint.Style.FILL
                draw(layout("照片不在本机", captionPaint, cell), left + 4f, y + boxHeight / 2 - 5f)
            } else {
                val scale = minOf(cell / bmp.width, boxHeight / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                val dst = RectF(left + (cell - w) / 2, y + (boxHeight - h) / 2, left + (cell + w) / 2, y + (boxHeight + h) / 2)
                canvas.drawBitmap(bmp, null, dst, BITMAP)
                bmp.recycle()
            }
            caption(e.featureId, sha)?.let { draw(layout(it, captionPaint, cell), left, y + boxHeight + 3f) }
        }
        y += boxHeight + captionHeight
        val more = e.photos.size - shas.size
        if (more > 0) block("另有 $more 张照片未列出", captionPaint, contentWidth - 8f, x = margin + 8f)
    }

    private fun factHeight(f: Pair<String, String>) = maxOf(
        layout(f.first, keyPaint, KEY_WIDTH).height,
        layout(f.second, valuePaint, contentWidth - KEY_WIDTH - 16f).height,
    ) + 2f

    /** Draws wrapped [text], breaking to a new page line by line when it does not fit. */
    private fun block(text: String, paint: TextPaint, width: Float, x: Float = margin) {
        val l = layout(text, paint, width)
        if (y + l.height <= bottom) {
            draw(l, x)
            y += l.height
            return
        }
        for (line in 0 until l.lineCount) {
            val h = (l.getLineBottom(line) - l.getLineTop(line)).toFloat()
            need(h)
            val s = text.substring(l.getLineStart(line), l.getLineEnd(line))
            canvas.drawText(s.trimEnd('\n'), x, y - paint.ascent(), paint)
            y += h
        }
    }

    private fun need(height: Float) {
        if (y + height > bottom) {
            finishPage()
            newPage()
        }
    }

    private fun newPage() {
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
        y = margin
    }

    private fun finishPage() {
        val p = page ?: return
        val label = "第 $pageNo 页"
        p.canvas.drawText(label, (pageWidth - footerPaint.measureText(label)) / 2, pageHeight - margin / 2, footerPaint)
        doc.finishPage(p)
        page = null
    }

    private fun layout(text: String, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(1.5f, 1f)
            .setIncludePad(false)
            .build()

    private fun draw(l: StaticLayout, x: Float, top: Float = y) {
        canvas.save()
        canvas.translate(x, top)
        l.draw(canvas)
        canvas.restore()
    }

    private companion object {
        const val GREY = 0xFF5F6368.toInt()
        const val LIGHT = 0xFFDADCE0.toInt()
        const val KEY_WIDTH = 64f
        const val PHOTO_PX = 900
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")
        val BITMAP = Paint(Paint.FILTER_BITMAP_FLAG)

        fun text(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
}
