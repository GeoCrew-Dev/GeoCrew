package org.geocrew.storage

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The CRSs PROJ knows, read from its own database (proj.db, unpacked by
 * [NativeData]), for choosing one by name, code or where it applies, as QGIS
 * does. Deprecated CRSs and those of other bodies (IAU) are left out of
 * searches. Names are PROJ's, in English; Chinese search words for the CRSs
 * used in China are turned into them (西安80, 高斯 3 度带, 38 带, ...).
 */
object CrsCatalog {
    /** A CRS: how GDAL names it (EPSG:4547), its name, its kind as PROJ says (projected, geographic 2D, ...) and where it applies. */
    data class Entry(val srs: String, val name: String, val type: String, val area: String?, val deprecated: Boolean = false)

    /** The kinds a search can ask for, and PROJ's types for each. */
    val KINDS = mapOf(
        "geographic" to listOf("geographic 2D"),
        "projected" to listOf("projected"),
        "geographic3d" to listOf("geographic 3D"),
        "geocentric" to listOf("geocentric"),
        "vertical" to listOf("vertical"),
        "compound" to listOf("compound"),
    )

    /** The kinds a layer's CRS can be. */
    val HORIZONTAL = setOf("geographic", "projected")

    private const val AUTHORITIES = "'EPSG', 'ESRI', 'IGNF', 'OGC', 'NKG'"

    // A CRS's name in lower case without spaces, hyphens and underscores, so "WGS84" finds "WGS 84" and "gauss kruger" finds "Gauss-Kruger".
    private const val FLAT_NAME = "replace(replace(replace(lower(c.name), ' ', ''), '-', ''), '_', '')"

    private const val AREA = "(SELECT e.name FROM usage u JOIN extent e ON e.auth_name = u.extent_auth_name AND e.code = u.extent_code " +
        "WHERE u.object_table_name = c.table_name AND u.object_auth_name = c.auth_name AND u.object_code = c.code LIMIT 1)"

    private var db: SQLiteDatabase? = null

    @Synchronized
    private fun database(): SQLiteDatabase = db ?: run {
        val file = File(checkNotNull(NativeData.dir) { "GeoCrew-Native is not set up" }, "proj.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).also { db = it }
    }

    /**
     * CRSs whose name or code has every word of [text], of the [kinds] asked
     * for, and with [near] (longitude, latitude) in their area when given; at
     * most [limit], EPSG's first. A word that is a CRS's code, or AUTH:CODE,
     * puts it at the top. Empty [text] with [near] lists the CRSs for that place.
     */
    fun search(text: String, kinds: Set<String> = HORIZONTAL, near: Pair<Double, Double>? = null, limit: Int = 200): List<Entry> {
        val words = words(text)
        if (words.isEmpty() && near == null) return emptyList()
        val types = kinds.flatMap { KINDS[it].orEmpty() }.ifEmpty { return emptyList() }
        val where = mutableListOf("c.deprecated = 0", "c.auth_name IN ($AUTHORITIES)", "c.type IN (${types.joinToString { "?" }})")
        val args = types.toMutableList()
        for (w in words) {
            where += "($FLAT_NAME LIKE ? ESCAPE '\\' OR CAST(c.code AS TEXT) = ? OR lower(c.auth_name || ':' || c.code) = ?)"
            args += listOf("%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", w, w)
        }
        if (near != null) {
            val (lon, lat) = near
            where += "EXISTS (SELECT 1 FROM usage u JOIN extent e ON e.auth_name = u.extent_auth_name AND e.code = u.extent_code " +
                "WHERE u.object_table_name = c.table_name AND u.object_auth_name = c.auth_name AND u.object_code = c.code " +
                "AND e.south_lat <= ? AND e.north_lat >= ? " +
                "AND (CASE WHEN e.west_lon <= e.east_lon THEN e.west_lon <= ? AND e.east_lon >= ? ELSE e.west_lon <= ? OR e.east_lon >= ? END))"
            args += listOf(lat, lat, lon, lon, lon, lon).map { it.toString() }
        }
        // A word naming a CRS by its code puts that CRS first.
        val exact = if (words.isEmpty()) "" else "(" + words.joinToString(" OR ") { "CAST(c.code AS TEXT) = ? OR lower(c.auth_name || ':' || c.code) = ?" } + ") DESC, "
        args += words.flatMap { listOf(it, it) }
        val sql = "SELECT c.auth_name, c.code, c.name, c.type, $AREA FROM crs_view c WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY $exact(c.auth_name = 'EPSG') DESC, c.name LIMIT ${limit.coerceIn(1, 1000)}"
        return query(sql, args)
    }

    /** The CRSs named in [srs] (AUTH:CODE, deprecated ones too), in that order; those not in the database are left out. */
    fun find(srs: List<String>): List<Entry> = srs.mapNotNull { s ->
        val (auth, code) = s.split(":", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        query(
            "SELECT c.auth_name, c.code, c.name, c.type, $AREA, c.deprecated FROM crs_view c " +
                "WHERE upper(c.auth_name) = upper(?) AND CAST(c.code AS TEXT) = ? LIMIT 1",
            listOf(auth.trim(), code.trim()),
        ).firstOrNull()
    }

    private fun query(sql: String, args: List<String>): List<Entry> = database().rawQuery(sql, args.toTypedArray()).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Entry(
                        srs = c.getString(0) + ":" + c.getString(1),
                        name = c.getString(2),
                        type = c.getString(3),
                        area = if (c.isNull(4)) null else c.getString(4),
                        deprecated = c.columnCount > 5 && c.getInt(5) != 0,
                    ),
                )
            }
        }
    }

    /**
     * The words of a search, flattened like the names they are matched against,
     * with Chinese words for China's CRSs turned into PROJ's: 西安80 is Xian 1980,
     * 38带 is zone 38, 中央经线114 is CM 114E.
     */
    internal fun words(text: String): List<String> {
        var t = text.lowercase()
        for ((pattern, replacement) in ALIASES) t = pattern.replace(t, " $replacement ")
        return t.split(Regex("[\\s/,，、;；()（）]+"))
            .map { it.replace("-", "").replace("_", "") }
            .filter { it.isNotEmpty() }
    }

    // Longest first, so 西安80 is not read as 西安 and 80.
    private val ALIASES = listOf(
        "第?\\s*(\\d+)\\s*号?\\s*带" to "zone$1",
        "中央经线\\s*(\\d+)\\s*°?\\s*e?" to "cm$1e",
        "(?:国家|中国|cgcs)\\s*2000|2000\\s*国家" to "cgcs2000",
        "西安\\s*(?:19)?80" to "xian1980",
        "新\\s*北京\\s*(?:19)?54|新\\s*54" to "newbeijing",
        "北京\\s*(?:19)?54" to "beijing1954",
        "西安" to "xian",
        "北京" to "beijing",
        "高斯\\s*[-–]?\\s*克[吕里]格|高斯" to "gausskruger",
        "(?:3|三)\\s*度\\s*带?" to "3degree",
        "(?:6|六)\\s*度\\s*带?" to "",
        "通用横轴墨卡托" to "utm",
        "(?:web|网络|伪)\\s*墨卡托" to "pseudomercator",
        "横轴墨卡托" to "transversemercator",
        "墨卡托" to "mercator",
        "兰伯特" to "lambert",
        "经纬度|坐标系" to "",
    ).map { (pattern, replacement) -> Regex(pattern) to replacement }
}
