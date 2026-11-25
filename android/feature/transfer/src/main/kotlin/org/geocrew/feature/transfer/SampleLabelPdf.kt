package org.geocrew.feature.transfer

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.geocrew.core.formats.LabelSheet
import org.geocrew.core.formats.SampleLabel
import java.io.OutputStream

/**
 * Prints sample labels on a [LabelSheet] (A4, 3 × 8 by default): the number
 * large, then type and lithology, station and date, position and project,
 * with a QR code of the number so a lab or store can scan it. The QR code
 * is drawn as vector squares, so it stays sharp at any print size.
 */
internal class SampleLabelPdf(private val sheet: LabelSheet = LabelSheet()) {
    private val noPaint = text(13f, bold = true)
    private val linePaint = text(8f)
    private val greyPaint = text(7f, color = GREY)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.4f; color = LIGHT }
    private val ink = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

    /** Writes all [labels] to [out]; returns the number of pages. */
    fun write(labels: List<SampleLabel>, out: OutputStream): Int {
        val doc = PdfDocument()
        try {
            var page: PdfDocument.Page? = null
            var pageNo = -1
            labels.forEachIndexed { i, label ->
                val (p, box) = sheet.cell(i)
                if (p != pageNo) {
                    page?.let(doc::finishPage)
                    pageNo = p
                    page = doc.startPage(PdfDocument.PageInfo.Builder(sheet.pageWidth.toInt(), sheet.pageHeight.toInt(), p + 1).create())
                }
                draw(page!!.canvas, label, box)
            }
            page?.let(doc::finishPage)
            doc.writeTo(out)
            return pageNo + 1
        } finally {
            doc.close()
        }
    }

    private fun draw(c: android.graphics.Canvas, l: SampleLabel, box: FloatArray) {
        val (left, top, right, bottom) = box.toList()
        c.drawRect(left, top, right, bottom, border)
        val pad = 7f
        val qr = minOf(bottom - top - 2 * pad, 58f)
        val textRight = right - pad - qr - 6f
        val width = textRight - left - pad
        var y = top + pad + 12f
        c.drawText(fit(l.no, noPaint, width), left + pad, y, noPaint)
        y += 4f
        for ((s, paint) in listOf(l.kind to linePaint, l.station to linePaint, l.position to linePaint, l.project to greyPaint)) {
            if (s.isBlank()) continue
            y += paint.textSize + 3f
            if (y > bottom - pad) break
            c.drawText(fit(s, paint, width), left + pad, y, paint)
        }
        qr(c, l.no, right - pad - qr, top + (bottom - top - qr) / 2, qr)
    }

    private fun qr(c: android.graphics.Canvas, content: String, x: Float, y: Float, size: Float) {
        val m = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        val cell = size / m.width
        for (row in 0 until m.height) {
            for (col in 0 until m.width) {
                // Slight overlap avoids hairline gaps between squares in some viewers.
                if (m[col, row]) c.drawRect(x + col * cell, y + row * cell, x + (col + 1) * cell + 0.05f, y + (row + 1) * cell + 0.05f, ink)
            }
        }
    }

    private fun fit(s: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(s, paint, width, TextUtils.TruncateAt.END).toString()

    private companion object {
        const val GREY = 0xFF5F6368.toInt()
        const val LIGHT = 0xFFDADCE0.toInt()

        fun text(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }
}
