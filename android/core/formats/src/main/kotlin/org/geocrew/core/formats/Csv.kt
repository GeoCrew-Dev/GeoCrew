package org.geocrew.core.formats

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.lengthMeters
import java.io.InputStream
import java.io.OutputStream

/**
 * Attribute table as CSV, for spreadsheets and for loading planned stations.
 *
 * Export writes one row per record: fixed columns (id, kind, title, point
 * coordinates, attitude, lithology, length/area), then the project's form
 * fields in form order, then any other attributes alphabetically. Lines and
 * polygons keep their measurements but no coordinates; use GeoJSON or
 * Shapefile for geometry. The file starts with a UTF-8 BOM so spreadsheet
 * programs read Chinese correctly.
 *
 * Import reads points from any CSV with longitude and latitude columns
 * (English or Chinese headers; comma, semicolon or tab separated). Every other
 * column becomes an attribute; values that look like plain numbers are stored
 * as numbers, anything with a leading zero (sample "007") stays text. An
 * `id` column, as written on export, is kept so re-imports update records.
 */
object CsvFormat : FeatureFormat {
    override val id = "csv"
    override val displayName = "CSV 属性表"
    override val extension = "csv"
    override val mimeType = "text/csv"
    override val canRead = true
    override val canWrite = true

    private const val BOM = '﻿'

    private val FIXED = listOf(
        "id", Props.KIND, Props.TITLE, "lng", "lat", "alt",
        Props.STRIKE, Props.DIP_DIRECTION, Props.DIP, Props.LITHOLOGY, Props.NOTE, Props.LENGTH_M, Props.AREA_M2,
    )

