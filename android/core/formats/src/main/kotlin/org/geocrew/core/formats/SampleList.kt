package org.geocrew.core.formats

import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.positions
import org.geocrew.core.model.samples
import org.geocrew.core.model.string
import java.io.OutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The sample register (protocol v0 §6.4) as CSV, one row per sample, for the
 * lab submission sheet and the sample store. Samples on lines or polygons
 * get the first vertex as their position, which is where a route sample is
 * usually logged from; the record id lets the sheet be joined back.
 */
object SampleListFormat : FeatureFormat {
    override val id = "samples"
    override val displayName = "样品清单（CSV）"
    override val extension = "csv"
    override val mimeType = "text/csv"
    override val canRead = false
    override val canWrite = true

    val HEADER = listOf("样品编号", "样品类型", "所在记录", "经度", "纬度", "高程", "岩性", "采样时间", "样品备注", "记录ID")

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val zone = ZoneId.systemDefault()
        val rows = features.filterNot { it.deleted }
            .flatMap { f -> f.samples.map { f to it } }
            .sortedWith(compareBy(NaturalOrder) { it.second.no })
        val w = output.bufferedWriter(Charsets.UTF_8)
        w.write('﻿'.code)
        w.write(HEADER.joinToString(",") { CsvFormat.quote(it) })
        w.write("\r\n")
        for ((f, s) in rows) {
            val at = position(f.geometry)
            val cells = listOf(
                s.no, s.type.orEmpty(), f.title.orEmpty(),
                at?.lng?.let(CsvFormat::num).orEmpty(), at?.lat?.let(CsvFormat::num).orEmpty(), at?.alt?.let(CsvFormat::num).orEmpty(),
                f.properties.string(Props.LITHOLOGY).orEmpty(),
                s.takenAt?.atZone(zone)?.format(TIME).orEmpty(),
                s.note.orEmpty(), f.id,
            )
            w.write(cells.joinToString(",") { CsvFormat.quote(it) })
            w.write("\r\n")
        }
        w.flush()
        return ExportResult(rows.size)
    }

    private fun position(g: Geometry?): LngLat? = when (g) {
        is Geometry.Point -> g.position
        null -> null
        else -> g.positions().firstOrNull()
    }
}

/** Orders "D2-1" before "D10-1": digit runs compare as numbers. */
internal object NaturalOrder : Comparator<String> {
    private val PART = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val x = PART.findAll(a.lowercase()).map { it.value }.toList()
        val y = PART.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
