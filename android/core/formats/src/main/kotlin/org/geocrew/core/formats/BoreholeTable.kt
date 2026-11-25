package org.geocrew.core.formats

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.borehole
import java.io.OutputStream
import java.util.Locale

/**
 * Borehole logs (protocol v0 §6.11) as a layer table, one row per layer of
 * every hole, with the collar, final depth and water table repeated on each
 * row so the sheet can be filtered or pivoted without lookups (钻孔分层表).
 */
object BoreholeTableFormat : FeatureFormat {
    override val id = "boreholes"
    override val displayName = "钻孔分层表（CSV）"
    override val extension = "csv"
    override val mimeType = "text/csv"
    override val canRead = false
    override val canWrite = true
    override val unit get() = "个分层"
    override val fileTag get() = "boreholes"
    override fun nothingToWrite(features: List<GeoFeature>) =
        if (holes(features).none { it.borehole.intervals().isNotEmpty() }) "当前工程还没有带分层的钻孔。在「工具 · 钻孔编录」里记录。" else null

    val HEADER = listOf("孔号", "孔口经度", "孔口纬度", "孔口高程(m)", "终孔深度(m)", "水位埋深(m)", "层号", "层顶深度(m)", "层底深度(m)", "厚度(m)", "岩性", "描述", "记录ID")

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val w = output.bufferedWriter(Charsets.UTF_8)
        w.write('﻿'.code)
        w.write(HEADER.joinToString(",") { CsvFormat.quote(it) })
        w.write("\r\n")
        var rows = 0
        for (f in holes(features)) {
            val title = f.title?.takeIf { it.isNotBlank() } ?: "钻孔"
            val hole = f.borehole
            val collar = (f.geometry as? Geometry.Point)?.position
            for (iv in hole.intervals()) {
                val cells = listOf(
                    title,
                    collar?.lng?.let { fixed(it, 7) }.orEmpty(), collar?.lat?.let { fixed(it, 7) }.orEmpty(),
                    collar?.alt?.let { fixed(it, 1) }.orEmpty(),
                    fixed(hole.total), hole.water?.let { fixed(it) }.orEmpty(),
                    iv.layer.no, fixed(iv.top), fixed(iv.bottom), fixed(iv.thickness),
                    iv.layer.lithology, iv.layer.note, f.id,
                )
                w.write(cells.joinToString(",") { CsvFormat.quote(defuse(it)) })
                w.write("\r\n")
                rows++
            }
        }
        w.flush()
        return ExportResult(rows)
    }

    private fun holes(features: List<GeoFeature>) = features
        .filter { !it.deleted && it.kind == FeatureKind.BOREHOLE }
        .sortedWith(compareBy(NaturalOrder) { it.title.orEmpty() })

    private fun fixed(v: Double, digits: Int = 2) = String.format(Locale.ROOT, "%.${digits}f", v)
}