    /** Properties that are not useful as spreadsheet columns. */
    private val SKIP = setOf(Props.PHOTOS)

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val live = features.filterNot { it.deleted }
        val formKeys = ctx.fields.map { it.key }.filter { it !in FIXED }
        val others = live.flatMap { it.properties.keys }.toSortedSet()
            .filter { it !in FIXED && it !in formKeys && it !in SKIP && it !in Props.MANAGED }
        val columns = FIXED + formKeys + others
        val labels = ctx.fields.associate { it.key to it.label }
        val w = output.bufferedWriter(Charsets.UTF_8)
        w.write(BOM.code)
        w.write(columns.joinToString(",") { quote(labels[it] ?: it) })
        w.write("\r\n")
        for (f in live) {
            val p = f.geometry as? Geometry.Point
            val row = columns.map { c ->
                when (c) {
                    "id" -> f.id
                    Props.KIND -> f.kind.id
                    "lng" -> p?.position?.lng?.let(::num).orEmpty()
                    "lat" -> p?.position?.lat?.let(::num).orEmpty()
                    "alt" -> p?.position?.alt?.let(::num).orEmpty()
                    Props.LENGTH_M -> f.geometry?.takeIf { it !is Geometry.Point }?.lengthMeters()?.let { "%.1f".format(java.util.Locale.ROOT, it) }.orEmpty()
                    Props.AREA_M2 -> f.geometry?.takeIf { it is Geometry.Polygon || it is Geometry.MultiPolygon }
                        ?.areaSquareMeters()?.let { "%.1f".format(java.util.Locale.ROOT, it) }.orEmpty()
                    else -> cell(f.properties[c])
                }
            }
            w.write(row.joinToString(",") { quote(it) })
            w.write("\r\n")
        }
        w.flush()
        return ExportResult(live.size)
    }

    override fun read(input: InputStream, ctx: ImportContext): ImportResult {
        val text = input.readBytes().toString(Charsets.UTF_8).removePrefix(BOM.toString())
        val rows = parse(text)
        if (rows.isEmpty()) throw FormatException("CSV 是空的")
        val header = rows.first().map { it.trim() }
        val find = { names: Set<String> -> header.indexOfFirst { it.lowercase() in names } }
        val lng = find(LNG)
        val lat = find(LAT)
        if (lng < 0 || lat < 0) throw FormatException("没有找到经度、纬度列（列名可用 lng/lon/经度 和 lat/纬度）")
        val alt = find(ALT)
        val title = find(TITLE)
        val kind = find(setOf("kind", "类型"))
        val idCol = header.indexOf("id")
        val used = setOf(lng, lat, alt, title, kind)
        val byLabel = ctx.fields.associate { it.label to it.key }
        val keys = header.map { byLabel[it] ?: it }

        val out = mutableListOf<GeoFeature>()
        val warnings = mutableListOf<String>()
        var bad = 0
        for ((i, row) in rows.drop(1).withIndex()) {
            if (row.all { it.isBlank() }) continue
            val x = row.getOrNull(lng)?.trim()?.replace(',', '.')?.toDoubleOrNull()
            val y = row.getOrNull(lat)?.trim()?.replace(',', '.')?.toDoubleOrNull()
            if (x == null || y == null || x !in -180.0..180.0 || y !in -90.0..90.0) {
                bad++
                if (bad <= 3) warnings += "第 ${i + 2} 行坐标无效，已跳过"
                continue
            }
            val z = row.getOrNull(alt)?.trim()?.toDoubleOrNull()
            val k = row.getOrNull(kind)?.trim()?.takeIf { it.isNotEmpty() }?.let(::FeatureKind)
                ?.takeIf { it == FeatureKind.POINT || it == FeatureKind.GEO_POINT || it == FeatureKind.PHOTO }
            // Keeping exported ids lets a re-import update records instead of duplicating them.
            val id = row.getOrNull(idCol)?.trim()?.takeIf { ID.matches(it) } ?: ctx.newId()
            out += GeoFeature(
                id = id, projectId = ctx.projectId, kind = k ?: FeatureKind.POINT,
                geometry = Geometry.Point(LngLat(x, y, z)),
                properties = buildProperties {
                    row.getOrNull(title)?.trim()?.takeIf { it.isNotEmpty() }?.let { this[Props.TITLE] = it }
                    keys.forEachIndexed { c, name ->
                        if (c in used || name.isEmpty() || name in IGNORED) return@forEachIndexed
                        val v = row.getOrNull(c)?.trim().orEmpty()
                        if (v.isEmpty()) return@forEachIndexed
                        val field = ctx.fields.firstOrNull { it.key == name }
                        val typed = field?.let { runCatching { it.encode(v) }.getOrNull() }
                        when {
                            typed != null -> put(name, typed)
                            NUMBER.matches(v) -> this[name] = v.toDouble()
                            else -> this[name] = v
                        }
                    }
                },
                createdAt = ctx.now, sync = SyncInfo(updatedAt = ctx.now),
            )
        }
        if (bad > 3) warnings += "共有 $bad 行坐标无效，已跳过"
        return ImportResult(out, warnings)
    }

    /** Columns written on export that must not come back as attributes. */
    private val IGNORED = setOf("id", Props.PHOTOS, Props.AUDIO, Props.LENGTH_M, Props.AREA_M2)

    private val ID = Regex("^[A-Za-z0-9._-]{1,64}$")

    private val LNG = setOf("lng", "lon", "long", "longitude", "x", "经度")
    private val LAT = setOf("lat", "latitude", "y", "纬度")
    private val ALT = setOf("alt", "altitude", "elevation", "ele", "z", "高程", "海拔")
    private val TITLE = setOf("title", "name", "名称", "点号", "编号")

    /** Plain numbers only: no leading zeros (codes), no thousands separators. */
    private val NUMBER = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?$")

    private fun num(d: Double): String = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun cell(v: JsonElement?): String = when (v) {
        null, JsonNull -> ""
        is JsonPrimitive -> if (v.isString) v.content else v.doubleOrNull?.let(::num) ?: v.content
        is JsonArray -> v.joinToString(";") { cell(it) }
        else -> v.toString()
    }

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s.startsWith(' ') || s.endsWith(' ')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    /** RFC 4180 rows; the separator is whichever of , ; or tab splits the header most. */
    internal fun parse(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(',', ';', '\t').maxByOrNull { c -> firstLine.count { it == c } } ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    sep -> { row += cell.toString(); cell.clear() }
                    '\r' -> Unit
                    '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf() }
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
}
