package org.geocrew.core.formats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldType
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.edit
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OGC GeoPackage 1.3, the single-file SQLite format QGIS, ArcGIS and most
 * desktop GIS read and write natively.
 *
 * Export writes one table each for points, lines and polygons in EPSG:4326
 * (CGCS2000 is taken as equal at field accuracy): the record id, kind,
 * title, note, lithology, attitude, line style, meeting time, map colour and
 * last change, a column per form field headed by its label, and all
 * properties as JSON so a GeoCrew file imports back without loss.
 *
 * Import reads every feature table: longitude/latitude, or Gauss-Krüger in
 * CGCS2000, WGS 84, Xi'an 80 or Beijing 54 as in Shapefile imports, with
 * the other columns as attributes. Curves and tiles are not read.
 *
 * SQLite needs a real file, so both work on a temporary file in [scratch].
 */
class GeoPackageFormat(private val engine: SqlEngine, private val scratch: () -> File) : FeatureFormat {
    override val id = "gpkg"
    override val displayName = "GeoPackage（QGIS、ArcGIS）"
    override val extension = "gpkg"
    override val mimeType = "application/geopackage+sqlite3"
    override val canRead = true
    override val canWrite = true

    override fun nothingToWrite(features: List<GeoFeature>): String? =
        if (features.none { writable(it) }) "没有带位置的记录可以导出" else null

    private fun writable(f: GeoFeature) = !f.deleted && f.geometry != null && f.geometry !is Geometry.GeometryCollection

