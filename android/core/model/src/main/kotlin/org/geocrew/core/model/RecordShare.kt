package org.geocrew.core.model

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A record as a short plain-text message, for sending a point to a chat app
 * or a note: name and kind, where it is, attitude, lithology, form answers,
 * samples, the description, and which project and when. Photos travel as
 * attachments beside the text, so only their count is written.
 */
object RecordShare {
    fun text(
        f: GeoFeature,
        kindLabel: String,
        format: CoordinateFormat,
        fields: List<FieldTemplate> = emptyList(),
        project: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        val title = f.title?.takeIf { it.isNotBlank() }
        appendLine(if (title != null) "$title · $kindLabel" else kindLabel)
        where(f.geometry, format)?.let { appendLine(it) }
        f.attitude?.let { appendLine("产状：${it.format()}") }
        f.plunge?.let { p ->
            val type = f.properties.string(Props.LINEATION_TYPE)?.takeIf { it.isNotBlank() } ?: "线状构造"
            appendLine("$type：${p.format()}")
        }
        f.properties.string(Props.LITHOLOGY)?.takeIf { it.isNotBlank() }?.let { appendLine("岩性：$it") }
        for (field in fields) {
            if (!field.appliesTo(f.kind)) continue
            val v = field.display(f.properties[field.key]).trim()
            if (v.isNotEmpty()) appendLine("${field.label}：$v")
        }
        val samples = f.samples
        if (samples.isNotEmpty()) {
            appendLine("样品：" + samples.joinToString("、") { s -> s.no + (s.type?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "") })
        }
        f.note?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine(it) }
        if (f.photos.isNotEmpty()) appendLine("照片 ${f.photos.size} 张")
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(f.createdAt)
        append(listOfNotNull(project?.takeIf { it.isNotBlank() }, stamp).joinToString(" · "))
    }.trim()

    private fun where(g: Geometry?, format: CoordinateFormat): String? = when (g) {
        null -> null
        is Geometry.Point -> "位置：" + CoordinateFormatter.format(g.position, format) +
            (g.position.alt?.let { "，海拔 %.0f m".fmt(it) } ?: "")
        is Geometry.LineString -> g.positions.firstOrNull()?.let {
            "长 ${CoordinateFormatter.distance(g.lengthMeters())}，起点 ${CoordinateFormatter.format(it, format)}"
        }
        is Geometry.Polygon -> g.rings.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { ring ->
            // The vertex average is good enough to find the area again.
            val pts = if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
            val c = LngLat(pts.sumOf { it.lng } / pts.size, pts.sumOf { it.lat } / pts.size)
            "面积 ${CoordinateFormatter.area(g.areaSquareMeters())}，中心 ${CoordinateFormatter.format(c, format)}"
        }
        // Multi-part shapes come from imports; the record name and project still find them.
        else -> null
    }
}
