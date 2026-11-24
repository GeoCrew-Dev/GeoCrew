package org.geocrew.core.formats

import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Props
import org.geocrew.core.model.geoRoute
import java.io.OutputStream
import java.util.Locale

/**
 * Geological routes (protocol v0 §6.6) as a route record table (路线记录表):
 * for each route a line with its purpose, one row per point in walking
 * order with its position, lithology, attitude, on-point note and the
 * description of the way to the next point, and a line with the summary.
 * Sorted by date, then route number, so it reads like the field notebook.
 */
object RouteTableFormat : FeatureFormat {
    override val id = "routes"
    override val displayName = "地质路线记录表（CSV）"
    override val extension = "csv"
    override val mimeType = "text/csv"
    override val canRead = false
    override val canWrite = true
    override val unit get() = "行"
    override val fileTag get() = "routes"
    override fun nothingToWrite(features: List<GeoFeature>) =
        if (routes(features).isEmpty()) "当前工程还没有地质路线。在「工具 · 地质路线」里新建。" else null

    val HEADER = listOf("路线", "日期", "参加人员", "序号", "点号", "类型", "经度", "纬度", "海拔(m)", "岩性", "产状", "点上描述", "至下一点描述", "记录ID")

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val byId = features.associateBy { it.id }
        val w = output.bufferedWriter(Charsets.UTF_8)
        w.write('﻿'.code)
        w.write(HEADER.joinToString(",") { CsvFormat.quote(it) })
        w.write("\r\n")
        var rows = 0
        fun row(cells: List<String>) {
            w.write(cells.joinToString(",") { CsvFormat.quote(defuse(it)) })
            w.write("\r\n")
            rows++
        }
        for (f in routes(features)) {
            val r = f.geoRoute
            val name = f.title?.takeIf { it.isNotBlank() } ?: "地质路线"
            val head = listOf(name, r.date?.toString().orEmpty(), r.members)
            if (r.purpose.isNotBlank()) row(head + listOf("目的", "", "", "", "", "", "", "", r.purpose, "", f.id))
            r.resolve(byId::get).forEachIndexed { i, s ->
                val p = s.feature
                val pos = s.position
                row(
                    head + listOf(
                        (i + 1).toString(),
                        p?.title.orEmpty(),
                        p?.let { kindName(it) }.orEmpty(),
                        pos?.let { fixed(it.lng, 6) }.orEmpty(),
                        pos?.let { fixed(it.lat, 6) }.orEmpty(),
                        pos?.alt?.let { fixed(it, 1) }.orEmpty(),
                        (p?.properties?.get(Props.LITHOLOGY) as? JsonPrimitive)?.content.orEmpty(),
                        p?.attitude?.format().orEmpty(),
                        p?.note.orEmpty(),
                        s.stop.onward,
                        s.stop.featureId,
                    ),
                )
            }
            if (r.summary.isNotBlank()) row(head + listOf("小结", "", "", "", "", "", "", "", r.summary, "", f.id))
        }
        w.flush()
        return ExportResult(rows)
    }

    private fun kindName(f: GeoFeature) = when (f.kind) {
        FeatureKind.GEO_POINT -> "地质点"
        FeatureKind.PHOTO -> "照片点"
        FeatureKind.POINT -> "标注点"
        else -> f.kind.id
    }

    private fun routes(features: List<GeoFeature>) = features
        .filter { !it.deleted && it.kind == FeatureKind.ROUTE }
        .sortedWith(compareBy<GeoFeature> { it.geoRoute.date }.thenBy(NaturalOrder) { it.title.orEmpty() })

    private fun fixed(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)
}
