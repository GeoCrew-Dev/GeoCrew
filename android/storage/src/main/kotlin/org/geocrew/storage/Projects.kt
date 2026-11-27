package org.geocrew.storage

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.edit.EditTarget
import org.geocrew.core.edit.Field
import org.geocrew.core.edit.FieldType
import org.geocrew.core.edit.FileJournal
import org.geocrew.core.project.LayerTemplate
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.quote
import org.geocrew.nativelib.VectorDataset

/**
 * The projects in the GeoCrew folder [root] (ADR 0004; `/sdcard/GeoCrew` in the
 * app, ADR 0002). Unsaved edits are journalled under [journals], in the app's
 * own files (ADR 0005). Call [NativeData.setup] first.
 */
class Projects(private val root: File, private val journals: File) {
    private val dir = File(root, ProjectFormat.PROJECTS)

    /** Where exported copies go, next to the projects. */
    val exports: File get() = File(root, EXPORTS)

    /** The names of the projects, in order. */
    fun list(): List<String> =
        dir.listFiles().orEmpty().filter { it.isDirectory && gpkg(it.name).isFile }.map { it.name }.sorted()

    /**
     * When project [name] last changed, in milliseconds since 1970. Its log
     * written ahead counts only while it holds changes: opening and closing a
     * project touches the log without changing anything.
     */
    fun modified(name: String): Long {
        val wal = File(gpkg(name).path + "-wal")
        return maxOf(gpkg(name).lastModified(), if (wal.length() > 0) wal.lastModified() else 0L)
    }

    /** What the project list shows of a project: its layers and features, the features by kind too; null when its file cannot be read. */
    data class Summary(
        val layers: Int,
        val features: Long,
        val bytes: Long,
        val created: String?,
        val points: Long = features, // generic geometry layers count only in the total
        val lines: Long = 0,
        val areas: Long = 0,
    )

    /**
     * Reads project [name] without opening it for editing: its layers, how many
     * features they have, its size and when it was made.
     */
    fun summary(name: String): Summary? = runCatching {
        VectorDataset.open(gpkg(name).path).use { ds ->
            val present = ds.layers.toSet()
            val layers = rows(ds, "SELECT table_name FROM ${ProjectFormat.LAYERS_TABLE}").mapNotNull { it["table_name"] }.filter { it in present }
            val kinds = rows(ds, "SELECT table_name, geometry_type_name FROM gpkg_geometry_columns").associate { it["table_name"] to it["geometry_type_name"].orEmpty() }
            val counts = layers.associateWith { rowCount(ds, it) }
            fun of(kind: String) = counts.filterKeys { kinds[it]?.contains(kind) == true }.values.sum()
            val points = of("POINT")
            val lines = of("LINE")
            val areas = of("POLYGON")
            val features = counts.values.sum()
            val created = rows(ds, "SELECT value FROM ${ProjectFormat.PROJECT_TABLE} WHERE key = 'created'").firstOrNull()?.get("value")
            Summary(layers.size, features, gpkg(name).length() + File(gpkg(name).path + "-wal").length(), created, points, lines, areas)
        }
    }.getOrNull()

    /**
     * Makes project [name] and opens it. It starts empty: the user makes its
     * layers, the first when placing the first point. Each of [templates] gives
     * it a layer, its layer and field names in [language] ("zh" or "en";
     * English for any other).
     */
    fun create(name: String, templates: List<LayerTemplate> = emptyList(), language: String = "en"): Project {
        ProjectFormat.nameProblem(name)?.let { throw IllegalArgumentException(it.message) }
        val folder = File(dir, name)
        require(!folder.exists()) { "there is already a project called $name" }
        root.mkdirs()
        File(root, ".nomedia").createNewFile() // keeps field photos out of the gallery
        check(File(folder, ProjectFormat.ATTACHMENTS).mkdirs()) { "cannot make the folder $folder" }
        try {
            VectorDataset.create(gpkg(name).path).use { ds ->
                for (statement in ProjectFormat.SCHEMA) ds.sql(statement)
                val about = mapOf(
                    "format_version" to ProjectFormat.VERSION.toString(),
                    "id" to UUID.randomUUID().toString(),
                    "name" to name,
                    "created" to Instant.now().toString(),
                )
                for ((key, value) in about) ds.sql("INSERT INTO ${ProjectFormat.PROJECT_TABLE} VALUES (${quote(key)}, ${quote(value)})")
                for (template in templates) addLayer(ds, template.name(language), template.geometryType, template.crs, template.id, template.fields(language))
            }
        } catch (e: Exception) {
            folder.deleteRecursively() // nothing half-made stays behind
            throw e
        }
        return open(name)
    }

    /**
     * Opens project [name] for editing, after checking its file is whole and
     * not from a newer GeoCrew.
     */
    fun open(name: String): Project {
        val file = gpkg(name)
        require(file.isFile) { "there is no project called $name" }
        val ds = VectorDataset.open(file.path, update = true, wal = true)
        try {
            val check = rows(ds, "PRAGMA quick_check").firstOrNull()?.values?.firstOrNull()
            check(check == "ok") { "the project file is damaged: $check" }
            val about = rows(ds, "SELECT key, value FROM ${ProjectFormat.PROJECT_TABLE}").associate { it["key"] to it["value"] }
            val version = about["format_version"]?.toIntOrNull()
            check(version != null) { "$file is not a GeoCrew project" }
            check(version <= ProjectFormat.VERSION) { "the project was saved by a newer GeoCrew (format $version)" }
            val id = about.getValue("id")!!
            return Project(name, File(dir, name), ds, id, readLayers(ds), File(journals, "$id-${pathKey(file)}"))
        } catch (e: Exception) {
            ds.close()
            throw e
        }
    }

