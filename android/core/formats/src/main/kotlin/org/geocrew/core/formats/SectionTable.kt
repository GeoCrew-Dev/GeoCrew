package org.geocrew.core.formats

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.measuredSection
import java.io.OutputStream
import java.util.Locale

/**
 * Measured sections (protocol v0 §6.5) as a layer table, one row per layer
 * of every section in the project, for the section register (实测剖面登记表).
 * Thicknesses are computed on export, the same way as on screen, and a
 * borrowed attitude is marked as such so the sheet shows what was measured.
 */
object SectionTableFormat : FeatureFormat {
    override val id = "sections"
    override val displayName = "实测剖面分层表（CSV）"
    override val extension = "csv"
    override val mimeType = "text/csv"
    override val canRead = false
    override val canWrite = true
    override val unit get() = "个分层"
    override val fileTag get() = "sections"
    override fun nothingToWrite(features: List<GeoFeature>) =
        if (sections(features).none { it.measuredSection.layers.isNotEmpty() }) "当前工程还没有带分层的实测剖面。在「工具 · 实测剖面」里记录。" else null

    val HEADER = listOf("剖面", "层号", "起点斜距(m)", "终点斜距(m)", "岩性", "倾向", "倾角", "产状来源", "真厚度(m)", "备注", "记录ID")

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val w = output.bufferedWriter(Charsets.UTF_8)
        w.write('﻿'.code)
        w.write(HEADER.joinToString(",") { CsvFormat.quote(it) })
        w.write("\r\n")
        var rows = 0
        for (f in sections(features)) {
            val title = f.title?.takeIf { it.isNotBlank() } ?: "实测剖面"
            val result = f.measuredSection.compute()
            for (l in result.layers) {
                val source = when {
                    l.attitude == null -> ""
                    l.borrowed -> "借用"
                    else -> "实测"
                }
                val cells = listOf(
                    title, l.layer.no, fixed(l.from), fixed(l.to), l.layer.lithology,
                    l.attitude?.dipDirection?.let { fixed(it, 0) }.orEmpty(), l.attitude?.dip?.let { fixed(it, 0) }.orEmpty(),
                    source, l.thickness?.let { fixed(it) }.orEmpty(), l.layer.note, f.id,
                )
                w.write(cells.joinToString(",") { CsvFormat.quote(defuse(it)) })
                w.write("\r\n")
                rows++
            }
        }
        w.flush()
        return ExportResult(rows)
    }

    private fun sections(features: List<GeoFeature>) = features
        .filter { !it.deleted && it.kind == FeatureKind.SECTION }
        .sortedWith(compareBy(NaturalOrder) { it.title.orEmpty() })

    private fun fixed(v: Double, digits: Int = 2) = String.format(Locale.ROOT, "%.${digits}f", v)
}
