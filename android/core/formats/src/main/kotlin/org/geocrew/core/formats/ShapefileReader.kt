package org.geocrew.core.formats

import org.geocrew.core.model.Ellipsoid
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GaussKruger
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.TransverseMercator
import org.geocrew.core.model.buildProperties
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.floor

/**
 * Reads the shapefiles in a zip: each `.shp` with its `.dbf` (attributes),
 * `.prj` (coordinate system) and `.cpg` (text encoding) of the same name.
 * Coordinates may be degrees or Transverse Mercator / Gauss-Krüger metres on
 * CGCS2000, WGS 84, Xi'an 80 or Beijing 54; files without a `.prj` are read
 * as degrees or as zone-prefixed Gauss-Krüger when the numbers show which.
 * Attributes without a declared encoding are read as UTF-8, or as GBK when
 * they are not valid UTF-8, as most Chinese GIS software writes them.
 */
internal object ShapefileReader {
    private const val MAX_ENTRY = 256L shl 20

    fun read(input: InputStream, ctx: ImportContext): ImportResult {
        val files = mutableMapOf<String, ByteArray>()
        var projectPackage = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "project.json") projectPackage = true
                if (e.isDirectory || e.name.contains("__MACOSX")) continue
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("shp", "dbf", "prj", "cpg")) continue
                files[e.name.lowercase()] = zip.readLimited(MAX_ENTRY)
            }
        }
        val layers = files.keys.filter { it.endsWith(".shp") }.map { it.removeSuffix(".shp") }.sorted()
        if (layers.isEmpty()) {
            throw FormatException(if (projectPackage) "这是同勘工程包，请在工程列表右上角导入" else "压缩包里没有 .shp 文件")
        }
        val out = mutableListOf<GeoFeature>()
        val warnings = mutableListOf<String>()
        for (base in layers) {
            val name = base.substringAfterLast('/')
            runCatching {
                layer(files.getValue("$base.shp"), files["$base.dbf"], files["$base.prj"]?.decodeToString(), files["$base.cpg"]?.decodeToString(), name, ctx, warnings, out)
            }.onFailure { e -> warnings += "图层 $name 无法读取：${e.message}" }
        }
        if (out.isEmpty() && warnings.isNotEmpty() && layers.size == 1) throw FormatException(warnings.first())
        return ImportResult(out, warnings)
    }

    private fun layer(
        shp: ByteArray, dbf: ByteArray?, prj: String?, cpg: String?, name: String,
        ctx: ImportContext, warnings: MutableList<String>, out: MutableList<GeoFeature>,
    ) {
        val shapes = shapes(shp)
        val table = dbf?.let { Dbf.read(it, cpg) }
        if (table == null) warnings += "图层 $name 没有 .dbf，只导入了图形"
        else if (table.rows.size != shapes.size) warnings += "图层 $name 的属性表有 ${table.rows.size} 行，图形有 ${shapes.size} 个，按顺序对应"
        val crs = Crs.of(prj, shapes.firstNotNullOfOrNull { it.firstOrNull()?.firstOrNull() }, ctx.localGrid)
            ?: throw FormatException(if (prj == null) "缺少 .prj，坐标看不出是经纬度还是带号的高斯坐标" else "不支持的坐标系：${prj.take(80)}")
        crs.note?.let { warnings += "图层 $name：$it" }
        val byLabel = ctx.fields.associate { it.label to it.key }
        var bad = 0
        shapes.forEachIndexed { i, parts ->
            val row = table?.rows?.getOrNull(i)
            if (row?.deleted == true) return@forEachIndexed
            val g = runCatching { geometry(parts, crs) }.getOrNull()
            if (g == null) {
                bad++
                return@forEachIndexed
            }
            val kind = when (g) {
                is Geometry.Point, is Geometry.MultiPoint -> FeatureKind.POINT
                is Geometry.LineString, is Geometry.MultiLineString -> FeatureKind.LINE
                else -> FeatureKind.POLYGON
            }
            out += GeoFeature(
                id = ctx.newId(), projectId = ctx.projectId, kind = kind, geometry = g,
                properties = buildProperties {
                    table?.fields?.zip(row?.values.orEmpty())?.forEach { (f, v) ->
                        val key = byLabel[f.name] ?: f.name
                        when (v) {
                            null -> Unit
                            is String -> this[key] = v
                            is Number -> this[key] = v
                            is Boolean -> this[key] = v
                        }
                    }
                    titleOf(table, row)?.let { this[Props.TITLE] = it }
                    this["layer"] = name
                },
                createdAt = ctx.now, sync = SyncInfo(updatedAt = ctx.now),
            )
        }
        if (bad > 0) warnings += "图层 $name 有 $bad 个图形为空或坐标超出范围，已跳过"
    }

    /** A name-like attribute for the record's title, in the usual Chinese and English spellings. */
    private fun titleOf(table: Dbf.Table?, row: Dbf.Row?): String? {
        if (table == null || row == null) return null
        val names = listOf("名称", "名字", "点号", "编号", "name", "title", "label", "code", "id")
        for (n in names) {
            val i = table.fields.indexOfFirst { it.name.equals(n, ignoreCase = true) }
            val v = row.values.getOrNull(i)?.toString()?.takeIf { it.isNotBlank() }
            if (i >= 0 && v != null) return v
        }
        return null
    }

    // --- geometry -----------------------------------------------------------

    /** A shape's parts in file coordinates (x, y, z); a point is one part of one position. */
    internal class Xyz(val x: Double, val y: Double, val z: Double?)

    private enum class Type { POINT, LINE, POLYGON, MULTIPOINT }

    private class Shape(val type: Type, val parts: List<List<Xyz>>) : List<List<Xyz>> by parts

    private fun shapes(bytes: ByteArray): List<Shape> {
        val buf = ByteBuffer.wrap(bytes)
        if (bytes.size < 100 || buf.order(ByteOrder.BIG_ENDIAN).getInt(0) != 9994) throw FormatException("不是有效的 .shp 文件")
        val out = mutableListOf<Shape>()
        var pos = 100
        while (pos + 8 <= bytes.size) {
            buf.order(ByteOrder.BIG_ENDIAN)
            val length = buf.getInt(pos + 4) * 2
            val start = pos + 8
            if (length < 4 || start + length > bytes.size) break
            out += shape(ByteBuffer.wrap(bytes, start, length).slice().order(ByteOrder.LITTLE_ENDIAN))
            pos = start + length
        }
        return out
    }

    private fun shape(b: ByteBuffer): Shape {
        val code = b.getInt(0)
        val hasZ = code in setOf(11, 13, 15, 18)
        return when (code) {
            0 -> Shape(Type.POINT, emptyList())
            1, 11, 21 -> Shape(Type.POINT, listOf(listOf(Xyz(b.getDouble(4), b.getDouble(12), if (hasZ) b.getDouble(20) else null))))
            8, 18, 28 -> {
                val n = b.getInt(36)
                val zAt = 40 + 16 * n + 16
                Shape(Type.MULTIPOINT, (0 until n).map { i -> listOf(Xyz(b.getDouble(40 + 16 * i), b.getDouble(48 + 16 * i), if (hasZ) b.getDouble(zAt + 8 * i) else null)) })
            }
            3, 13, 23, 5, 15, 25 -> {
                val parts = b.getInt(36)
                val n = b.getInt(40)
                val first = 44 + 4 * parts
                val zAt = first + 16 * n + 16
                val starts = (0 until parts).map { b.getInt(44 + 4 * it) } + n
                val pts = (0 until n).map { i -> Xyz(b.getDouble(first + 16 * i), b.getDouble(first + 16 * i + 8), if (hasZ) b.getDouble(zAt + 8 * i) else null) }
                Shape(if (code % 10 == 5) Type.POLYGON else Type.LINE, starts.zipWithNext { s, e -> pts.subList(s, e) })
            }
            else -> throw FormatException("不支持的图形类型 $code")
        }
    }

    private fun geometry(s: Shape, crs: Crs): Geometry? {
        val parts = s.parts.map { part -> part.map { crs.toLngLat(it.x, it.y, it.z?.takeIf { z -> abs(z) < 1e6 }) } }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return when (s.type) {
            Type.POINT -> Geometry.Point(parts[0][0])
            Type.MULTIPOINT -> if (parts.size == 1) Geometry.Point(parts[0][0]) else Geometry.MultiPoint(parts.map { it[0] })
            Type.LINE -> parts.filter { it.size >= 2 }.let { l ->
                when (l.size) {
                    0 -> null
                    1 -> Geometry.LineString(l[0])
                    else -> Geometry.MultiLineString(l)
                }
            }
            Type.POLYGON -> polygons(parts.filter { it.size >= 4 })
        }
    }

    /** Groups rings: clockwise ones are outer rings, the others holes of the outer ring around them. */
    private fun polygons(rings: List<List<LngLat>>): Geometry? {
        if (rings.isEmpty()) return null
        val outers = rings.filter { signedArea(it) < 0 }.ifEmpty { rings }
        val holes = rings.filter { it !in outers }
        val groups = outers.associateWith { mutableListOf(ccw(it, false)) }
        for (h in holes) {
            val owner = outers.firstOrNull { contains(it, h[0]) } ?: outers.last()
            groups.getValue(owner) += ccw(h, true)
        }
        val polys = groups.values.map { Geometry.Polygon(it) }
        return if (polys.size == 1) polys[0] else Geometry.MultiPolygon(polys)
    }

    /** GeoJSON winding (RFC 7946): outer rings counter-clockwise, holes clockwise. */
    private fun ccw(ring: List<LngLat>, hole: Boolean) = if ((signedArea(ring) > 0) != hole) ring else ring.asReversed()

    private fun signedArea(ring: List<LngLat>) = ring.zipWithNext { a, b -> a.lng * b.lat - b.lng * a.lat }.sum() / 2

    private fun contains(ring: List<LngLat>, p: LngLat): Boolean {
        var inside = false
        ring.zipWithNext { a, b ->
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng) inside = !inside
        }
        return inside
    }

    // --- coordinate systems -------------------------------------------------

    internal class Crs(
        val note: String?,
        private val tm: TransverseMercator?,
        /** Read plane coordinates through this grid's datum shift; [tm] then only gives the false easting. */
        private val grid: org.geocrew.core.model.LocalGrid? = null,
    ) {
        fun toLngLat(x: Double, y: Double, z: Double?): LngLat = when {
            tm == null -> LngLat(x, y, z)
            grid != null -> grid.fromGrid(y - tm.falseNorthing, x - tm.falseEasting + 500_000.0, z ?: 0.0).let { LngLat(it.lng, it.lat, z) }
            else -> tm.inverse(x, y).let { LngLat(it.lng, it.lat, z) }
        }

        companion object {
            private val PARAM = Regex("""PARAMETER\s*\[\s*"([^"]+)"\s*,\s*([-+0-9.eE]+)\s*]""", RegexOption.IGNORE_CASE)
            private val PROJECTION = Regex("""PROJECTION\s*\[\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            private val SPHEROID = Regex("""(?:SPHEROID|ELLIPSOID)\s*\[\s*"([^"]*)"\s*,\s*([0-9.eE+]+)\s*,\s*([0-9.eE+]+)""", RegexOption.IGNORE_CASE)

            fun of(prj: String?, sample: Xyz?, grid: org.geocrew.core.model.LocalGrid? = null): Crs? {
                if (prj.isNullOrBlank()) return guess(sample)
                val sph = SPHEROID.find(prj)
                val ellipsoid = sph?.let { m ->
                    val a = m.groupValues[2].toDoubleOrNull() ?: 0.0
                    val inv = m.groupValues[3].toDoubleOrNull() ?: 0.0
                    Ellipsoid.entries.minByOrNull { abs(it.a - a) + 1000 * abs(it.inverseFlattening - inv) }
                } ?: Ellipsoid.CGCS2000
                val local = ellipsoid == Ellipsoid.XIAN80 || ellipsoid == Ellipsoid.BEIJING54
                val shift = if (local) "${ellipsoid.label} 坐标按 CGCS2000 显示，没有做基准转换，可能偏几十到一百多米" else null
                if (!prj.trimStart().startsWith("PROJCS", ignoreCase = true) && !prj.trimStart().startsWith("PROJCRS", ignoreCase = true)) {
                    return Crs(shift, null)
                }
                val proj = PROJECTION.find(prj)?.groupValues?.get(1)?.lowercase().orEmpty()
                if ("transverse" !in proj && "gauss" !in proj) return null
                val p = PARAM.findAll(prj).associate { it.groupValues[1].lowercase() to it.groupValues[2].toDouble() }
                if (abs(p["latitude_of_origin"] ?: 0.0) > 1e-9) return null
                val cm = p["central_meridian"] ?: return null
                val tm = TransverseMercator(
                    ellipsoid,
                    centralMeridian = cm,
                    scale = p["scale_factor"] ?: 1.0,
                    falseEasting = p["false_easting"] ?: 500_000.0,
                    falseNorthing = p["false_northing"] ?: 0.0,
                )
                // The project's own old grid with its datum shift: read through it instead of only swapping ellipsoids.
                val own = grid?.takeIf {
                    local && it.shift != null && it.ellipsoid == ellipsoid && abs(tm.scale - 1.0) < 1e-9 &&
                        abs(Geodesy.wrapLongitude(cm - GaussKruger.centralMeridian(it.zone, it.width))) < 1e-6
                }
                if (own != null) return Crs("按本工程的本地坐标系（${own.name}）做了基准转换", tm, own)
                return Crs(shift, tm)
            }

            /** No .prj: degrees, or Gauss-Krüger whose easting carries the zone number. */
            private fun guess(s: Xyz?): Crs? {
                s ?: return Crs(null, null)
                if (s.x in -180.0..180.0 && s.y in -90.0..90.0) return Crs("没有 .prj，按经纬度读取", null)
                val zone = floor(s.x / 1_000_000).toInt()
                val width = when (zone) {
                    in 13..23 -> 6
                    in 24..45 -> 3
                    else -> return null
                }
                val cm = if (width == 6) zone * 6.0 - 3 else zone * 3.0
                return Crs("没有 .prj，按 CGCS2000 ${width}° 带第 $zone 带读取", TransverseMercator(Ellipsoid.CGCS2000, cm, falseEasting = zone * 1_000_000.0 + 500_000.0))
            }
        }
    }

    // --- .dbf ---------------------------------------------------------------

    internal object Dbf {
        class Field(val name: String, val type: Char, val length: Int, val decimals: Int)
        class Row(val deleted: Boolean, val values: List<Any?>)
        class Table(val fields: List<Field>, val rows: List<Row>)

        fun read(bytes: ByteArray, cpg: String?): Table {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = b.getInt(4)
            val headerLength = b.getShort(8).toInt() and 0xFFFF
            val recordLength = b.getShort(10).toInt() and 0xFFFF
            val raw = mutableListOf<Triple<ByteArray, Char, Pair<Int, Int>>>()
            var at = 32
            while (at + 32 <= headerLength && bytes[at] != 0x0D.toByte()) {
                val nameBytes = bytes.copyOfRange(at, at + 11).takeWhile { it != 0.toByte() }.toByteArray()
                raw += Triple(nameBytes, (bytes[at + 11].toInt() and 0xFF).toChar(), (bytes[at + 16].toInt() and 0xFF) to (bytes[at + 17].toInt() and 0xFF))
                at += 32
            }
            val charset = encoding(cpg, bytes[29].toInt() and 0xFF, bytes, headerLength)
            val fields = raw.map { (n, t, ld) -> Field(String(n, charset).trim(), t.uppercaseChar(), ld.first, ld.second) }
            val rows = (0 until count).mapNotNull { r ->
                val start = headerLength + r * recordLength
                if (start + recordLength > bytes.size) return@mapNotNull null
                var off = start + 1
                Row(bytes[start] == '*'.code.toByte(), fields.map { f ->
                    val text = String(bytes, off, f.length, charset).trim()
                    off += f.length
                    value(f, text)
                })
            }
            return Table(fields, rows)
        }

        private fun value(f: Field, text: String): Any? {
            if (text.isEmpty()) return null
            return when (f.type) {
                'N', 'F' -> if (f.decimals == 0) text.toLongOrNull() ?: text.toDoubleOrNull() else text.toDoubleOrNull()
                'L' -> when (text.uppercase()) {
                    "T", "Y" -> true
                    "F", "N" -> false
                    else -> null
                }
                'D' -> if (text.length == 8 && text.all(Char::isDigit)) "${text.take(4)}-${text.substring(4, 6)}-${text.takeLast(2)}" else text
                else -> text
            }
        }

        private fun encoding(cpg: String?, ldid: Int, bytes: ByteArray, headerLength: Int): Charset {
            val declared = cpg?.trim()?.uppercase()
            when (declared) {
                null, "" -> Unit
                "UTF-8", "UTF8", "65001" -> return Charsets.UTF_8
                "GBK", "936", "CP936", "GB2312", "GB18030", "ANSI 936" -> return Charset.forName("GB18030")
                else -> runCatching { Charset.forName(declared) }.getOrNull()?.let { return it }
            }
            if (ldid == 0x4D) return Charset.forName("GB18030")
            // Undeclared: UTF-8 if the whole file decodes as UTF-8, otherwise the Chinese code page.
            val strict = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                strict.decode(ByteBuffer.wrap(bytes, headerLength, (bytes.size - headerLength).coerceAtLeast(0)))
                Charsets.UTF_8
            } catch (_: CharacterCodingException) {
                Charset.forName("GB18030")
            }
        }
    }

    private fun ZipInputStream.readLimited(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw FormatException("文件过大")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
