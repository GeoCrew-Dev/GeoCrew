package org.geocrew.core.formats

import org.geocrew.core.model.AudioInfo
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.GeoRoute
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.bounds
import org.geocrew.core.model.lengthMeters
import org.geocrew.core.model.string
import org.geocrew.core.model.task
import org.geocrew.core.model.audio
import org.geocrew.core.model.audioInfo
import org.geocrew.core.model.geoRoute
import org.geocrew.core.model.measuredSection
import org.geocrew.core.model.samples
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A printable field notebook: the project's records grouped by day, each
 * with its position, attitude, form fields, note and photos. Built here as
 * plain data so any renderer (PDF on Android, HTML, tests) lays it out the
 * same way.
 */
data class FieldReport(
    val title: String,
    val subtitle: String,
    val days: List<Day>,
) {
    data class Day(val date: LocalDate, val entries: List<Entry>)

    data class Entry(
        val featureId: String,
        val heading: String,
        val facts: List<Pair<String, String>>,
        val note: String?,
        val photos: List<String>,
    )

    val entryCount: Int get() = days.sumOf { it.entries.size }

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /**
         * Tracks are left out (they are routes, not observations) unless
         * [includeTracks]; [days] restricts the report to those local dates.
         */
        fun build(
            projectName: String,
            features: List<GeoFeature>,
            fields: List<FieldTemplate>,
            kindLabel: (FeatureKind) -> String,
            zone: ZoneId,
            coordinates: CoordinateFormat = CoordinateFormat.DMS,
            days: Set<LocalDate>? = null,
            includeTracks: Boolean = false,
            generatedAt: Instant = Instant.now(),
        ): FieldReport {
            val chosen = features
                .filter { !it.deleted && (includeTracks || it.kind != FeatureKind.TRACK) }
                .filter { days == null || it.createdAt.atZone(zone).toLocalDate() in days }
                .sortedWith(compareBy<GeoFeature> { it.createdAt }.thenBy { it.id })
            val byId = features.associateBy { it.id }
            val grouped = chosen.groupBy { it.createdAt.atZone(zone).toLocalDate() }
            val dayList = grouped.toSortedMap().map { (date, list) ->
                Day(date, list.map { entry(it, fields, kindLabel, zone, coordinates, byId::get) })
            }
            val span = when {
                dayList.isEmpty() -> "没有记录"
                dayList.size == 1 -> DAY.format(dayList.first().date)
                else -> DAY.format(dayList.first().date) + " 至 " + DAY.format(dayList.last().date)
            }
            return FieldReport(
                title = "$projectName · 野外记录",
                subtitle = "$span，共 ${chosen.size} 条 · 生成于 ${STAMP.withZone(zone).format(generatedAt)}",
                days = dayList,
            )
        }

        internal fun entry(
            f: GeoFeature,
            fields: List<FieldTemplate>,
            kindLabel: (FeatureKind) -> String,
            zone: ZoneId,
            coordinates: CoordinateFormat,
            byId: (String) -> GeoFeature? = { null },
        ): Entry {
            val title = f.title?.takeIf { it.isNotBlank() }
            val heading = listOfNotNull(title, kindLabel(f.kind)).joinToString(" · ") + "（" + CLOCK.withZone(zone).format(f.createdAt) + "）"
            val facts = buildList {
                when (val g = f.geometry) {
                    is Geometry.Point -> {
                        add("位置" to CoordinateFormatter.format(g.position, coordinates))
                        g.position.alt?.let { add("海拔" to "%.0f m".format(java.util.Locale.ROOT, it)) }
                    }
                    null -> Unit
                    else -> {
                        g.bounds()?.let { b -> add("中心" to CoordinateFormatter.format(b.center, coordinates)) }
                        if (g is Geometry.LineString || g is Geometry.MultiLineString) {
                            add("长度" to CoordinateFormatter.distance(g.lengthMeters()))
                        }
                        if (g is Geometry.Polygon || g is Geometry.MultiPolygon) {
                            add("面积" to CoordinateFormatter.area(g.areaSquareMeters()))
                        }
                    }
                }
                f.attitude?.let { add("产状" to it.format()) }
                f.properties.string(Props.LITHOLOGY)?.takeIf { it.isNotBlank() }?.let { add("岩性" to it) }
                for (field in fields) {
                    if (!field.appliesTo(f.kind)) continue
                    val v = field.display(f.properties[field.key])
                    if (v.isNotBlank()) add(field.label to v + if (field.unit.isNotBlank()) " ${field.unit}" else "")
                }
                f.samples.takeIf { it.isNotEmpty() }?.let { list ->
                    add("样品" to list.joinToString("；") { s -> listOfNotNull(s.no, s.type).joinToString(" ") })
                }
                if (f.kind == FeatureKind.SECTION) {
                    val sec = f.measuredSection
                    val r = sec.compute()
                    if (sec.legs.isNotEmpty()) add("导线" to "${sec.legs.size} 段，共 ${"%.1f".format(java.util.Locale.ROOT, sec.length)} m")
                    if (r.layers.isNotEmpty()) {
                        add(
                            "分层" to r.layers.joinToString("；") { l ->
                                listOf(l.layer.no, l.layer.lithology, l.thickness?.let { "%.1f m".format(java.util.Locale.ROOT, it) }.orEmpty())
                                    .filter { it.isNotBlank() }.joinToString(" ")
                            },
                        )
                        add("总厚度" to "%.1f m".format(java.util.Locale.ROOT, r.total))
                    }
                }
                if (f.kind == FeatureKind.ROUTE) routeFacts(f.geoRoute, byId)
                f.task?.let { t -> add("任务" to "${t.assigneeName}：${t.status.label}") }
                f.audio.takeIf { it.isNotEmpty() }?.let { clips ->
                    val total = clips.sumOf { f.audioInfo[it]?.durationSeconds ?: 0.0 }
                    add("录音" to "${clips.size} 段" + if (total > 0) "，共 ${AudioInfo.clock(total)}" else "")
                }
            }
            return Entry(f.id, heading, facts, f.note?.takeIf { it.isNotBlank() }, f.photos)
        }

        /** A geological route (protocol v0 §6.6): crew, purpose, the points in order, what lies between them, summary. */
        private fun MutableList<Pair<String, String>>.routeFacts(r: GeoRoute, byId: (String) -> GeoFeature?) {
            r.date?.let { add("路线日期" to DAY.format(it)) }
            if (r.members.isNotBlank()) add("参加人员" to r.members.trim())
            if (r.purpose.isNotBlank()) add("目的任务" to r.purpose.trim())
            val stops = r.resolve(byId).filter { it.feature != null }
            if (stops.isNotEmpty()) {
                val name = { s: GeoRoute.Resolved -> s.feature?.title?.takeIf { it.isNotBlank() } ?: "未命名点" }
                add("路线点" to "${stops.size} 个：" + stops.joinToString(" → ", transform = name))
                val between = stops.zipWithNext().filter { (a, _) -> a.stop.onward.isNotBlank() }
                    .map { (a, b) -> "${name(a)}→${name(b)}：${a.stop.onward.trim()}" }
                if (between.isNotEmpty()) add("点间描述" to between.joinToString("；"))
            }
            if (r.summary.isNotBlank()) add("路线小结" to r.summary.trim())
        }
    }
}
