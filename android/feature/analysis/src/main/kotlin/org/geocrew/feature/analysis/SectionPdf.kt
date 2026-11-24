package org.geocrew.feature.analysis

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import org.geocrew.core.model.CoordinateFormatter
import java.io.OutputStream
import java.time.LocalDate
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws a map section with terrain as a printable cross-section on one A4
 * landscape page: the ground line at a round horizontal scale, the units as
 * a coloured skin, each contact or fault with its name and apparent dip
 * (steepened with the vertical exaggeration, so the angles match the
 * drawing), elevation and distance axes, and a legend.
 */
internal class SectionPdf {
    private val pageWidth = 842
    private val pageHeight = 595
    private val margin = 36f

    private val left = margin + 44f
    private val right = pageWidth - margin
    private val top = 92f
    private val bottom = pageHeight - margin - 78f

    private val titlePaint = text(16f, bold = true)
    private val subPaint = text(9f, color = GREY)
    private val labelPaint = text(7.5f)
    private val smallPaint = text(7f, color = GREY)
    private val frame = stroke(0.6f, Color.BLACK)
    private val grid = stroke(0.3f, 0xFFBDBDBD.toInt()).apply { pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f) }
    private val groundPaint = stroke(1.3f, 0xFF6D4C41.toInt())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Writes [s] to [out]. [s] must have ground. */
    fun write(s: Strip, legend: List<Pair<String, String>>, out: OutputStream) {
        val ground = requireNotNull(s.ground) { "没有地形线" }
        val widthMm = (right - left) / PT_PER_MM
        val scale = s.scaleToFit(widthMm)
        val hPt = 1000.0 * PT_PER_MM / scale // points per metre along the section
        val drawnWidth = s.length * hPt
        val ve = s.exaggeration((bottom - top) / drawnWidth, fill = 0.5)
        val vPt = hPt * ve
        val hi = ground.maxOf { it.second }
        val lo = ground.minOf { it.second }
        val zTop = hi + (bottom - top) * 0.14 / vPt
        val zBottom = zTop - (bottom - top) / vPt

        fun x(c: Double) = (left + c * hPt).toFloat()
        fun y(z: Double) = (top + (zTop - z) * vPt).toFloat()

        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            val canvas = page.canvas

            canvas.drawText("${s.title ?: "剖面线"} 图切剖面", margin, margin + 16f, titlePaint)
            val facts = listOf(
                "水平 1:${fmt(scale)}",
                "垂直 1:${fmt(scale / ve)}" + if (abs(ve - 1) > 0.05) "（放大 ×${trim(ve)}）" else "",
                "剖面长 ${CoordinateFormatter.distance(s.length)}",
                "地形 ${fmt(lo)}–${fmt(hi)} m",
                LocalDate.now().toString(),
            )
            canvas.drawText(facts.joinToString("  ·  "), margin, margin + 32f, subPaint)

            // Elevation grid and axis.
            val zStep = niceStep(zTop - zBottom, 7)
            var z = ceil(zBottom / zStep) * zStep
            while (z <= zTop) {
                val yy = y(z)
                canvas.drawLine(left, yy, x(s.length), yy, grid)
                canvas.drawLine(left - 3f, yy, left, yy, frame)
                val t = fmt(z)
                canvas.drawText(t, left - 5f - labelPaint.measureText(t), yy + 2.5f, labelPaint)
                z += zStep
            }
            canvas.save()
            canvas.rotate(-90f, margin + 6f, (top + bottom) / 2)
            canvas.drawText("高程（m）", margin + 6f - labelPaint.measureText("高程（m）") / 2, (top + bottom) / 2, labelPaint)
            canvas.restore()

            // Distance axis.
            val cStep = niceStep(s.length, 10)
            var c = 0.0
            while (c <= s.length + 1e-6) {
                val xx = x(c)
                canvas.drawLine(xx, bottom, xx, bottom + 3f, frame)
                val t = if (cStep >= 1000) "${trim(c / 1000)} km" else "${fmt(c)} m"
                canvas.drawText(t, xx - labelPaint.measureText(t) / 2, bottom + 12f, labelPaint)
                c += cStep
            }
            canvas.drawRect(left, top, x(s.length), bottom, frame)

            // Units as a skin under the ground line, named where there is room.
            val skin = 9f
            s.bands.forEach { b ->
                val pts = buildList {
                    add(b.from to s.groundAt(b.from)!!)
                    ground.filter { it.first > b.from && it.first < b.to }.forEach { add(it) }
                    add(b.to to s.groundAt(b.to)!!)
                }
                val path = Path().apply {
                    moveTo(x(pts[0].first), y(pts[0].second))
                    pts.drop(1).forEach { lineTo(x(it.first), y(it.second)) }
                    pts.asReversed().forEach { lineTo(x(it.first), y(it.second) + skin) }
                    close()
                }
                fill.color = color(b.color)
                canvas.drawPath(path, fill)
                b.label?.let { name ->
                    val w = smallPaint.measureText(name)
                    val mid = (b.from + b.to) / 2
                    if (x(b.to) - x(b.from) > w + 4f) {
                        canvas.drawText(name, x(mid) - w / 2, y(s.groundAt(mid)!!) + skin + 9f, smallPaint)
                    }
                }
            }
            val g = Path().apply {
                moveTo(x(ground[0].first), y(ground[0].second))
                ground.drop(1).forEach { lineTo(x(it.first), y(it.second)) }
            }
            canvas.drawPath(g, groundPaint)

            // Contacts and faults: where they crop out, their dip, and their name.
            val reach = 46f
            s.ticks.forEach { t ->
                val cx = x(t.at)
                val cy = y(s.groundAt(t.at)!!)
                val ink = stroke(1.1f, color(t.color))
                t.dip?.let { d ->
                    val a = if (abs(d) >= 89.9) PI / 2 else atan(ve * tan(abs(d) * PI / 180))
                    val dx = (cos(a) * reach).toFloat() * if (d < 0) -1 else 1
                    val dy = (sin(a) * reach).toFloat()
                    canvas.drawLine(cx, cy, cx + dx, cy + dy, ink)
                }
                canvas.drawLine(cx, cy - 4f, cx, cy, ink)
                val label = listOfNotNull(t.label, t.dip?.let { "${fmt(abs(it))}°" }).joinToString(" ")
                if (label.isNotEmpty()) {
                    canvas.save()
                    canvas.rotate(-60f, cx, cy - 6f)
                    canvas.drawText(label, cx, cy - 6f, smallPaint)
                    canvas.restore()
                }
            }

            legend(canvas, legend, bottom + 30f)
            canvas.drawText(
                "高程取自公开地形瓦片（Terrarium，SRTM 等来源），沿剖面约每 30 m 取样，仅作示意；界线倾角为视倾角，已按垂直放大换算。同勘 GeoCrew 生成",
                margin, pageHeight - margin + 8f, smallPaint,
            )
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun legend(canvas: Canvas, entries: List<Pair<String, String>>, y: Float) {
        if (entries.isEmpty()) return
        canvas.drawText("图例", margin, y, labelPaint)
        var x = margin + 28f
        var row = y
        entries.forEach { (hex, name) ->
            val w = 14f + 4f + labelPaint.measureText(name) + 14f
            if (x + w > right) {
                x = margin + 28f
                row += 14f
            }
            fill.color = color(hex)
            canvas.drawRect(x, row - 8f, x + 14f, row + 1f, fill)
            canvas.drawRect(x, row - 8f, x + 14f, row + 1f, frame)
            canvas.drawText(name, x + 18f, row, labelPaint)
            x += w
        }
    }

    private companion object {
        const val GREY = 0xFF5F6368.toInt()
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

        fun color(hex: String): Int = runCatching { Color.parseColor(hex) }.getOrDefault(Color.GRAY)

        fun fmt(v: Double) = String.format(Locale.ROOT, "%.0f", v)

        fun trim(v: Double) = if (abs(v - Math.round(v)) < 1e-6) Math.round(v).toString() else String.format(Locale.ROOT, "%.2f", v)
    }
}

/** A round step (1, 2 or 5 times a power of ten) that cuts [span] into at most [parts]. */
internal fun niceStep(span: Double, parts: Int): Double {
    if (span <= 0) return 1.0
    val raw = span / parts
    val p = Math.pow(10.0, floor(Math.log10(raw)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.first { it >= raw }
}
