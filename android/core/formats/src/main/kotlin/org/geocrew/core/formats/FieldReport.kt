package org.geocrew.core.formats

import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.bounds
import org.geocrew.core.model.lengthMeters
import org.geocrew.core.model.string
import org.geocrew.core.model.task
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
            val grouped = chosen.groupBy { it.createdAt.atZone(zone).toLocalDate() }
            val dayList = grouped.toSortedMap().map { (date, list) ->
                Day(date, list.map { entry(it, fields, kindLabel, zone, coordinates) })
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
                f.task?.let { t -> add("任务" to "${t.assigneeName}：${t.status.label}") }
            }
            return Entry(f.id, heading, facts, f.note?.takeIf { it.isNotBlank() }, f.photos)
        }
    }
}