    override fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext): ExportResult {
        val file = File.createTempFile("export-", ".gpkg", scratch()).also { it.delete() }
        try {
            val result = engine.open(file).use { db -> db.transaction { writeTo(db, features, ctx) } }
            file.inputStream().use { it.copyTo(output) }
            output.flush()
            return result
        } finally {
            cleanUp(file)
        }
    }

    override fun read(input: InputStream, ctx: ImportContext): ImportResult {
        val file = File.createTempFile("import-", ".gpkg", scratch())
        try {
            file.outputStream().use { input.copyTo(it) }
            file.inputStream().use { s ->
                val head = ByteArray(16)
                if (s.read(head) < 16 || !head.decodeToString().startsWith("SQLite format 3")) throw FormatException("不是 GeoPackage 文件")
            }
            return engine.open(file).use { db -> readFrom(db, ctx) }
        } finally {
            cleanUp(file)
        }
    }

    private fun cleanUp(file: File) {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(file.path + suffix).delete()
    }

    // --- writing --------------------------------------------------------------

    private class Layer(val table: String, val title: String)

    private fun layerOf(g: Geometry) = when (g) {
        is Geometry.Point, is Geometry.MultiPoint -> POINTS
        is Geometry.LineString, is Geometry.MultiLineString -> LINES
        else -> POLYGONS
    }

    private class Column(val name: String, val real: Boolean, val value: (GeoFeature) -> Any?)

    private fun columns(ctx: ExportContext): List<Column> {
        fun text(key: String): (GeoFeature) -> Any? = { f -> (f.properties[key] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        fun number(key: String): (GeoFeature) -> Any? = { f -> (f.properties[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull }
        val fixed = listOf(
            Column("record_id", false) { it.id },
            Column("kind", false) { it.kind.id },
            Column("title", false, text(Props.TITLE)),
            Column("note", false, text(Props.NOTE)),
            Column("lithology", false, text(Props.LITHOLOGY)),
            Column("strike", true, number(Props.STRIKE)),
            Column("dip_direction", true, number(Props.DIP_DIRECTION)),
            Column("dip", true, number(Props.DIP)),
            Column("line_style", false, text(Props.LINE_STYLE)),
            Column("meet_at", false, text(Props.MEET_AT)),
            Column("color", false) { f -> ownColor(f) ?: ctx.legend.colorOf(f) },
            Column("updated_at", false) { it.sync.updatedAt.toString() },
            Column(PROPERTIES, false) { it.properties.toString() },
        )
        val taken = fixed.mapTo(mutableSetOf("fid", "geom")) { it.name.lowercase() }
        val form = ctx.fields.mapNotNull { field ->
            val name = field.label.trim().ifEmpty { field.key }.replace('"', '\'')
            if (!taken.add(name.lowercase()) || field.key in Props.MANAGED) return@mapNotNull null
            if (field.type == FieldType.NUMBER) Column(name, true, number(field.key)) else Column(name, false) { f ->
                (f.properties[field.key] as? JsonPrimitive)?.contentOrNull
            }
        }
        return fixed + form
    }

    private fun ownColor(f: GeoFeature): String? = listOf("stroke", "fill", "marker-color").firstNotNullOfOrNull { k ->
        (f.properties[k] as? JsonPrimitive)?.contentOrNull?.takeIf { HEX.matches(it) }?.uppercase()
    }

    private fun writeTo(db: SqlDatabase, features: List<GeoFeature>, ctx: ExportContext): ExportResult {
        db.execute("PRAGMA application_id = $APPLICATION_ID")
        db.execute("PRAGMA user_version = 10300")
        SCHEMA.forEach { db.execute(it) }
        db.execute("INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined cartesian SRS', -1, 'NONE', -1, 'undefined', 'undefined cartesian coordinate reference system')")
        db.execute("INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined geographic SRS', 0, 'NONE', 0, 'undefined', 'undefined geographic coordinate reference system')")
        db.execute("INSERT INTO gpkg_spatial_ref_sys VALUES ('WGS 84 geodetic', 4326, 'EPSG', 4326, ?, 'longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid')", listOf(WGS84_WKT))

        val cols = columns(ctx)
        val extents = linkedMapOf<Layer, Wkb.Envelope>()
        var written = 0
        var skipped = 0
        for (f in features) {
            if (f.deleted) continue
            val g = f.geometry
            if (g == null || g is Geometry.GeometryCollection) {
                skipped++
                continue
            }
            val layer = layerOf(g)
            val (wkb, env) = Wkb.encode(g)
            val ext = extents.getOrPut(layer) {
                db.execute(
                    """CREATE TABLE "${layer.table}" (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom BLOB, """ +
                        cols.joinToString(", ") { "\"${it.name}\" ${if (it.real) "REAL" else "TEXT"}" } + ")",
                )
                Wkb.Envelope()
            }
            ext.add(env)
            db.execute(
                """INSERT INTO "${layer.table}" (geom, ${cols.joinToString(", ") { "\"${it.name}\"" }}) VALUES (?${", ?".repeat(cols.size)})""",
                listOf(blob(wkb, env)) + cols.map { it.value(f) },
            )
            written++
        }
        for ((layer, e) in extents) {
            db.execute(
                "INSERT INTO gpkg_contents (table_name, data_type, identifier, description, min_x, min_y, max_x, max_y, srs_id) VALUES (?, 'features', ?, ?, ?, ?, ?, ?, 4326)",
                listOf(layer.table, layer.title, ctx.title, e.minX, e.minY, e.maxX, e.maxY),
            )
            db.execute("INSERT INTO gpkg_geometry_columns VALUES (?, 'geom', 'GEOMETRY', 4326, 2, 0)", listOf(layer.table))
        }
        return ExportResult(written = written, skipped = skipped)
    }

    /** GeoPackage binary header (little endian, XY envelope, SRS 4326) and the WKB. */
    private fun blob(wkb: ByteArray, e: Wkb.Envelope): ByteArray {
        val b = ByteBuffer.allocate(8 + 32 + wkb.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(0x03)
        b.putInt(4326)
        b.putDouble(e.minX).putDouble(e.maxX).putDouble(e.minY).putDouble(e.maxY)
        b.put(wkb)
        return b.array()
    }

    // --- reading --------------------------------------------------------------

    private fun readFrom(db: SqlDatabase, ctx: ImportContext): ImportResult {
        val layers = try {
            db.query(
                "SELECT c.table_name, g.column_name, g.srs_id, c.identifier FROM gpkg_contents c " +
                    "JOIN gpkg_geometry_columns g ON g.table_name = c.table_name WHERE c.data_type = 'features' ORDER BY c.table_name",
            )
        } catch (e: Exception) {
            throw FormatException("不是 GeoPackage 文件（缺少 gpkg_contents 表）", e)
        }
        if (layers.isEmpty()) throw FormatException("GeoPackage 里没有要素图层（可能只有瓦片）")
        val out = mutableListOf<GeoFeature>()
        val warnings = mutableListOf<String>()
        val byLabel = ctx.fields.associate { it.label to it.key }
        for (row in layers) {
            val table = row[0] as? String ?: continue
            val geomCol = row[1] as? String ?: continue
            val srs = (row[2] as? Number)?.toLong() ?: 0L
            val name = (row[3] as? String)?.takeIf { it.isNotBlank() } ?: table
            val srsRow = db.query("SELECT definition, organization, organization_coordsys_id FROM gpkg_spatial_ref_sys WHERE srs_id = ?", listOf(srs)).firstOrNull()
            val info = db.query("PRAGMA table_info(\"${table.replace("\"", "\"\"")}\")")
            val names = info.map { it[1] as String }
            val pk = info.firstOrNull { (it[5] as? Number)?.toInt() == 1 }?.get(1) as? String
            val geomIndex = names.indexOfFirst { it.equals(geomCol, ignoreCase = true) }
            if (geomIndex < 0) continue
            var crs: ShapefileReader.Crs? = null
            var bad = 0
            var count = 0
            val rows = db.query("SELECT * FROM \"${table.replace("\"", "\"\"")}\" LIMIT ${MAX_FEATURES + 1}")
            if (rows.size > MAX_FEATURES) warnings += "图层 $name 超过 $MAX_FEATURES 条，只导入了前 $MAX_FEATURES 条"
            for (r in rows.take(MAX_FEATURES)) {
                val bytes = r.getOrNull(geomIndex) as? ByteArray ?: continue
                val start = runCatching { headerLength(bytes) }.getOrNull()
                if (start == null) {
                    bad++
                    continue
                }
                val c = crs ?: (
                    crsOf(srs, srsRow, sample(bytes, start), name)
                        ?: throw FormatException("图层 $name 的坐标系不支持：${(srsRow?.getOrNull(0) as? String).orEmpty().take(80)}")
                    ).also { found ->
                    crs = found
                    found.note?.let { warnings += "图层 $name：$it" }
                }
                val geometry = runCatching { Wkb.decode(bytes, start) { x, y, z -> c.toLngLat(x, y, z) } }.getOrNull()
                if (geometry == null || geometry is Geometry.GeometryCollection) {
                    bad++
                    continue
                }
                out += feature(geometry, names, r, setOfNotNull(pk, names[geomIndex]), name, byLabel, ctx)
                count++
            }
            if (bad > 0) warnings += "图层 $name 有 $bad 个图形为空、类型不支持或坐标超出范围，已跳过"
            if (count == 0 && bad == 0) warnings += "图层 $name 是空的"
        }
        return ImportResult(out, warnings)
    }

    private fun feature(
        geometry: Geometry, names: List<String>, row: List<Any?>, skip: Set<String>, layer: String,
        byLabel: Map<String, String>, ctx: ImportContext,
    ): GeoFeature {
        val cells = names.zip(row).filter { (n, _) -> n !in skip }.toMap()
        // A GeoCrew file carries every property as JSON; take it whole.
        val own = (cells[PROPERTIES] as? String)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val props = own ?: buildProperties {
            for ((n, v) in cells) {
                val key = byLabel[n] ?: n
                when (v) {
                    is String -> if (v.isNotEmpty()) this[key] = v
                    is Long -> this[key] = v
                    is Double -> if (v.isFinite()) this[key] = v
                    else -> Unit
                }
            }
            if (Props.TITLE !in cells) titleOf(cells)?.let { this[Props.TITLE] = it }
            this["layer"] = layer
        }
        val kindId = (props[Props.KIND] as? JsonPrimitive)?.contentOrNull ?: (cells["kind"] as? String)
        val inferred = GeoJson.inferKind(geometry)
        val kind = kindId?.let(::FeatureKind)?.takeIf { family(it) == family(inferred) } ?: inferred
        val id = (cells["record_id"] as? String)?.takeIf { own != null && it.isNotBlank() && it.length <= 64 } ?: ctx.newId()
        return GeoFeature(
            id = id, projectId = ctx.projectId, kind = kind, geometry = geometry,
            properties = props.edit { remove(Props.KIND) }, createdAt = ctx.now, sync = SyncInfo(updatedAt = ctx.now),
        )
    }

    /** Point, line or polygon kinds, so a record keeps its kind only on a geometry of that family. */
    private fun family(k: FeatureKind) = when (k) {
        FeatureKind.POINT, FeatureKind.GEO_POINT, FeatureKind.PHOTO -> 0
        FeatureKind.POLYGON -> 2
        else -> 1
    }

    private fun titleOf(cells: Map<String, Any?>): String? {
        for (n in TITLE_COLUMNS) {
            val v = cells.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value ?: continue
            val s = when (v) {
                is String -> v
                is Long -> v.toString()
                else -> null
            }
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    /** Length of the GeoPackage binary header before the WKB. */
    private fun headerLength(b: ByteArray): Int {
        if (b.size < 8 || b[0] != 'G'.code.toByte() || b[1] != 'P'.code.toByte()) throw FormatException("几何不是 GeoPackage 格式")
        val flags = b[3].toInt()
        if (flags and 0x10 != 0) throw FormatException("空几何")
        val envelope = when ((flags shr 1) and 0x07) {
            0 -> 0
            1 -> 32
            2, 3 -> 48
            4 -> 64
            else -> throw FormatException("几何包络格式不对")
        }
        return 8 + envelope
    }

    /** The first coordinate of a geometry as stored, to tell degrees from zone-numbered metres. */
    private fun sample(bytes: ByteArray, start: Int): ShapefileReader.Xyz? {
        var first: ShapefileReader.Xyz? = null
        runCatching {
            Wkb.decode(bytes, start) { x, y, z ->
                if (first == null) first = ShapefileReader.Xyz(x, y, z)
                LngLat(0.0, 0.0)
            }
        }
        return first
    }

    private fun crsOf(srs: Long, row: List<Any?>?, sample: ShapefileReader.Xyz?, name: String): ShapefileReader.Crs? {
        val org = (row?.getOrNull(1) as? String)?.uppercase()
        val code = (row?.getOrNull(2) as? Number)?.toLong()
        if (org == "EPSG" && code in GEOGRAPHIC) return ShapefileReader.Crs.of("GEOGCS[\"$name\"]", sample)
        val def = (row?.getOrNull(0) as? String)?.takeIf { it.isNotBlank() && !it.equals("undefined", ignoreCase = true) }
        if (def == null && srs != 0L && srs != -1L) return null
        return ShapefileReader.Crs.of(def, sample)
    }

    companion object {
        private const val APPLICATION_ID = 1196444487 // "GPKG"
        private const val PROPERTIES = "properties"
        const val MAX_FEATURES = 50_000
        private val HEX = Regex("^#[0-9a-fA-F]{6}$")
        private val POINTS = Layer("points", "点记录")
        private val LINES = Layer("lines", "线记录")
        private val POLYGONS = Layer("polygons", "面记录")
        /** Geographic CRS read as longitude/latitude: WGS 84, CGCS2000, Xi'an 80, Beijing 54. */
        private val GEOGRAPHIC = setOf(4326L, 4490L, 4610L, 4214L)
        private val TITLE_COLUMNS = listOf("title", "名称", "名字", "点号", "编号", "name", "label", "code")

        private const val WGS84_WKT = """GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,AUTHORITY["EPSG","7030"]],AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4326"]]"""

        private val SCHEMA = listOf(
            """CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL,
                organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)""",
            """CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE,
                description TEXT DEFAULT '', last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER,
                CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id))""",
            """CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL,
                srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL,
                CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name), CONSTRAINT uk_gc_table_name UNIQUE (table_name),
                CONSTRAINT fk_gc_tn FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name),
                CONSTRAINT fk_gc_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys (srs_id))""",
        )
    }
}
