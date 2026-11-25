package org.geocrew.core.formats

import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.lineStyle
import org.geocrew.core.model.positions
import org.geocrew.core.model.string
import java.io.OutputStream
import java.util.Locale

/**
 * Every structural measurement as one row (产状表): planes by dip direction
 * and dip with their strike, lineations (§6.12) by trend and plunge, a record
 * holding both giving two rows. The angle columns are plain numbers, ready
 * for stereonet programs; lines and polygons (faults, contacts) are placed
 * at their first vertex. Same columns as the server's export.structures.csv.
 */
object StructureTableFormat : FeatureFormat {
    override val id = "structures"
    override val displayName = "产状表（CSV）"
    override val extension = "csv"
    override val mimeType = "text/csv"
    override val canRead = false
    override val canWrite = true
    override val unit get() = "条产状"
    override val fileTag get() = "structures"
    override fun nothingToWrite(features: List<GeoFeature>) =
        if (rows(features).isEmpty()) "当前工程还没有产状记录。用罗盘测量，或在记录详情里填写。" else null

    val HEADER = listOf("记录", "种类", "类型", "倾向或倾伏向(°)", "倾角或倾伏角(°)", "走向(°)", "经度", "纬度", "高程(m)", "岩性", "记录ID")

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val w = output.bufferedWriter(Charsets.UTF_8)
        w.write('﻿'.code)
        w.write(HEADER.joinToString(",") { CsvFormat.quote(it) })
        w.write("\r\n")
        val rows = rows(features)
        for (cells in rows) {
            w.write(cells.joinToString(",") { CsvFormat.quote(defuse(it)) })
            w.write("\r\n")
        }
        w.flush()
        return ExportResult(rows.size)
    }

    private fun rows(features: List<GeoFeature>): List<List<String>> = features
        .filter { !it.deleted }
        .sortedWith(compareBy(NaturalOrder) { it.title.orEmpty() })
        .flatMap { f ->
            val at = f.geometry?.positions()?.firstOrNull()
            val place = listOf(
                at?.lng?.let { fixed(it, 7) }.orEmpty(), at?.lat?.let { fixed(it, 7) }.orEmpty(), at?.alt?.let { fixed(it, 1) }.orEmpty(),
                f.properties.string(Props.LITHOLOGY).orEmpty(), f.id,
            )
            val title = f.title.orEmpty()
            listOfNotNull(
                f.attitude?.let { a ->
                    val type = f.lineStyle?.takeIf { f.geometry !is Geometry.Point }?.label.orEmpty()
                    listOf(title, "面状", type, fixed(a.dipDirection, 1), fixed(a.dip, 1), fixed(a.strike, 1)) + place
                },
                f.plunge?.let { l ->
                    val type = f.properties.string(Props.LINEATION_TYPE)?.takeIf { it.isNotBlank() } ?: "线理"
                    listOf(title, "线状", type, fixed(l.trend, 1), fixed(l.plunge, 1), "") + place
                },
            )
        }

    private fun fixed(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)
}
