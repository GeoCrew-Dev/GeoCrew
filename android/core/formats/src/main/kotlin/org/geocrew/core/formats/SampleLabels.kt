package org.geocrew.core.formats

import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.positions
import org.geocrew.core.model.samples
import org.geocrew.core.model.string
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What goes on each printed sample label, and how labels fill a sheet. The
 * drawing itself is platform code; this part is plain data so it can be
 * tested and reused (another app could print the same labels).
 */
data class SampleLabel(
    val no: String,
    /** "薄片 · 花岗岩": the type and the station's lithology, whichever exist. */
    val kind: String,
    /** "D012 · 2026-11-24": station and date. */
    val station: String,
    val position: String,
    val project: String,
)

object SampleLabels {
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Labels for every sample in [features] (or only [only], by number), in natural number order. */
    fun build(
        projectName: String,
        features: List<GeoFeature>,
        coordinates: CoordinateFormat = CoordinateFormat.DECIMAL,
        zone: ZoneId = ZoneId.systemDefault(),
        only: Set<String>? = null,
    ): List<SampleLabel> = features.filterNot { it.deleted }
        .flatMap { f -> f.samples.filter { only == null || it.no in only }.map { f to it } }
        .sortedWith(compareBy(NaturalOrder) { it.second.no })
        .map { (f, s) ->
            val at = when (val g = f.geometry) {
                is Geometry.Point -> g.position
                null -> null
                else -> g.positions().firstOrNull()
            }
            SampleLabel(
                no = s.no,
                kind = listOfNotNull(s.type, f.properties.string(Props.LITHOLOGY)?.takeIf { it.isNotBlank() }).joinToString(" · "),
                station = listOfNotNull(f.title?.takeIf { it.isNotBlank() }, (s.takenAt ?: f.createdAt).atZone(zone).format(DATE)).joinToString(" · "),
                position = at?.let { CoordinateFormatter.format(it, coordinates) }.orEmpty(),
                project = projectName,
            )
        }
}

/**
 * A sheet of equal labels, in points (1/72 inch). The default is A4 with 3 × 8
 * labels, the common self-adhesive layout; [cell] gives each label's box.
 */
data class LabelSheet(
    val pageWidth: Float = 595f,
    val pageHeight: Float = 842f,
    val columns: Int = 3,
    val rows: Int = 8,
    val marginX: Float = 18f,
    val marginY: Float = 24f,
) {
    init {
        require(columns > 0 && rows > 0) { "a sheet needs at least one label" }
    }

    val perPage get() = columns * rows
    val labelWidth get() = (pageWidth - 2 * marginX) / columns
    val labelHeight get() = (pageHeight - 2 * marginY) / rows

    fun pages(count: Int) = if (count <= 0) 0 else (count + perPage - 1) / perPage

    /** Page and box (left, top, right, bottom) of label [index], filled row by row. */
    fun cell(index: Int): Pair<Int, FloatArray> {
        val page = index / perPage
        val i = index % perPage
        val left = marginX + (i % columns) * labelWidth
        val top = marginY + (i / columns) * labelHeight
        return page to floatArrayOf(left, top, left + labelWidth, top + labelHeight)
    }
}
