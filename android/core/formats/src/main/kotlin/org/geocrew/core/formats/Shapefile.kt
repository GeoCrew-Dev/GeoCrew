package org.geocrew.core.formats

import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ESRI Shapefile export, as a zip with one shapefile per geometry type
 * (points, lines, polygons). Attributes go to the DBF in UTF-8 (declared in
 * the .cpg file); numeric properties become numeric fields. Import reads a
 * zip of shapefiles, see [ShapefileReader].
 */
object ShapefileFormat : FeatureFormat {
    override val id = "shapefile"
    override val displayName = "Shapefile（zip）"
    override val extension = "zip"
    override val mimeType = "application/zip"
    override val canRead = true
    override val canWrite = true

    private const val WGS84_PRJ =
        "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]"

    private const val MAX_EXTRA_FIELDS = 64

    internal enum class Layer(val suffix: String, val shapeType: Int) {
        POINTS("points", 1), LINES("lines", 3), POLYGONS("polygons", 5)
    }

    /** One shapefile record: a shape and the feature its attributes come from. */
    internal class Row(val feature: GeoFeature, val shape: Shape)

    internal sealed interface Shape {
        data class Point(val p: LngLat) : Shape
        /** Parts are lines (PolyLine) or rings (Polygon). */
        data class Parts(val parts: List<List<LngLat>>) : Shape
    }

