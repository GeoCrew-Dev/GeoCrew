package org.geocrew.feature.compass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import org.geocrew.core.formats.LithoPattern
import org.geocrew.core.formats.StratColumn
import java.io.OutputStream
import java.util.Locale

/**
 * Draws a [StratColumn] on A4 pages with the platform's [PdfDocument]: a
 * table of layer number, the column with lithology fills, cumulative and
 * true thickness, and description. Rows are drawn at the column's scale;
 * a column taller than a page carries on over the next, header repeated.
 */
internal class StratColumnPdf {
    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 40f

    private val titlePaint = text(16f, bold = true)
    private val subPaint = text(9f, color = GREY)
    private val headPaint = text(8.5f, bold = true)
    private val cellPaint = text(8f)
    private val smallPaint = text(7f, color = GREY)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.BLACK }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = Color.BLACK }
    private val water = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.rgb(21, 101, 192) }
    private val waterFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(21, 101, 192) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }

    // Table columns, left to right: 层号, 柱状图, 累计厚度, 厚度, 岩性及描述.
    private val xNo = margin
    private val xColumn = xNo + 34f
    private val xCum = xColumn + 90f
    private val xThick = xCum + 50f
    private val xText = xThick + 46f
    private val xEnd = pageWidth - margin
    private val headHeight = 22f

    /** Writes [c] to [out]; returns the number of pages. */
    fun write(c: StratColumn, out: OutputStream): Int {
        val doc = PdfDocument()
        var pageNo = 0
        try {
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNo).create())
            var y = titleBlock(page.canvas, c)
            y = header(page.canvas, y, c)
            for (r in c.rows) {
                val h = (r.heightMm * PT_PER_MM).toFloat()
                if (y + h > pageHeight - margin - 30f && y > margin + headHeight + 1f) {
                    footer(page.canvas, c, pageNo)
                    doc.finishPage(page)
                    page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNo).create())
                    y = header(page.canvas, margin, c)
                }
                row(page.canvas, r, y, h, c)
                y += h
            }
            legend(page.canvas, c, y + 14f)
            footer(page.canvas, c, pageNo)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
        return pageNo
    }

    private fun titleBlock(canvas: Canvas, c: StratColumn): Float {
        var y = margin + 16f
        val hole = c.kind == StratColumn.Kind.BOREHOLE
        canvas.drawText("${c.title} " + if (hole) "钻孔柱状图" else "实测剖面柱状图", margin, y, titlePaint)
        y += 16f
        val facts = buildList {
            add("比例尺 1:${c.scale}")
            if (hole) {
                add("孔深 ${m(c.total)} m")
                add("${c.rows.size} 层")
                add(c.water?.let { "地下水位 ${m(it)} m" } ?: "未见地下水")
            } else {
                add("总厚 ${m(c.total)} m")
                add("${c.rows.size} 层")
                add(if (c.startIsBase) "导线自下而上测" else "导线自上而下测")
                if (c.unknown > 0) add("${c.unknown} 层缺产状，厚度未知")
            }
        }
        canvas.drawText(facts.joinToString("  ·  "), margin, y, subPaint)
        return y + 12f
    }

    private fun header(canvas: Canvas, top: Float, c: StratColumn): Float {
        val r = RectF(xNo, top, xEnd, top + headHeight)
        canvas.drawRect(r, line)
        listOf(xColumn, xCum, xThick, xText).forEach { canvas.drawLine(it, r.top, it, r.bottom, line) }
        val base = top + headHeight / 2 + 3f
        centered(canvas, "层号", xNo, xColumn, base, headPaint)
        centered(canvas, "柱状图", xColumn, xCum, base, headPaint)
        centered(canvas, if (c.kind == StratColumn.Kind.BOREHOLE) "层底深(m)" else "累计厚(m)", xCum, xThick, base, headPaint)
        centered(canvas, "厚度(m)", xThick, xText, base, headPaint)
        canvas.drawText("岩性及描述", xText + 4f, base, headPaint)
        return top + headHeight
    }

    private fun row(canvas: Canvas, r: StratColumn.Row, top: Float, h: Float, c: StratColumn) {
        val bottom = top + h
        canvas.drawRect(xNo, top, xEnd, bottom, line)
        listOf(xColumn, xCum, xThick, xText).forEach { canvas.drawLine(it, top, it, bottom, line) }
        fill(canvas, r.pattern, RectF(xColumn, top, xCum, bottom))
        canvas.drawRect(xColumn, top, xCum, bottom, line)

        val small = h < 11f
        val p = if (small) smallPaint else cellPaint
        val mid = top + h / 2 + p.textSize / 3
        centered(canvas, r.no, xNo, xColumn, mid, p)
        if (c.kind == StratColumn.Kind.BOREHOLE) {
            // Depth of the layer's bottom, at the bottom of the cell, as on borehole logs.
            canvas.drawText(m(r.top), xCum + 3f, bottom - 2f, p)
            c.water?.takeIf { it >= r.base && it < r.top }?.let { w ->
                val y = top + (h * ((w - r.base) / (r.top - r.base))).toFloat()
                canvas.drawLine(xColumn, y, xCum, y, water)
                val tri = Path().apply { moveTo(xCum - 14f, y - 6f); lineTo(xCum - 6f, y - 6f); lineTo(xCum - 10f, y); close() }
                canvas.drawPath(tri, waterFill)
            }
        } else {
            // Cumulative thickness at the top of the layer, as on printed columns.
            canvas.drawText(m(r.top), xCum + 3f, top + p.textSize + 1f, p)
        }
        centered(canvas, r.thickness?.let(::m) ?: "—", xThick, xText, mid, p)

        val text = buildString {
            append(r.lithology.ifEmpty { "未填岩性" })
            r.attitude?.let { append("；产状 ").append(it).append(if (r.borrowed) "（借邻层）" else "") }
            if (r.note.isNotEmpty()) append("。").append(r.note)
            if (r.stretched) append("（按最小高度画）")
        }
        val width = (xEnd - xText - 8f).toInt()
        val l = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxOf(1, ((h - 2f) / (p.textSize * 1.25f)).toInt()))
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.clipRect(xText, top, xEnd, bottom)
        canvas.translate(xText + 4f, top + maxOf(1f, (h - l.height) / 2))
        l.draw(canvas)
        canvas.restore()
    }

    private fun legend(canvas: Canvas, c: StratColumn, top: Float) {
        val used = c.rows.map { it.pattern }.distinct().filter { it != LithoPattern.BLANK }
        if (used.isEmpty() || top + 30f > pageHeight - margin - 20f) return
        canvas.drawText("图例", margin, top, headPaint)
        var x = margin
        var y = top + 6f
        for (p in used) {
            val w = 28f + smallPaint.measureText(p.label) + 12f
            if (x + w > xEnd) { x = margin; y += 18f }
            if (y + 14f > pageHeight - margin - 20f) return
            val box = RectF(x, y, x + 22f, y + 12f)
            fill(canvas, p, box)
            canvas.drawRect(box, line)
            canvas.drawText(p.label, x + 26f, y + 9f, smallPaint)
            x += w
        }
    }

    private fun footer(canvas: Canvas, c: StratColumn, pageNo: Int) {
        val label = "${c.title} · 第 $pageNo 页 · 同勘 GeoCrew"
        canvas.drawText(label, (pageWidth - smallPaint.measureText(label)) / 2, pageHeight - margin / 2, smallPaint)
    }

    /** The lithology fill, clipped to [r]. Spacing is in points, so fills read the same at any scale. */
    private fun fill(canvas: Canvas, p: LithoPattern, r: RectF) {
        canvas.save()
        canvas.clipRect(r)
        val s = 6f
        fun rows(step: Float, block: (y: Float, odd: Boolean) -> Unit) {
            var y = r.top + step / 2
            var i = 0
            while (y < r.bottom + step) { block(y, i % 2 == 1); y += step; i++ }
        }
        fun across(step: Float, odd: Boolean, block: (x: Float) -> Unit) {
            var x = r.left + if (odd) step / 2 else 0f
            while (x < r.right + step) { block(x); x += step }
        }
        when (p) {
            LithoPattern.COAL -> canvas.drawRect(r, dot)
            LithoPattern.SANDSTONE -> rows(s) { y, odd -> across(s, odd) { x -> canvas.drawCircle(x, y, 0.7f, dot) } }
            LithoPattern.SILTSTONE -> rows(s) { y, odd -> across(s * 2, odd) { x ->
                canvas.drawCircle(x, y, 0.6f, dot)
                canvas.drawLine(x + s * 0.6f, y, x + s * 1.4f, y, ink)
            } }
            LithoPattern.MUDSTONE -> rows(s * 0.8f) { y, odd -> across(s * 1.6f, odd) { x -> canvas.drawLine(x, y, x + s * 0.8f, y, ink) } }
            LithoPattern.LIMESTONE, LithoPattern.DOLOMITE, LithoPattern.MARL -> {
                val h = s * 1.2f
                var y = r.top
                var i = 0
                while (y < r.bottom) {
                    canvas.drawLine(r.left, y, r.right, y, ink)
                    var x = r.left + if (i % 2 == 1) s else 0f
                    while (x < r.right) {
                        if (p == LithoPattern.DOLOMITE) canvas.drawLine(x - h / 3, y + h, x + h / 3, y, ink)
                        else canvas.drawLine(x, y, x, y + h, ink)
                        x += s * 2
                    }
                    if (p == LithoPattern.MARL) {
                        var dx = r.left + s / 2 + if (i % 2 == 1) s else 0f
                        while (dx < r.right) { canvas.drawLine(dx, y + h / 2, dx + s * 0.6f, y + h / 2, ink); dx += s * 2 }
                    }
                    y += h
                    i++
                }
            }
            LithoPattern.CONGLOMERATE -> rows(s * 1.4f) { y, odd -> across(s * 1.8f, odd) { x -> canvas.drawCircle(x, y, 2f, ink) } }
            LithoPattern.INTRUSIVE -> rows(s * 1.4f) { y, odd -> across(s * 1.8f, odd) { x ->
                canvas.drawLine(x - 2f, y, x + 2f, y, ink)
                canvas.drawLine(x, y - 2f, x, y + 2f, ink)
            } }
            LithoPattern.VOLCANIC -> rows(s * 1.4f) { y, odd -> across(s * 1.8f, odd) { x ->
                canvas.drawLine(x - 2f, y - 2f, x, y + 2f, ink)
                canvas.drawLine(x, y + 2f, x + 2f, y - 2f, ink)
            } }
            LithoPattern.METAMORPHIC -> rows(s) { y, _ ->
                val path = Path()
                path.moveTo(r.left, y)
                var x = r.left
                var up = true
                while (x < r.right) {
                    path.quadTo(x + s / 2, if (up) y - 2f else y + 2f, x + s, y)
                    x += s
                    up = !up
                }
                canvas.drawPath(path, ink)
            }
            LithoPattern.BLANK -> Unit
        }
        canvas.restore()
    }

    private fun centered(canvas: Canvas, s: String, left: Float, right: Float, baseline: Float, p: TextPaint) {
        val w = p.measureText(s)
        canvas.drawText(s, left + maxOf(1f, (right - left - w) / 2), baseline, p)
    }

    private fun m(v: Double) = String.format(Locale.ROOT, if (v >= 100) "%.1f" else "%.2f", v)

    private companion object {
        const val GREY = 0xFF5F6368.toInt()
        const val PT_PER_MM = 72.0 / 25.4

        fun text(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
}