    private fun gpkg(name: String) = File(File(dir, name), "$name.gpkg")

    /** Closes [project] and opens it again, as GDAL learns a table's columns when it opens the file. */
    fun reopen(project: Project): Project {
        project.close()
        return open(project.name)
    }

    companion object {
        /** The folder in the GeoCrew folder where exports (and map screenshots) go. */
        const val EXPORTS = "导出"

        /** The folder in the GeoCrew folder where files other apps open with GeoCrew or share to it are put. */
        const val IMPORTS = "导入"
    }

    // A copied project folder keeps the project's id; its path tells the copies'
    // journals apart, so one copy's unsaved edits never turn up in another.
    private fun pathKey(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.canonicalPath.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

}

// [template]'s fields, named in [language].
private fun LayerTemplate.fields(language: String): List<Field> = fields.map { Field(it.name(language), it.type) }

// Makes layer [layer] with [fields], an id for it and each field, and the template it came from, if any.
private fun addLayer(ds: VectorDataset, layer: String, geometry: String, crs: String, template: String?, fields: List<Field>) {
    val described = buildJsonArray {
        for (f in fields) add(buildJsonObject { put("name", f.name); put("type", f.type.name.lowercase()) })
    }
    ds.createLayer(layer, geometry, crs, described.toString())
    val table = quote(layer)
    ds.sql("INSERT INTO ${ProjectFormat.LAYERS_TABLE} VALUES ($table, ${quote(UUID.randomUUID().toString())}, ${template?.let(::quote) ?: "NULL"})")
    for (f in fields) {
        ds.sql("INSERT INTO ${ProjectFormat.FIELDS_TABLE} VALUES ($table, ${quote(f.name)}, ${quote(UUID.randomUUID().toString())})")
    }
}

// The project's layers in their list order (made order where none was set), with geometry type, CRS and display.
private fun readLayers(ds: VectorDataset): List<Project.Layer> {
    val present = ds.layers.toSet()
    val geometry = rows(ds, "SELECT table_name, geometry_type_name FROM gpkg_geometry_columns").associate { it["table_name"] to it["geometry_type_name"] }
    val display = readDisplay(ds)
    // Rows of GeoCrew's layer table keep their number when a layer before them is deleted: colours stay too.
    return rows(ds, "SELECT rowid - 1 AS made, table_name, id, template FROM ${ProjectFormat.LAYERS_TABLE} ORDER BY rowid")
        .filter { it["table_name"] in present }
        .map {
            val made = it["made"]?.toIntOrNull() ?: 0
            val name = it.getValue("table_name")!!
            val srs = (Json.parseToJsonElement(ds.layerInfo(name)).jsonObject["srs"] as? JsonPrimitive)?.contentOrNull
            val id = it.getValue("id")!!
            Project.Layer(name, id, it["template"], geometry[name], srs, display[id] ?: Project.Display(), made)
        }
        .sortedWith(compareBy({ it.display.sort ?: Int.MAX_VALUE }, { it.made }))
}

// Each layer's and basemap's display by id; nothing for a project whose table was never made.
private fun readDisplay(ds: VectorDataset): Map<String, Project.Display> {
    val made = rows(ds, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '${ProjectFormat.DISPLAY_TABLE}'").isNotEmpty()
    if (!made) return emptyMap()
    return rows(ds, "SELECT layer_id, sort, visible, opacity FROM ${ProjectFormat.DISPLAY_TABLE}").associate {
        it.getValue("layer_id")!! to Project.Display(it["sort"]?.toIntOrNull(), it["visible"] != "0", it["opacity"]?.toDoubleOrNull() ?: 1.0)
    }
}

// What a layer's style may say (ADR 0006): one symbol, or a colour for each value of a field.
private val STYLE_KEYS = setOf("color", "size", "width", "fill", "field", "classes", "ranges", "label")

// The field types a graduated style can range over.
private val NUMBER_TYPES = setOf("integer", "integer64", "real")

/** How a raster may be drawn: as it is, its hillshade, or coloured by height (for single-band rasters, such as DEMs). */
val RENDERS = listOf("default", "hillshade", "relief")
const val DEFAULT_RENDER = "default"

// The most values a categorized style may colour.
private const val MAX_CLASSES = 100

// A style's colour: #rrggbb, or #rrggbbaa with its opacity.
private val STYLE_COLOR = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")

// The rows of a query, as column name to text.
private fun rows(ds: VectorDataset, sql: String): List<Map<String, String?>> =
    Json.parseToJsonElement(ds.sql(sql)).jsonArray.map { row ->
        row.jsonObject.getValue("properties").jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
    }

/**
 * How many features [layer] has, from its rows. The count a GeoPackage keeps
 * for itself is left behind when Android stops the app without the file being
 * closed, as it does without warning.
 */
internal fun rowCount(ds: VectorDataset, layer: String): Long {
    val row = Json.parseToJsonElement(ds.sql("SELECT COUNT(*) AS n FROM ${ProjectFormat.identifier(layer)}")).jsonArray.firstOrNull()
    return (row?.jsonObject?.get("properties")?.jsonObject?.get("n") as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
}

/**
 * An open project: its [layers] and where its attachments go. Close it when done;
 * its layers are edited through [target].
 */
class Project internal constructor(
    val name: String,
    val folder: File,
    private val dataset: VectorDataset,
    val id: String,
    layers: List<Layer>,
    private val journals: File,
) : AutoCloseable {
    /**
     * A layer of the project: its table [name], stable [id], the template it was
     * made from, its [geometry] type (such as POINT), its CRS (such as EPSG:4490),
     * how it shows on the map, and when it was [made] among the layers (0 first).
     */
    data class Layer(
        val name: String,
        val id: String,
        val template: String?,
        val geometry: String?,
        val srs: String?,
        val display: Display = Display(),
        val made: Int = 0,
    )

    /** Each layer's style (ADR 0006) by layer id, for those that have one. */
    val styles: Map<String, JsonObject>
        get() {
            val made = rows(dataset, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '${ProjectFormat.STYLE_TABLE}'").isNotEmpty()
            if (!made) return emptyMap()
            return rows(dataset, "SELECT layer_id, style FROM ${ProjectFormat.STYLE_TABLE}").mapNotNull { row ->
                val id = row["layer_id"] ?: return@mapNotNull null
                val style = runCatching { Json.parseToJsonElement(row["style"].orEmpty()).jsonObject }.getOrNull() ?: return@mapNotNull null
                id to style
            }.toMap()
        }

    /**
     * Sets how layer [layer] is drawn: one colour (#rrggbb, or #rrggbbaa with
     * its opacity), a point's [size]
     * and a line's width in dp, an area's fill opacity; what is left out takes
     * its default. With a "field" of the layer and "classes" ([{"value",
     * "color"}], each value once, at most 100) features are coloured by that
     * field's value, the rest in the colour; with a number field and "ranges"
     * ([{"from", "to", "color"}], at most 100) by the range the value falls
     * in. "label" names the field whose
     * values label the features, empty for none; without it the name field
     * labels them. An empty [style] goes back to the default style. Saved at
     * once, not undone, like the display.
     */
    fun setStyle(layer: String, style: JsonObject) {
        val id = layers.firstOrNull { it.name == layer }?.id ?: throw IllegalArgumentException("there is no layer $layer")
        val unknown = style.keys - STYLE_KEYS
        require(unknown.isEmpty()) { "a style has no ${unknown.joinToString()}" }
        fun number(key: String, range: ClosedFloatingPointRange<Double>): Double? {
            val value = style[key] ?: return null
            val n = (value as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toDoubleOrNull()
            require(n != null && n in range) { "$key goes from ${range.start} to ${range.endInclusive}" }
            return n
        }
        val color = style["color"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        require(style["color"] == null || (color != null && STYLE_COLOR.matches(color))) { "color is #rrggbb or #rrggbbaa" }
        val size = number("size", 1.0..30.0)
        val width = number("width", 0.5..20.0)
        val fill = number("fill", 0.0..1.0)
        val field = style["field"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        require(style["field"] == null || field != null) { "field is a field's name" }
        require((field == null) == (style["classes"] == null && style["ranges"] == null)) { "a field goes with classes or ranges" }
        require(style["classes"] == null || style["ranges"] == null) { "a style has classes or ranges, not both" }
        val ranges = style["ranges"]?.let { r ->
            val listed = (r as? JsonArray)?.map { it as? JsonObject } ?: throw IllegalArgumentException("ranges is a list")
            require(listed.size <= MAX_CLASSES) { "a style has at most $MAX_CLASSES ranges" }
            listed.map { o ->
                fun bound(key: String) = (o?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toDoubleOrNull()
                val from = bound("from")
                val to = bound("to")
                val shown = (o?.get("color") as? JsonPrimitive)?.contentOrNull
                require(o != null && o.keys == setOf("from", "to", "color") && from != null && to != null && from <= to && shown != null && STYLE_COLOR.matches(shown)) {
                    "each range is {\"from\", \"to\", \"color\"}, from no more than to"
                }
                Triple(from, to, shown.lowercase())
            }
        }
        val classes = style["classes"]?.let { c ->
            val listed = (c as? JsonArray)?.map { it as? JsonObject } ?: throw IllegalArgumentException("classes is a list")
            require(listed.size <= MAX_CLASSES) { "a style colours at most $MAX_CLASSES values" }
            listed.map { o ->
                val value = (o?.get("value") as? JsonPrimitive)?.contentOrNull
                val shown = (o?.get("color") as? JsonPrimitive)?.contentOrNull
                require(o != null && o.keys == setOf("value", "color") && value != null && shown != null && STYLE_COLOR.matches(shown)) {
                    "each class is {\"value\", \"color\": #rrggbb or #rrggbbaa}"
                }
                value to shown.lowercase()
            }.also { pairs -> require(pairs.map { it.first }.toSet().size == pairs.size) { "each value has one class" } }
        }
        val label = style["label"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        require(style["label"] == null || label != null) { "label is a field's name, or empty for none" }
        if (field != null || !label.isNullOrEmpty()) {
            val types = Json.parseToJsonElement(dataset.layerInfo(layer)).jsonObject["fields"]?.jsonArray.orEmpty().mapNotNull {
                val name = (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                name to (it.jsonObject["type"] as? JsonPrimitive)?.contentOrNull
            }.toMap()
            require(field == null || field in types) { "$layer has no field $field" }
            require(ranges == null || types[field] in NUMBER_TYPES) { "$field is not a number field" }
            require(label.isNullOrEmpty() || label in types) { "$layer has no field $label" }
        }
        dataset.sql(ProjectFormat.STYLE_SCHEMA)
        if (style.isEmpty()) {
            dataset.sql("DELETE FROM ${ProjectFormat.STYLE_TABLE} WHERE layer_id = ${quote(id)}")
            return
        }
        val stored = buildJsonObject {
            put("kind", if (field == null) "single" else if (ranges != null) "graduated" else "categorized")
            color?.let { put("color", it.lowercase()) }
            size?.let { put("size", it) }
            width?.let { put("width", it) }
            fill?.let { put("fill", it) }
            if (field != null) {
                put("field", field)
                if (ranges != null) {
                    putJsonArray("ranges") {
                        for ((from, to, shown) in ranges) addJsonObject { put("from", from); put("to", to); put("color", shown) }
                    }
                } else {
                    putJsonArray("classes") {
                        for ((value, shown) in classes.orEmpty()) addJsonObject { put("value", value); put("color", shown) }
                    }
                }
            }
            label?.let { put("label", it) }
        }
        dataset.sql("INSERT OR REPLACE INTO ${ProjectFormat.STYLE_TABLE} (layer_id, style) VALUES (${quote(id)}, ${quote(stored.toString())})")
    }

    /**
     * A raster under the layers: its stable [id], its [name] in the project,
     * the [file] it shows, what the probe made of it ([info], see Probe), how
     * it shows, and when it was [added].
     */
    data class Raster(
        val id: String,
        val name: String,
        val file: File,
        val info: JsonObject,
        val display: Display,
        val added: String,
        /** How a single-band raster is drawn: one of [RENDERS]. */
        val render: String = DEFAULT_RENDER,
    )

    /** The rasters, newest on top: the first is drawn over the others. */
    val rasters: List<Raster>
        get() {
            val made = rows(dataset, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '${ProjectFormat.RASTERS_TABLE}'").isNotEmpty()
            if (!made) return emptyList()
            val display = readDisplay(dataset)
            val looks = styles
            return rows(dataset, "SELECT id, name, path, added, info FROM ${ProjectFormat.RASTERS_TABLE} ORDER BY rowid DESC").mapNotNull { row ->
                val id = row["id"] ?: return@mapNotNull null
                val path = row["path"] ?: return@mapNotNull null
                val info = runCatching { Json.parseToJsonElement(row["info"].orEmpty()).jsonObject }.getOrNull() ?: return@mapNotNull null
                val file = File(path).let { if (it.isAbsolute) it else File(folder, path) }
                val render = (looks[id]?.get("render") as? JsonPrimitive)?.contentOrNull?.takeIf { it in RENDERS } ?: DEFAULT_RENDER
                Raster(id, row["name"].orEmpty(), file, info, display[id] ?: Display(), row["added"].orEmpty(), render)
            }
        }

    /**
     * Adds raster [file] under the layers, as [name] or else the file's own
     * name (numbered when taken), with what the probe made of it ([info]).
     * The file stays where it is; one in the project's folder is kept by its
     * path from there, so the folder can move. Saved at once.
     */
    fun addRaster(file: File, info: JsonObject, name: String? = null): Raster {
        val taken = rasters.map { it.name }
        val chosen = if (name != null) {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME) { "a raster name has 1 to ${ProjectFormat.MAX_NAME} characters" }
            require(taken.none { it.equals(trimmed, ignoreCase = true) }) { "the project already has a raster $trimmed" }
            trimmed
        } else {
            val base = file.nameWithoutExtension.take(ProjectFormat.MAX_NAME - 4).ifBlank { "raster" }
            generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { n -> taken.none { it.equals(n, ignoreCase = true) } }
        }
        val inside = file.canonicalFile.relativeToOrNull(folder.canonicalFile)?.takeUnless { it.path.startsWith("..") }
        val id = UUID.randomUUID().toString()
        dataset.sql(ProjectFormat.RASTERS_SCHEMA)
        dataset.sql(
            "INSERT INTO ${ProjectFormat.RASTERS_TABLE} (id, name, path, added, info) VALUES " +
                "(${quote(id)}, ${quote(chosen)}, ${quote(inside?.path ?: file.canonicalPath)}, ${quote(Instant.now().toString())}, ${quote(info.toString())})",
        )
        return rasters.single { it.id == id }
    }

    /** Takes raster [name] out of the project; its file stays. */
    fun removeRaster(name: String) {
        val found = rasters.firstOrNull { it.name == name } ?: throw IllegalArgumentException("there is no raster $name")
        dataset.sql("DELETE FROM ${ProjectFormat.RASTERS_TABLE} WHERE id = ${quote(found.id)}")
        dataset.sql(ProjectFormat.DISPLAY_SCHEMA)
        dataset.sql("DELETE FROM ${ProjectFormat.DISPLAY_TABLE} WHERE layer_id = ${quote(found.id)}")
        dataset.sql(ProjectFormat.STYLE_SCHEMA)
        dataset.sql("DELETE FROM ${ProjectFormat.STYLE_TABLE} WHERE layer_id = ${quote(found.id)}")
    }

    /**
     * Draws raster [name] as [render] (one of [RENDERS]): as it is, a
     * single band in grey; its hillshade; or coloured by height. Kept in the
     * style table under the raster's id; saved at once, not undone.
     */
    fun setRasterRender(name: String, render: String) {
        val found = rasters.firstOrNull { it.name == name } ?: throw IllegalArgumentException("there is no raster $name")
        require(render in RENDERS) { "render is one of ${RENDERS.joinToString()}" }
        dataset.sql(ProjectFormat.STYLE_SCHEMA)
        if (render == DEFAULT_RENDER) {
            dataset.sql("DELETE FROM ${ProjectFormat.STYLE_TABLE} WHERE layer_id = ${quote(found.id)}")
        } else {
            val style = buildJsonObject { put("kind", "raster"); put("render", render) }
            dataset.sql("INSERT OR REPLACE INTO ${ProjectFormat.STYLE_TABLE} (layer_id, style) VALUES (${quote(found.id)}, ${quote(style.toString())})")
        }
    }

    /** A saved map view: where the map looked, how far in (zoom) and how turned (bearing, degrees). */
    data class Bookmark(val id: Long, val name: String, val longitude: Double, val latitude: Double, val zoom: Double, val bearing: Double)

    /** The saved map views, oldest first. */
    val bookmarks: List<Bookmark>
        get() {
            val made = rows(dataset, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '${ProjectFormat.BOOKMARKS_TABLE}'").isNotEmpty()
            if (!made) return emptyList()
            // The id under another name: GDAL takes an integer primary key, even renamed, for the feature id and leaves it out.
            return rows(dataset, "SELECT CAST(id AS TEXT) AS bookmark, name, longitude, latitude, zoom, bearing FROM ${ProjectFormat.BOOKMARKS_TABLE} ORDER BY id").map {
                Bookmark(
                    it.getValue("bookmark")!!.toLong(), it.getValue("name").orEmpty(),
                    it.getValue("longitude")!!.toDouble(), it.getValue("latitude")!!.toDouble(),
                    it.getValue("zoom")!!.toDouble(), it["bearing"]?.toDoubleOrNull() ?: 0.0,
                )
            }
        }

    /** Saves a map view as [name]; saved in the project at once, not undone. */
    fun addBookmark(name: String, longitude: Double, latitude: Double, zoom: Double, bearing: Double): Bookmark {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a bookmark needs a name" }
        require(longitude in -180.0..180.0 && latitude in -90.0..90.0) { "longitude and latitude in degrees" }
        dataset.sql(ProjectFormat.BOOKMARKS_SCHEMA)
        dataset.sql(
            "INSERT INTO ${ProjectFormat.BOOKMARKS_TABLE} (name, longitude, latitude, zoom, bearing, created) VALUES " +
                "(${quote(trimmed)}, $longitude, $latitude, $zoom, $bearing, ${quote(java.time.Instant.now().toString())})",
        )
        return bookmarks.last()
    }

    /** Deletes the bookmark [id]; saved at once, not undone. */
    fun deleteBookmark(id: Long) {
        require(bookmarks.any { it.id == id }) { "there is no bookmark $id" }
        dataset.sql("DELETE FROM ${ProjectFormat.BOOKMARKS_TABLE} WHERE id = $id")
    }

    /** How a layer or basemap shows: its place in the list when set, whether it shows, and its opacity from 0 to 1. */
    data class Display(val sort: Int? = null, val visible: Boolean = true, val opacity: Double = 1.0)

    /** The basemaps a project can show, under the layers: imagery, and its annotation above it. */
    val basemaps: Map<String, Display>
        get() {
            val display = readDisplay(dataset)
            return BASEMAPS.associateWith { display["basemap:$it"] ?: Display() }
        }

    /**
     * Shows or hides layer [layer] or basemap [basemap] (one of [BASEMAPS]) and sets
     * its opacity; saved in the project at once. Not part of editing: it is not
     * undone and needs no save.
     */
    fun setDisplay(layer: String? = null, basemap: String? = null, visible: Boolean? = null, opacity: Double? = null, raster: String? = null) {
        val id = when {
            layer != null -> layers.firstOrNull { it.name == layer }?.id ?: throw IllegalArgumentException("there is no layer $layer")
            basemap != null -> basemap.takeIf { it in BASEMAPS }?.let { "basemap:$it" } ?: throw IllegalArgumentException("there is no basemap $basemap")
            raster != null -> rasters.firstOrNull { it.name == raster }?.id ?: throw IllegalArgumentException("there is no raster $raster")
            else -> throw IllegalArgumentException("a layer, a basemap or a raster")
        }
        require(opacity == null || opacity in 0.0..1.0) { "opacity goes from 0 to 1" }
        dataset.sql(ProjectFormat.DISPLAY_SCHEMA)
        dataset.sql("INSERT OR IGNORE INTO ${ProjectFormat.DISPLAY_TABLE} (layer_id) VALUES (${quote(id)})")
        if (visible != null) dataset.sql("UPDATE ${ProjectFormat.DISPLAY_TABLE} SET visible = ${if (visible) 1 else 0} WHERE layer_id = ${quote(id)}")
        if (opacity != null) dataset.sql("UPDATE ${ProjectFormat.DISPLAY_TABLE} SET opacity = $opacity WHERE layer_id = ${quote(id)}")
        layers = readLayers(dataset)
    }

    /** Puts the layers in the order of [names], every layer once, the first drawn on top; saved at once. */
    fun setOrder(names: List<String>) {
        require(names.size == layers.size && names.toSet() == layers.map { it.name }.toSet()) { "name every layer once" }
        dataset.sql(ProjectFormat.DISPLAY_SCHEMA)
        for ((index, name) in names.withIndex()) {
            val id = quote(layers.single { it.name == name }.id)
            dataset.sql("INSERT OR IGNORE INTO ${ProjectFormat.DISPLAY_TABLE} (layer_id) VALUES ($id)")
            dataset.sql("UPDATE ${ProjectFormat.DISPLAY_TABLE} SET sort = $index WHERE layer_id = $id")
        }
        layers = readLayers(dataset)
    }

    /** The layers, in the order they were made. */
    var layers: List<Layer> = layers
        private set

    val attachments: File get() = File(folder, ProjectFormat.ATTACHMENTS)

    /** The project's GeoPackage. */
    val file: File get() = File(folder, "$name.gpkg")

    /**
     * Moves what is in the write-ahead log into the GeoPackage itself, so a copy
     * of the file has every saved change (ADR 0004: before exporting or sharing).
     */
    fun checkpoint() {
        // One row: busy, the frames in the log, the frames moved; another reader can stop it part-way.
        val row = Json.parseToJsonElement(dataset.sql("PRAGMA wal_checkpoint(TRUNCATE)")).jsonArray.single()
            .jsonObject.getValue("properties").jsonObject.values.map { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
        check(row.size == 3 && row[0] == 0L && row[1] == row[2]) { "the project is busy; try again" }
    }

    /**
     * Adds field [name] of [type] to [layer] (ADR 0004: it gets a stable id). GDAL
     * sees it once the project is opened again, see [Projects.reopen].
     */
    fun addField(layer: String, name: String, type: FieldType) {
        require(layers.any { it.name == layer }) { "there is no layer $layer" }
        val column = when (type) {
            FieldType.STRING -> "TEXT"
            FieldType.REAL -> "REAL"
            FieldType.INTEGER, FieldType.INTEGER64 -> "INTEGER"
            FieldType.DATE -> "DATE"
            FieldType.DATETIME -> "DATETIME"
            FieldType.BOOLEAN -> "BOOLEAN"
            FieldType.OTHER -> throw IllegalArgumentException("a field cannot be of type other")
        }
        dataset.sql("ALTER TABLE ${ProjectFormat.identifier(layer)} ADD COLUMN ${ProjectFormat.identifier(name)} $column")
        dataset.sql("INSERT INTO ${ProjectFormat.FIELDS_TABLE} VALUES (${quote(layer)}, ${quote(name)}, ${quote(UUID.randomUUID().toString())})")
    }

    /** The names of [layer]'s fields as GDAL reads them, its fid and geometry aside. */
    fun fieldNames(layer: String): List<String> {
        require(layers.any { it.name == layer }) { "there is no layer $layer" }
        return VectorLayerStore(dataset, layer).fields.map { it.name }
    }

    /** Throws unless field [field] of [layer] can be named [to]: a name of its own in the layer, in any case, that fits. */
    fun checkFieldRename(layer: String, field: String, to: String) {
        val names = fieldNames(layer)
        require(field in names) { "$layer has no field $field" }
        require(to != field) { "the field is already called $to" }
        require(to.isNotEmpty() && to == to.trim() && to.length <= ProjectFormat.MAX_NAME) {
            "a field name has 1 to ${ProjectFormat.MAX_NAME} characters, without spaces at either end"
        }
        require((names - field + Workspace.RESERVED).none { it.equals(to, ignoreCase = true) }) { "the layer already has a field $to" }
    }

    /**
     * Renames field [field] of [layer] to [to] in the file (GDAL keeps
     * GeoPackage's own tables in step) and in GeoCrew's: its values and its
     * stable id stay (ADR 0004), and the layer's style and label follow it
     * (ADR 0006). Open the project again afterwards, see [Projects.reopen].
     */
    fun renameField(layer: String, field: String, to: String) {
        checkFieldRename(layer, field, to)
        val id = layers.single { it.name == layer }.id
        val table = ProjectFormat.identifier(layer)
        changing {
            // SQLite takes column names in any case as the same, so a change of case goes by way of another name.
            val via = if (to.equals(field, ignoreCase = true)) "geocrew_" + UUID.randomUUID().toString().replace("-", "") else field
            if (via != field) dataset.sql("ALTER TABLE $table RENAME COLUMN ${ProjectFormat.identifier(field)} TO ${ProjectFormat.identifier(via)}")
            dataset.sql("ALTER TABLE $table RENAME COLUMN ${ProjectFormat.identifier(via)} TO ${ProjectFormat.identifier(to)}")
            dataset.sql("UPDATE ${ProjectFormat.FIELDS_TABLE} SET column_name = ${quote(to)} WHERE table_name = ${quote(layer)} AND column_name = ${quote(field)}")
            restyle(id, field, to)
        }
    }

    /** Throws unless [layer] has field [field] to delete. */
    fun checkFieldDelete(layer: String, field: String) {
        require(field in fieldNames(layer)) { "$layer has no field $field" }
    }

    /**
     * Deletes field [field] of [layer], every value in it with it, from the
     * file and from GeoCrew's tables; the layer's style no longer colours by
     * it nor labels with it (ADR 0006). It cannot be undone. Open the project
     * again afterwards, see [Projects.reopen].
     */
    fun deleteField(layer: String, field: String) {
        checkFieldDelete(layer, field)
        val id = layers.single { it.name == layer }.id
        changing {
            dataset.sql("ALTER TABLE ${ProjectFormat.identifier(layer)} DROP COLUMN ${ProjectFormat.identifier(field)}")
            dataset.sql("DELETE FROM ${ProjectFormat.FIELDS_TABLE} WHERE table_name = ${quote(layer)} AND column_name = ${quote(field)}")
            restyle(id, field, null)
        }
    }

    // [change] all or nothing: a column renamed or dropped without GeoCrew's rows would leave them pointing nowhere.
    private fun changing(change: () -> Unit) {
        dataset.begin()
        try {
            change()
            dataset.commit()
        } catch (e: Exception) {
            runCatching { dataset.rollback() }
            throw e
        }
    }

    // Layer [id]'s style with field [from] called [to] instead; with [to] null, no longer drawn by it (one colour, as
    // before it was) nor labelled with it (as by default). A style that uses another field is left as it is.
    private fun restyle(id: String, from: String, to: String?) {
        val style = styles[id] ?: return
        val drawnBy = (style["field"] as? JsonPrimitive)?.contentOrNull == from
        val labelled = (style["label"] as? JsonPrimitive)?.contentOrNull == from
        if (!drawnBy && !labelled) return
        val changed = buildJsonObject {
            for ((key, value) in style) {
                val ours = (key == "field" && drawnBy) || (key == "label" && labelled)
                when {
                    ours && to != null -> put(key, to)
                    ours -> Unit
                    to == null && drawnBy && key in setOf("classes", "ranges") -> Unit
                    to == null && drawnBy && key == "kind" -> put(key, "single")
                    else -> put(key, value)
                }
            }
        }
        dataset.sql("UPDATE ${ProjectFormat.STYLE_TABLE} SET style = ${quote(changed.toString())} WHERE layer_id = ${quote(id)}")
    }

    /**
     * Makes layer [name] from [template], its fields named in [language], in CRS
     * [srs] (the template's when null), in the open file: layers being edited
     * carry on, unsaved edits and all.
     */
    fun createLayer(name: String, template: LayerTemplate, language: String, srs: String? = null): Layer {
        checkNewName(name)
        if (srs != null) Crs.describe(srs) // a CRS GDAL does not know is refused before anything is made
        addLayer(dataset, name, template.geometryType, srs ?: template.crs, template.id, template.fields(language))
        layers = readLayers(dataset)
        putOnTop(name)
        return layers.single { it.name == name }
    }

    /**
     * A name for a layer brought in as [wanted]: as it is when it can name a
     * layer, else cut to length, with GeoPackage's and GeoCrew's own prefixes
     * kept off, and numbered when taken (路线 2, 路线 3, ...).
     */
    fun freeLayerName(wanted: String): String {
        var base = wanted.trim().ifEmpty { "layer" }.take(ProjectFormat.MAX_NAME - 4).trim()
        if (RESERVED_PREFIXES.any { base.startsWith(it, ignoreCase = true) }) base = "_$base"
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { n -> dataset.layers.none { it.equals(n, ignoreCase = true) } }
    }

    /**
     * Copies layer [sourceLayer] of [source] into the project as new layer
     * [name] (ADR 0002's import): its geometry type, its CRS, the fields
     * GeoCrew can edit, and every feature, in one transaction. Like a new
     * layer it is saved at once and not undone; a copy that fails leaves no
     * layer behind. Refused for a layer that does not say its CRS.
     */
    fun importLayer(source: VectorDataset, sourceLayer: String, name: String): Layer {
        checkNewName(name)
        val info = Json.parseToJsonElement(source.layerInfo(sourceLayer)).jsonObject
        val srs = (info["srs"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw NoCrs("$sourceLayer does not say its CRS")
        val declared = (info["geometryType"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "GEOMETRY"
        // Fields GeoCrew cannot edit (binary, lists) stay behind; so does a second field of the same name in another case.
        val fields = info.getValue("fields").jsonArray.map { f ->
            Field(f.jsonObject.getValue("name").jsonPrimitive.content, FieldType.of(f.jsonObject.getValue("type").jsonPrimitive.content))
        }.filter { it.type != FieldType.OTHER && it.name.isNotBlank() }.distinctBy { it.name.lowercase() }
        val kept = fields.map { it.name }.toSet()
        val features = Json.parseToJsonElement(source.features(sourceLayer)).jsonArray
        // A Shapefile calls a layer of multi-part lines or areas single-part: such a layer comes in as multi-part.
        val multi = features.any { ((it.jsonObject["geometry"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull?.startsWith("Multi") == true }
        val geometry = if (multi && !declared.startsWith("MULTI") && !declared.startsWith("GEOMETRY")) "MULTI$declared" else declared
        addLayer(dataset, name, geometry, srs, null, fields)
        try {
            dataset.begin()
            for (f in features) {
                val feature = f.jsonObject
                val properties = (feature["properties"] as? JsonObject).orEmpty().filterKeys { it in kept }
                dataset.add(name, buildJsonObject { put("geometry", feature["geometry"] ?: JsonNull); put("properties", JsonObject(properties)) }.toString())
            }
            dataset.commit()
        } catch (e: Exception) {
            runCatching { dataset.rollback() }
            layers = readLayers(dataset)
            runCatching { deleteLayer(name) }
            throw e
        }
        layers = readLayers(dataset)
        putOnTop(name)
        return layers.single { it.name == name }
    }

    /**
     * Makes layer [name] like layer [like]: its fields, geometry type, CRS and
     * template, and no features. A feature moved between the two keeps every value.
     */
    fun createLayerLike(name: String, like: String): Layer {
        checkNewName(name)
        val source = layers.firstOrNull { it.name == like } ?: throw IllegalArgumentException("there is no layer $like")
        val fields = VectorLayerStore(dataset, like).fields.filter { it.type != FieldType.OTHER } // fields GeoCrew cannot edit stay behind
        addLayer(dataset, name, source.geometry ?: "POINT", source.srs ?: "EPSG:4490", source.template, fields)
        layers = readLayers(dataset)
        // Its style too, so a feature moved across keeps its look.
        styles[source.id]?.let { style ->
            val made = layers.single { it.name == name }.id
            dataset.sql("INSERT OR REPLACE INTO ${ProjectFormat.STYLE_TABLE} (layer_id, style) VALUES (${quote(made)}, ${quote(style.toString())})")
        }
        putOnTop(name)
        return layers.single { it.name == name }
    }

    /**
     * Renames layer [layer] to [to] in the file (GDAL keeps GeoPackage's own tables
     * in step) and in GeoCrew's; its id, fields' ids and display stay. Open the
     * project again afterwards, see [Projects.reopen].
     */
    fun renameLayer(layer: String, to: String) {
        require(layers.any { it.name == layer }) { "there is no layer $layer" }
        checkNewName(to, besides = layer)
        // All or nothing: a table renamed without GeoCrew's rows would drop out of the project.
        dataset.begin()
        try {
            // SQLite takes table names in any case as the same, so a change of case goes by way of another name.
            val via = if (to.equals(layer, ignoreCase = true)) "geocrew_renaming" else layer
            if (via != layer) dataset.sql("ALTER TABLE ${ProjectFormat.identifier(layer)} RENAME TO ${ProjectFormat.identifier(via)}")
            dataset.sql("ALTER TABLE ${ProjectFormat.identifier(via)} RENAME TO ${ProjectFormat.identifier(to)}")
            dataset.sql("UPDATE ${ProjectFormat.LAYERS_TABLE} SET table_name = ${quote(to)} WHERE table_name = ${quote(layer)}")
            dataset.sql("UPDATE ${ProjectFormat.FIELDS_TABLE} SET table_name = ${quote(to)} WHERE table_name = ${quote(layer)}")
            dataset.commit()
        } catch (e: Exception) {
            dataset.rollback()
            throw e
        }
    }

    /**
     * Deletes layer [layer] with all its features, from the file and from
     * GeoCrew's tables, and its journal. It cannot be undone. Open the project
     * again afterwards, see [Projects.reopen].
     */
    fun deleteLayer(layer: String) {
        val found = layers.firstOrNull { it.name == layer } ?: throw IllegalArgumentException("there is no layer $layer")
        dataset.sql("DROP TABLE ${ProjectFormat.identifier(layer)}")
        dataset.sql("DELETE FROM ${ProjectFormat.FIELDS_TABLE} WHERE table_name = ${quote(layer)}")
        dataset.sql("DELETE FROM ${ProjectFormat.LAYERS_TABLE} WHERE table_name = ${quote(layer)}")
        dataset.sql(ProjectFormat.DISPLAY_SCHEMA)
        dataset.sql("DELETE FROM ${ProjectFormat.DISPLAY_TABLE} WHERE layer_id = ${quote(found.id)}")
        dataset.sql(ProjectFormat.STYLE_SCHEMA)
        dataset.sql("DELETE FROM ${ProjectFormat.STYLE_TABLE} WHERE layer_id = ${quote(found.id)}")
        File(journals, "${found.id}.jsonl").delete()
    }

    // A new layer goes on top of a list that has been put in order, as in QGIS; an unordered list keeps the order layers were made.
    private fun putOnTop(name: String) {
        if (layers.none { it.display.sort != null }) return
        setOrder(listOf(name) + layers.map { it.name }.filter { it != name })
    }

    // [besides]: a layer being renamed, whose own name, in any case, does not count as taken.
    private fun checkNewName(name: String, besides: String? = null) {
        require(name.isNotBlank() && name == name.trim() && name.length <= ProjectFormat.MAX_NAME) {
            "a layer name has 1 to ${ProjectFormat.MAX_NAME} characters, without spaces at either end"
        }
        require(RESERVED_PREFIXES.none { name.startsWith(it, ignoreCase = true) }) { "a layer name cannot start with ${RESERVED_PREFIXES.joinToString()}" }
        require(dataset.layers.none { it.equals(name, ignoreCase = true) && it != besides }) { "the project already has a layer $name" }
    }

    /** How many features [layer] has in its file. */
    fun count(layer: String): Long = rowCount(dataset, layer)

    /** What editing [layer] needs: its store, and its journal in the app's own files, named by the layer's id. */
    fun target(layer: String): EditTarget {
        val found = layers.firstOrNull { it.name == layer } ?: throw IllegalArgumentException("there is no layer $layer")
        journals.mkdirs()
        return EditTarget(VectorLayerStore(dataset, found.name), FileJournal(File(journals, "${found.id}.jsonl")), found.srs)
    }

    override fun close() = dataset.close()

    companion object {
        // Tables of GeoPackage, its spatial indexes, SQLite and GeoCrew itself.
        val RESERVED_PREFIXES = listOf("gpkg_", "rtree_", "sqlite_", "geocrew_")

        /** Tianditu imagery and its annotation (ADR 0015). */
        val BASEMAPS = listOf("img", "cia")
    }
}

/** A layer brought into a project that does not say its CRS: there is no knowing where it is. */
class NoCrs(message: String) : IllegalArgumentException(message)