    override fun read(input: java.io.InputStream, ctx: ImportContext): ImportResult = ShapefileReader.read(input, ctx)

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val rows = mutableMapOf<Layer, MutableList<Row>>()
        var skipped = 0
        for (f in features) {
            if (f.deleted) continue
            val shapes = shapesOf(f.geometry)
            if (shapes.isEmpty()) skipped++
            shapes.forEach { (layer, shape) -> rows.getOrPut(layer) { mutableListOf() } += Row(f, shape) }
        }
        val base = ctx.title.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }.ifEmpty { "geocrew" }
        ZipOutputStream(output).use { zip ->
            for ((layer, list) in rows.toSortedMap()) {
                val name = "${base}_${layer.suffix}"
                val (shp, shx) = shpAndShx(layer, list.map { it.shape })
                zip.entry("$name.shp", shp)
                zip.entry("$name.shx", shx)
                zip.entry("$name.dbf", dbf(list.map { it.feature }))
                zip.entry("$name.prj", WGS84_PRJ.toByteArray())
                zip.entry("$name.cpg", "UTF-8".toByteArray())
            }
        }
        return ExportResult(written = features.count { !it.deleted } - skipped, skipped = skipped)
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    internal fun shapesOf(g: Geometry?): List<Pair<Layer, Shape>> = when (g) {
        null -> emptyList()
        is Geometry.Point -> listOf(Layer.POINTS to Shape.Point(g.position))
        is Geometry.MultiPoint -> g.positions.map { Layer.POINTS to Shape.Point(it) }
        is Geometry.LineString -> if (g.positions.size >= 2) listOf(Layer.LINES to Shape.Parts(listOf(g.positions))) else emptyList()
        is Geometry.MultiLineString -> g.lines.filter { it.size >= 2 }.takeIf { it.isNotEmpty() }
            ?.let { listOf(Layer.LINES to Shape.Parts(it)) }.orEmpty()
        is Geometry.Polygon -> polygonRings(listOf(g))?.let { listOf(Layer.POLYGONS to Shape.Parts(it)) }.orEmpty()
        is Geometry.MultiPolygon -> polygonRings(g.polygons)?.let { listOf(Layer.POLYGONS to Shape.Parts(it)) }.orEmpty()
        is Geometry.GeometryCollection -> g.geometries.flatMap { shapesOf(it) }
    }

    /** Shapefile rings: outer rings clockwise, holes counter-clockwise. */
    private fun polygonRings(polygons: List<Geometry.Polygon>): List<List<LngLat>>? {
        val rings = polygons.flatMap { p ->
            p.rings.filter { it.size >= 4 }.mapIndexed { i, ring ->
                val cw = signedArea(ring) < 0
                if ((i == 0) == cw) ring else ring.asReversed()
            }
        }
        return rings.ifEmpty { null }
    }

    private fun signedArea(ring: List<LngLat>): Double =
        ring.zipWithNext { a, b -> a.lng * b.lat - b.lng * a.lat }.sum() / 2

    // --- .shp / .shx --------------------------------------------------------

    private class Box(var minX: Double = Double.MAX_VALUE, var minY: Double = Double.MAX_VALUE, var maxX: Double = -Double.MAX_VALUE, var maxY: Double = -Double.MAX_VALUE) {
        fun add(p: LngLat) {
            minX = minOf(minX, p.lng); minY = minOf(minY, p.lat); maxX = maxOf(maxX, p.lng); maxY = maxOf(maxY, p.lat)
        }
        fun add(o: Box) { minX = minOf(minX, o.minX); minY = minOf(minY, o.minY); maxX = maxOf(maxX, o.maxX); maxY = maxOf(maxY, o.maxY) }
    }

    private fun content(layer: Layer, s: Shape, box: Box): ByteArray = when (s) {
        is Shape.Point -> le(20).apply {
            putInt(layer.shapeType); putDouble(s.p.lng); putDouble(s.p.lat)
            box.add(s.p)
        }.array()
        is Shape.Parts -> {
            val pts = s.parts.flatten()
            val b = Box().apply { pts.forEach(::add) }
            box.add(b)
            le(44 + 4 * s.parts.size + 16 * pts.size).apply {
                putInt(layer.shapeType)
                putDouble(b.minX); putDouble(b.minY); putDouble(b.maxX); putDouble(b.maxY)
                putInt(s.parts.size); putInt(pts.size)
                var start = 0
                for (part in s.parts) { putInt(start); start += part.size }
                for (p in pts) { putDouble(p.lng); putDouble(p.lat) }
            }.array()
        }
    }

    internal fun shpAndShx(layer: Layer, shapes: List<Shape>): Pair<ByteArray, ByteArray> {
        val box = Box()
        val records = shapes.map { content(layer, it, box) }
        val shpLength = 100 + records.sumOf { 8 + it.size }
        val shp = ByteArrayOutputStream(shpLength)
        shp.write(header(layer, box, shpLength))
        val shx = ByteArrayOutputStream(100 + 8 * records.size)
        shx.write(header(layer, box, 100 + 8 * records.size))
        var offset = 100
        records.forEachIndexed { i, c ->
            shp.write(be(8).putInt(i + 1).putInt(c.size / 2).array())
            shp.write(c)
            shx.write(be(8).putInt(offset / 2).putInt(c.size / 2).array())
            offset += 8 + c.size
        }
        return shp.toByteArray() to shx.toByteArray()
    }

    private fun header(layer: Layer, box: Box, length: Int): ByteArray {
        val b = ByteBuffer.allocate(100)
        b.order(ByteOrder.BIG_ENDIAN).putInt(9994)
        repeat(5) { b.putInt(0) }
        b.putInt(length / 2)
        b.order(ByteOrder.LITTLE_ENDIAN).putInt(1000).putInt(layer.shapeType)
        b.putDouble(box.minX).putDouble(box.minY).putDouble(box.maxX).putDouble(box.maxY)
        repeat(4) { b.putDouble(0.0) }
        return b.array()
    }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun be(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.BIG_ENDIAN)

    // --- .dbf ---------------------------------------------------------------

    internal data class Field(val name: String, val type: Char, val length: Int, val decimals: Int, val value: (GeoFeature) -> String?)

    internal fun fields(features: List<GeoFeature>): List<Field> {
        val fixed = listOf(
            Field("ID", 'C', 36, 0) { it.id },
            Field("KIND", 'C', 20, 0) { it.kind.id },
            Field("TITLE", 'C', 100, 0) { it.title },
            Field("NOTE", 'C', 254, 0) { it.note },
            Field("CREATED", 'C', 20, 0) { it.createdAt.toString().take(20) },
        )
        val skip = setOf(Props.KIND, Props.TITLE, Props.NOTE, Props.PHOTOS)
        val keys = features.flatMap { f -> f.properties.keys }.distinct().filter { it !in skip }
        val used = fixed.map { it.name }.toMutableSet()
        val extra = keys.mapNotNull { key ->
            val values = features.mapNotNull { (it.properties[key] as? JsonPrimitive) }
            if (values.isEmpty()) return@mapNotNull null
            val name = dbfName(key, used) ?: return@mapNotNull null
            used += name
            val numeric = values.all { !it.isString && it.content.toDoubleOrNull() != null }
            if (numeric) {
                Field(name, 'N', 19, 6) { f -> (f.properties[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { "%.6f".format(java.util.Locale.ROOT, it) } }
            } else {
                Field(name, 'C', 254, 0) { f -> (f.properties[key] as? JsonPrimitive)?.content }
            }
        }.take(MAX_EXTRA_FIELDS)
        return fixed + extra
    }

    /** DBF names are at most 10 ASCII characters; non-ASCII keys get a numbered name. */
    private fun dbfName(key: String, used: Set<String>): String? {
        val ascii = key.uppercase().map { if (it in 'A'..'Z' || it in '0'..'9') it else '_' }.joinToString("").trim('_')
        val base = (if (ascii.isEmpty() || ascii.count { it == '_' } * 2 > ascii.length) "FIELD" else ascii).take(10)
        if (base !in used && base != "FIELD") return base
        for (i in 1..999) {
            val candidate = base.take(10 - i.toString().length) + i
            if (candidate !in used) return candidate
        }
        return null
    }

    internal fun dbf(features: List<GeoFeature>): ByteArray {
        val fields = fields(features)
        val headerSize = 32 + 32 * fields.size + 1
        val recordSize = 1 + fields.sumOf { it.length }
        val out = ByteArrayOutputStream(headerSize + recordSize * features.size + 1)
        val today = LocalDate.now()
        val h = le(32)
        h.put(0x03).put((today.year - 1900).toByte()).put(today.monthValue.toByte()).put(today.dayOfMonth.toByte())
        h.putInt(features.size).putShort(headerSize.toShort()).putShort(recordSize.toShort())
        out.write(h.array())
        for (f in fields) {
            val d = ByteArray(32)
            f.name.toByteArray(Charsets.US_ASCII).copyInto(d, 0)
            d[11] = f.type.code.toByte()
            d[16] = f.length.toByte()
            d[17] = f.decimals.toByte()
            out.write(d)
        }
        out.write(0x0D)
        for (feature in features) {
            out.write(' '.code)
            for (f in fields) out.write(cell(f, f.value(feature)))
        }
        out.write(0x1A)
        return out.toByteArray()
    }

    private fun cell(f: Field, value: String?): ByteArray {
        val bytes = ByteArray(f.length) { ' '.code.toByte() }
        if (value == null) return bytes
        if (f.type == 'N') {
            val v = value.toByteArray(Charsets.US_ASCII)
            if (v.size <= f.length) v.copyInto(bytes, f.length - v.size) // right-aligned
            return bytes
        }
        truncateUtf8(value, f.length).copyInto(bytes, 0)
        return bytes
    }

    /** UTF-8 bytes of [s], cut to [max] without splitting a character. */
    internal fun truncateUtf8(s: String, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val b = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (out.size() + b.size > max) break
            out.write(b)
            i += Character.charCount(cp)
        }
        return out.toByteArray()
    }
}
