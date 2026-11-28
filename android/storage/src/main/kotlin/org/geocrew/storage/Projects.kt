package org.geocrew.storage

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import org.geocrew.core.edit.ValueRange
import org.geocrew.core.layers.LayerTree
import org.geocrew.core.layers.Place
import org.geocrew.core.layers.TreeNode
import org.geocrew.core.project.LayerTemplate
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.quote
import org.geocrew.core.project.Templates
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
        return open(name, snapshot = false) // an empty project needs no snapshot yet
    }

    /**
     * Opens project [name] for editing, after checking its file is whole and
     * not from a newer GeoCrew. With [snapshot], the first opening of a day
     * makes the day's snapshot ([Project.snapshot]); a snapshot that cannot be
     * made does not stop the project opening, and [Project.snapshotFailure] says why.
     */
    fun open(name: String, snapshot: Boolean = true): Project {
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
            // Before GDAL reads the counts, so it goes on from the right ones.
            fixFeatureCounts(ds, rows(ds, "SELECT table_name FROM ${ProjectFormat.LAYERS_TABLE}").mapNotNull { it["table_name"] })
            val id = about.getValue("id")!!
            val project = Project(name, File(dir, name), ds, id, readLayers(ds), File(journals, "$id-${pathKey(file)}"))
            if (snapshot) project.snapshotFailure = runCatching { project.snapshot() }.exceptionOrNull()?.let { it.message ?: it.toString() }
            return project
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

    /**
     * Puts [project] back as it was in its snapshot [day] (ADR 0004): the file
     * as it is goes first into the snapshot folder as 恢复前-<time>.gpkg (kept
     * out of the seven, to delete by hand), then the snapshot is copied in its
     * place and the project opened again. Attachments stay: edits never change
     * or delete their files. Returns the project opened again, and the copy.
     */
    fun restore(project: Project, day: String): Pair<Project, File> {
        val snapshot = File(project.snapshots, "$day.gpkg")
        require(SNAPSHOT_NAME.matches(snapshot.name) && snapshot.isFile) { "there is no snapshot $day" }
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val kept = File(project.snapshots, "$RESTORED_PREFIX$stamp.gpkg")
        project.keepCopy(kept)
        val file = project.file
        val name = project.name
        project.close()
        val part = File(file.parentFile, ".${file.name}.restoring")
        try {
            snapshot.copyTo(part, overwrite = true)
            // The log of the file being replaced goes with it.
            for (suffix in listOf("-wal", "-shm", "-journal")) File(file.path + suffix).delete()
            check(part.renameTo(file) || (file.delete() && part.renameTo(file))) { "cannot put the snapshot in place of $file" }
        } finally {
            part.delete()
        }
        return open(name, snapshot = false) to kept
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

// The project's layers in their list order (made order where none was set; a group's layers together where the group
// is, ADR 0017), with geometry type, CRS and display.
private fun readLayers(ds: VectorDataset): List<Project.Layer> {
    val present = ds.layers.toSet()
    val geometry = rows(ds, "SELECT table_name, geometry_type_name FROM gpkg_geometry_columns").associate { it["table_name"] to it["geometry_type_name"] }
    val display = readDisplay(ds)
    // Rows of GeoCrew's layer table keep their number when a layer before them is deleted: colours stay too.
    val layers = rows(ds, "SELECT rowid - 1 AS made, table_name, id, template FROM ${ProjectFormat.LAYERS_TABLE} ORDER BY rowid")
        .filter { it["table_name"] in present }
        .map {
            val made = it["made"]?.toIntOrNull() ?: 0
            val name = it.getValue("table_name")!!
            val srs = (Json.parseToJsonElement(ds.layerInfo(name)).jsonObject["srs"] as? JsonPrimitive)?.contentOrNull
            val id = it.getValue("id")!!
            Project.Layer(name, id, it["template"], geometry[name], srs, display[id] ?: Project.Display(), made)
        }
    val order = LayerTree.drawOrder(treeNodes(layers, readGroups(ds, display))).withIndex().associate { (i, id) -> id to i }
    return layers.sortedBy { order[it.id] ?: Int.MAX_VALUE }
}

// The layer list's groups, in no order (the tree puts them in place); none for a project that never had one.
private fun readGroups(ds: VectorDataset, display: Map<String, Project.Display> = readDisplay(ds)): List<Project.Group> {
    val made = rows(ds, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '${ProjectFormat.GROUPS_TABLE}'").isNotEmpty()
    if (!made) return emptyList()
    return rows(ds, "SELECT id, name, vector FROM ${ProjectFormat.GROUPS_TABLE}").map {
        val id = it.getValue("id")!!
        Project.Group(id, it["name"].orEmpty(), it["vector"] == "1", display[Project.groupKey(id)] ?: Project.Display())
    }
}

// What a layer of [geometry] holds, as a part of a vector layer takes it: POINT, LINESTRING or POLYGON (multi types too);
// null for mixed geometry, which takes every kind.
internal fun kindOf(geometry: String?): String? = when {
    geometry == null -> null
    geometry.contains("POLYGON") -> "POLYGON"
    geometry.contains("LINE") -> "LINESTRING"
    geometry.contains("POINT") -> "POINT"
    else -> null
}

// The layers and groups as the tree takes them: a group by its key in the display table, as layers name it as parent.
private fun treeNodes(layers: List<Project.Layer>, groups: List<Project.Group>): List<TreeNode> =
    layers.map { TreeNode(it.id, group = false, parent = it.display.parent, sort = it.display.sort, made = it.made) } +
        groups.map { TreeNode(Project.groupKey(it.id), group = true, sort = it.display.sort) }

// Each layer's, basemap's and group's display by id; nothing for a project whose table was never made.
private fun readDisplay(ds: VectorDataset): Map<String, Project.Display> {
    val made = rows(ds, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '${ProjectFormat.DISPLAY_TABLE}'").isNotEmpty()
    if (!made) return emptyMap()
    // Older projects have no parent column until a layer first goes into a group.
    val parent = rows(ds, "PRAGMA table_info(${ProjectFormat.DISPLAY_TABLE})").any { it["name"] == "parent" }
    return rows(ds, "SELECT layer_id, sort, visible, opacity${if (parent) ", parent" else ""} FROM ${ProjectFormat.DISPLAY_TABLE}").associate {
        it.getValue("layer_id")!! to Project.Display(it["sort"]?.toIntOrNull(), it["visible"] != "0", it["opacity"]?.toDoubleOrNull() ?: 1.0, it["parent"])
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
// The field types a range can be set on.
private val NUMBERS = setOf(FieldType.INTEGER, FieldType.INTEGER64, FieldType.REAL)

internal fun rows(ds: VectorDataset, sql: String): List<Map<String, String?>> =
    Json.parseToJsonElement(ds.sql(sql)).jsonArray.map { row ->
        row.jsonObject.getValue("properties").jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
    }

/**
 * Puts right the count of features GeoPackage keeps for each of [layers]
 * (gpkg_ogr_contents), where it is wrong. GDAL writes that count only when it
 * closes a layer, and Android ends apps without warning: QGIS and other
 * readers would show the count left behind (backlog). Called when a project
 * opens, before GDAL reads the counts, and after every save; a file with no
 * such table, or a count that cannot be put right, is left as it is.
 */
internal fun fixFeatureCounts(ds: VectorDataset, layers: Collection<String>) {
    // Never fails: after a save it runs on data already committed, which must not look unsaved.
    runCatching {
        if (rows(ds, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'gpkg_ogr_contents'").isEmpty()) return
        for (layer in layers) {
            runCatching {
                val n = rowCount(ds, layer)
                ds.sql("UPDATE gpkg_ogr_contents SET feature_count = $n WHERE lower(table_name) = lower(${quote(layer)}) AND feature_count IS NOT $n")
            }
        }
    }
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

/** How many daily snapshots a project keeps: a week of field days. */
const val SNAPSHOTS_KEPT = 7

// A snapshot's name: its date (also for listing and restoring them).
internal val SNAPSHOT_NAME = Regex("""\d{4}-\d{2}-\d{2}\.gpkg""")

// The name of the copy a project's file goes to before a snapshot is put in its place, then the time.
const val RESTORED_PREFIX = "恢复前-"


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
     * Draws layer [id] as the team's workspace has it (protocol v1 7.1): with
     * [style] as it came, or the default one for JsonNull. Not checked as
     * [setStyle] checks the user's: a newer phone may have more in it.
     */
    internal fun applyStyle(id: String, style: JsonElement) {
        dataset.sql(ProjectFormat.STYLE_SCHEMA)
        if (style is JsonObject && style.isNotEmpty()) {
            dataset.sql("INSERT OR REPLACE INTO ${ProjectFormat.STYLE_TABLE} (layer_id, style) VALUES (${quote(id)}, ${quote(style.toString())})")
        } else {
            dataset.sql("DELETE FROM ${ProjectFormat.STYLE_TABLE} WHERE layer_id = ${quote(id)}")
        }
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
    data class Display(val sort: Int? = null, val visible: Boolean = true, val opacity: Double = 1.0, val parent: String? = null)

    /**
     * A group in the layer list (ADR 0017): its stable [id], its name, whether
     * it is a [vector] layer (its points, lines and areas tables of their own),
     * and its place and whether it shows.
     */
    data class Group(val id: String, val name: String, val vector: Boolean, val display: Display = Display())

    /** The layer list's groups, in no order; [tree] puts them in place. */
    var groups: List<Group> = readGroups(dataset)
        private set

    /**
     * The layer list as a tree: the nodes at the top in order, a layer alone or
     * a group with its layers in order, the first drawn on top.
     */
    val tree: List<Pair<Group?, List<Layer>>>
        get() {
            val byKey = layers.associateBy { it.id }
            val groupsByKey = groups.associateBy { groupKey(it.id) }
            return LayerTree.tree(treeNodes(layers, groups)).mapNotNull { (node, children) ->
                if (node.group) {
                    groupsByKey[node.id]?.let { it to children.mapNotNull { c -> byKey[c.id] } }
                } else {
                    byKey[node.id]?.let { null to listOf(it) }
                }
            }
        }

    /** Whether layer [layer] shows on the map: its own switch, and its group's. */
    fun shows(layer: Layer): Boolean =
        layer.display.visible && (layer.display.parent?.let { key -> groups.firstOrNull { groupKey(it.id) == key } }?.display?.visible ?: true)

    /**
     * Makes a group named [name], a [vector] layer or not, with layers [inside]
     * in it in that order, where the first of them was (on top without any);
     * saved at once, not undone.
     */
    fun createGroup(name: String, vector: Boolean = false, inside: List<String> = emptyList()): Group {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME) { "a group name has 1 to ${ProjectFormat.MAX_NAME} characters" }
        require(groups.none { it.name.equals(trimmed, ignoreCase = true) }) { "there is a group $trimmed already" }
        val ids = inside.map { n -> layers.firstOrNull { it.name == n }?.id ?: throw IllegalArgumentException("there is no layer $n") }
        val id = UUID.randomUUID().toString()
        dataset.begin()
        try {
            dataset.sql(ProjectFormat.GROUPS_SCHEMA)
            dataset.sql("INSERT INTO ${ProjectFormat.GROUPS_TABLE} (id, name, vector) VALUES (${quote(id)}, ${quote(trimmed)}, ${if (vector) 1 else 0})")
            // Where the first layer is at the top (or the group it is in), before it goes in.
            val at = ids.firstOrNull()?.let { first ->
                val tree = LayerTree.tree(treeNodes(layers, groups))
                tree.indexOfFirst { (node, children) -> node.id == first || children.any { it.id == first } }.takeIf { it >= 0 }
            } ?: 0
            reload()
            var nodes = treeNodes(layers, groups)
            nodes = placed(nodes, LayerTree.move(nodes, groupKey(id), null, at))
            for ((i, layer) in ids.withIndex()) nodes = placed(nodes, LayerTree.move(nodes, layer, groupKey(id), i))
            writePlaces(nodes)
            dataset.commit()
        } catch (e: Exception) {
            dataset.rollback()
            reload()
            throw e
        }
        reload()
        return groups.single { it.id == id }
    }

    /**
     * Adds to the vector layer of [layer] (ADR 0017) a part for features of
     * [geometry] (POINT, LINESTRING or POLYGON): a table of its own with the
     * fields, CRS and look of [layer] ([color], #rrggbb, when [layer] has no
     * style of its own), named after the vector layer and the kind in
     * [language] (断层 线), last in it. A layer in no vector layer yet becomes
     * one: a group named after it, where it was. Saved at once, not undone.
     */
    fun createPart(layer: String, geometry: String, language: String, color: String? = null): Layer {
        val source = layers.firstOrNull { it.name == layer } ?: throw IllegalArgumentException("there is no layer $layer")
        val template = requireNotNull(Templates.forGeometry(geometry)) { "a part holds POINT, LINESTRING or POLYGON" }
        // A layer of mixed geometry takes every kind: it needs no parts.
        val own = requireNotNull(Templates.forGeometry(kindOf(source.geometry) ?: "")) { "$layer takes every kind already" }
        require(own != template) { "$layer holds them already" }
        var group = groups.firstOrNull { it.vector && groupKey(it.id) == source.display.parent }
        group?.let { g -> require(layers.none { it.display.parent == groupKey(g.id) && kindOf(it.geometry) == geometry }) { "${g.name} has its part already" } }
        if (group == null) {
            val base = source.name
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { n -> groups.none { it.name.equals(n, ignoreCase = true) } }
            group = createGroup(name, vector = true, inside = listOf(source.name))
        }
        val name = freeLayerName("${group.name} ${Templates.partName(geometry, language)}")
        val fields = VectorLayerStore(dataset, source.name).fields.filter { it.type != FieldType.OTHER } // fields GeoCrew cannot edit stay behind
        // A field template goes with the kind; another (a track's) stays with its layer.
        val fieldTemplates = listOf("POINT", "LINESTRING", "POLYGON").mapNotNull(Templates::forGeometry).map { it.id }
        val kept = if (source.template in fieldTemplates) template.id else null
        addLayer(dataset, name, geometry, source.srs ?: "EPSG:4490", kept, fields)
        for (f in fields) f.range?.let { FieldRanges.set(dataset, name, f.name, it) }
        layers = readLayers(dataset)
        val made = layers.single { it.name == name }
        val style = styles[source.id]
        if (style != null) {
            dataset.sql("INSERT OR REPLACE INTO ${ProjectFormat.STYLE_TABLE} (layer_id, style) VALUES (${quote(made.id)}, ${quote(style.toString())})")
        } else if (color != null) {
            setStyle(name, buildJsonObject { put("color", color) })
        }
        move(layer = name, into = group.id, index = Int.MAX_VALUE / 2)
        return layers.single { it.name == name }
    }

    /** Renames group [id]; saved at once. */
    fun renameGroup(id: String, to: String) {
        val trimmed = to.trim()
        require(groups.any { it.id == id }) { "there is no group $id" }
        require(trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME) { "a group name has 1 to ${ProjectFormat.MAX_NAME} characters" }
        require(groups.none { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) { "there is a group $trimmed already" }
        dataset.sql("UPDATE ${ProjectFormat.GROUPS_TABLE} SET name = ${quote(trimmed)} WHERE id = ${quote(id)}")
        reload()
    }

    /** Takes group [id] away; its layers stay, at its place at the top. Saved at once. */
    fun removeGroup(id: String) {
        require(groups.any { it.id == id }) { "there is no group $id" }
        val places = LayerTree.ungroup(treeNodes(layers, groups), groupKey(id))
        dataset.begin()
        try {
            writePlaces(places)
            dataset.sql("DELETE FROM ${ProjectFormat.GROUPS_TABLE} WHERE id = ${quote(id)}")
            dataset.sql("DELETE FROM ${ProjectFormat.DISPLAY_TABLE} WHERE layer_id = ${quote(groupKey(id))}")
            dataset.commit()
        } catch (e: Exception) {
            dataset.rollback()
            throw e
        } finally {
            reload()
        }
    }

    /** Shows or hides group [id], and with it its layers; saved at once. */
    fun setGroupDisplay(id: String, visible: Boolean) {
        require(groups.any { it.id == id }) { "there is no group $id" }
        dataset.sql(ProjectFormat.DISPLAY_SCHEMA)
        dataset.sql("INSERT OR IGNORE INTO ${ProjectFormat.DISPLAY_TABLE} (layer_id) VALUES (${quote(groupKey(id))})")
        dataset.sql("UPDATE ${ProjectFormat.DISPLAY_TABLE} SET visible = ${if (visible) 1 else 0} WHERE layer_id = ${quote(groupKey(id))}")
        reload()
    }

    /**
     * Moves layer [layer] or group [group] to place [index] (0 first) among
     * the nodes of group [into] (null for the top; a group always goes at the
     * top); saved at once.
     */
    fun move(layer: String? = null, group: String? = null, into: String? = null, index: Int) {
        require((layer == null) != (group == null)) { "a layer or a group, not both" }
        val key = when {
            layer != null -> layers.firstOrNull { it.name == layer }?.id ?: throw IllegalArgumentException("there is no layer $layer")
            group != null -> group.takeIf { g -> groups.any { it.id == g } }?.let(::groupKey) ?: throw IllegalArgumentException("there is no group $group")
            else -> throw IllegalArgumentException("a layer or a group")
        }
        val parent = into?.let { g -> g.takeIf { groups.any { it.id == g } }?.let(::groupKey) ?: throw IllegalArgumentException("there is no group $g") }
        require(index >= 0) { "a place is 0 or more" }
        val places = LayerTree.move(treeNodes(layers, groups), key, parent, index)
        dataset.begin()
        try {
            writePlaces(places)
            dataset.commit()
        } catch (e: Exception) {
            dataset.rollback()
            throw e
        } finally {
            reload()
        }
    }

    // [nodes] with the places given.
    private fun placed(nodes: List<TreeNode>, places: Map<String, Place>): List<TreeNode> =
        nodes.map { n -> places[n.id]?.let { n.copy(parent = it.parent, sort = it.sort) } ?: n }

    // Every node's place, as the tree has it.
    private fun writePlaces(nodes: List<TreeNode>) = writePlaces(nodes.associate { it.id to Place(it.parent, it.sort ?: 0) })

    private fun writePlaces(places: Map<String, Place>) {
        dataset.sql(ProjectFormat.DISPLAY_SCHEMA)
        if (rows(dataset, "PRAGMA table_info(${ProjectFormat.DISPLAY_TABLE})").none { it["name"] == "parent" }) dataset.sql(ProjectFormat.DISPLAY_PARENT)
        for ((key, place) in places) {
            dataset.sql("INSERT OR IGNORE INTO ${ProjectFormat.DISPLAY_TABLE} (layer_id) VALUES (${quote(key)})")
            dataset.sql(
                "UPDATE ${ProjectFormat.DISPLAY_TABLE} SET sort = ${place.sort}, parent = ${place.parent?.let(::quote) ?: "NULL"} WHERE layer_id = ${quote(key)}",
            )
        }
    }

    private fun reload() {
        groups = readGroups(dataset)
        layers = readLayers(dataset)
    }

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

    /**
     * Puts the layers in the order of [names], every layer once, the first
     * drawn on top; saved at once. Layers stay in their groups: the order
     * within a group follows [names], and a group goes where its first layer
     * is named (an empty one last).
     */
    fun setOrder(names: List<String>) {
        require(names.size == layers.size && names.toSet() == layers.map { it.name }.toSet()) { "name every layer once" }
        val at = names.withIndex().associate { (i, n) -> layers.single { it.name == n }.id to i }
        val places = LinkedHashMap<String, Place>()
        val top = LayerTree.tree(treeNodes(layers, groups)).sortedBy { (node, children) ->
            if (node.group) children.minOfOrNull { at.getValue(it.id) } ?: Int.MAX_VALUE else at.getValue(node.id)
        }
        for ((i, pair) in top.withIndex()) {
            val (node, children) = pair
            places[node.id] = Place(null, i)
            children.sortedBy { at.getValue(it.id) }.forEachIndexed { j, c -> places[c.id] = Place(node.id, j) }
        }
        dataset.begin()
        try {
            writePlaces(places)
            dataset.commit()
        } catch (e: Exception) {
            dataset.rollback()
            throw e
        } finally {
            reload()
        }
    }

    /** The layers, in the order they were made. */
    var layers: List<Layer> = layers
        private set

    val attachments: File get() = File(folder, ProjectFormat.ATTACHMENTS)

    /** The project's GeoPackage. */
    val file: File get() = File(folder, "$name.gpkg")

    /** The folder of the project's daily snapshots. */
    val snapshots: File get() = File(folder, ProjectFormat.SNAPSHOTS)

    /** The days the project has a snapshot of (2026-11-27), newest first. */
    val snapshotDays: List<String>
        get() = snapshots.listFiles { f -> SNAPSHOT_NAME.matches(f.name) }.orEmpty().map { it.name.removeSuffix(".gpkg") }.sortedDescending()

    // A consistent copy of the project's file at [target], as a snapshot is made (VACUUM INTO), written under another name first.
    internal fun keepCopy(target: File) {
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "cannot make the folder ${target.parentFile}" }
        val part = File(target.parentFile, ".${target.name}.part")
        part.delete()
        try {
            dataset.sql("VACUUM INTO ${quote(part.path)}")
            VectorDataset.open(part.path, update = true).use { it.sql("PRAGMA journal_mode=DELETE") }
            check(part.renameTo(target)) { "cannot name the copy $target" }
        } finally {
            part.delete()
        }
    }

    /** Why the day's snapshot could not be made when the project opened; null when it was, or was there already. */
    var snapshotFailure: String? = null
        internal set

    /** [snapshotFailure], said once: later calls give null. */
    fun takeSnapshotFailure(): String? = snapshotFailure.also { snapshotFailure = null }

    /**
     * Makes the snapshot of [day] unless there is one (ADR 0004): a consistent
     * copy of the project's GeoPackage, made by SQLite itself (VACUUM INTO, as
     * GDAL gives no way to its backup interface), written under another name and
     * then named after the date, in rollback-journal mode so it is a file of its
     * own. Only the newest [keep] stay. The attachments are not copied: their
     * files are never changed or deleted by edits. Returns the snapshot made, or
     * null when the day had one.
     */
    fun snapshot(day: LocalDate = LocalDate.now(), keep: Int = SNAPSHOTS_KEPT): File? {
        val target = File(snapshots, "$day.gpkg")
        if (target.exists()) return null
        check(snapshots.isDirectory || snapshots.mkdirs()) { "cannot make the folder $snapshots" }
        val part = File(snapshots, ".$day.part.gpkg")
        part.delete()
        try {
            dataset.sql("VACUUM INTO ${quote(part.path)}")
            VectorDataset.open(part.path, update = true).use { it.sql("PRAGMA journal_mode=DELETE") }
            check(part.renameTo(target)) { "cannot name the snapshot $target" }
        } finally {
            part.delete()
        }
        snapshots.listFiles { f -> SNAPSHOT_NAME.matches(f.name) }.orEmpty().sortedByDescending { it.name }.drop(keep).forEach { it.delete() }
        return target
    }

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
    fun addField(layer: String, name: String, type: FieldType) = addField(layer, name, type, UUID.randomUUID().toString())

    /**
     * Adds field [name] of [type] to [layer] with the id [id] the team's
     * workspace gave it (protocol v1 7.2), so that values go by the same id here
     * and there. Open the project again afterwards, see [Projects.reopen].
     */
    internal fun addField(layer: String, name: String, type: FieldType, id: String) {
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
        dataset.sql("INSERT INTO ${ProjectFormat.FIELDS_TABLE} VALUES (${quote(layer)}, ${quote(name)}, ${quote(id)})")
    }

    /**
     * Field [column] of [layer] (id [layerId]) takes the id [to] the team's
     * workspace has for a field of its name and kind (protocol v1 7.4): two
     * members made the same field offline, and its values go into one. Its
     * values still to send go by the new id too.
     */
    internal fun adoptFieldId(layer: String, layerId: String, column: String, to: String) {
        val from = Outbox.fieldIds(dataset, layer)[column] ?: return
        dataset.sql("UPDATE ${ProjectFormat.FIELDS_TABLE} SET id = ${quote(to)} WHERE table_name = ${quote(layer)} AND column_name = ${quote(column)}")
        val outbox = rows(dataset, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ${quote(ProjectFormat.OUTBOX_TABLE)}").isNotEmpty()
        if (outbox) dataset.sql("UPDATE ${ProjectFormat.OUTBOX_TABLE} SET field = ${quote(to)} WHERE layer_id = ${quote(layerId)} AND field = ${quote(from)}")
    }

    /**
     * The names of [layer]'s fields as the file has them now, its fid and
     * geometry aside: GDAL's own list waits till the project is opened again,
     * and sync changes several fields in one go (protocol v1 7.4).
     */
    fun fieldNames(layer: String): List<String> {
        require(layers.any { it.name == layer }) { "there is no layer $layer" }
        val geometry = rows(dataset, "SELECT column_name FROM gpkg_geometry_columns WHERE table_name = ${quote(layer)}").mapNotNull { it["column_name"] }
        return rows(dataset, "SELECT name, pk FROM pragma_table_info(${quote(layer)})").filter { it["pk"] == "0" && it["name"] !in geometry }.mapNotNull { it["name"] }
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
            FieldRanges.renamingColumn(dataset, layer, field, to) {
                // SQLite takes column names in any case as the same, so a change of case goes by way of another name.
                val via = if (to.equals(field, ignoreCase = true)) "geocrew_" + UUID.randomUUID().toString().replace("-", "") else field
                if (via != field) dataset.sql("ALTER TABLE $table RENAME COLUMN ${ProjectFormat.identifier(field)} TO ${ProjectFormat.identifier(via)}")
                dataset.sql("ALTER TABLE $table RENAME COLUMN ${ProjectFormat.identifier(via)} TO ${ProjectFormat.identifier(to)}")
            }
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
            FieldRanges.columnDropped(dataset, layer, field)
            dataset.sql("ALTER TABLE ${ProjectFormat.identifier(layer)} DROP COLUMN ${ProjectFormat.identifier(field)}")
            dataset.sql("DELETE FROM ${ProjectFormat.FIELDS_TABLE} WHERE table_name = ${quote(layer)} AND column_name = ${quote(field)}")
            restyle(id, field, null)
        }
    }

    /**
     * Lets number field [field] of [layer] take only the numbers in [range],
     * or with null any (ADR 0004: a GeoPackage range constraint, which GDAL
     * and QGIS read). Edits keep to it from then on; values already in the
     * layer stay. Saved at once, not undone, like a style.
     */
    fun setFieldRange(layer: String, field: String, range: ValueRange?) {
        require(layers.any { it.name == layer }) { "there is no layer $layer" }
        val found = VectorLayerStore(dataset, layer).fields.firstOrNull { it.name == field } ?: throw IllegalArgumentException("$layer has no field $field")
        require(range == null || found.type in NUMBERS) { "only number fields have ranges, and $field is ${found.type.name.lowercase()}" }
        changing { FieldRanges.set(dataset, layer, field, range) }
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
        for (f in fields) f.range?.let { FieldRanges.set(dataset, name, f.name, it) }
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
            FieldRanges.renamingTable(dataset, layer, to) {
                // SQLite takes table names in any case as the same, so a change of case goes by way of another name.
                val via = if (to.equals(layer, ignoreCase = true)) "geocrew_renaming" else layer
                if (via != layer) dataset.sql("ALTER TABLE ${ProjectFormat.identifier(layer)} RENAME TO ${ProjectFormat.identifier(via)}")
                dataset.sql("ALTER TABLE ${ProjectFormat.identifier(via)} RENAME TO ${ProjectFormat.identifier(to)}")
            }
            dataset.sql("UPDATE ${ProjectFormat.LAYERS_TABLE} SET table_name = ${quote(to)} WHERE table_name = ${quote(layer)}")
            dataset.sql("UPDATE ${ProjectFormat.FIELDS_TABLE} SET table_name = ${quote(to)} WHERE table_name = ${quote(layer)}")
            AttachmentTables.tableRenamed(dataset, layers.single { it.name == layer }.id, to)
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
        AttachmentTables.droppingTable(dataset, found.id) {
            FieldRanges.droppingTable(dataset, layer) { dataset.sql("DROP TABLE ${ProjectFormat.identifier(layer)}") }
        }
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
        val nodes = treeNodes(layers, groups)
        if (!LayerTree.ordered(nodes)) return
        writePlaces(LayerTree.move(nodes, layers.single { it.name == name }.id, null, 0))
        reload()
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
        return EditTarget(VectorLayerStore(dataset, found.name, found.id), FileJournal(File(journals, "${found.id}.jsonl")), found.srs)
    }

    /**
     * Links the project to team workspace [workspace] (protocol v1): from then
     * on every save records what the workspace is to be sent ([Outbox]). For
     * the join flow (M3).
     */
    internal fun link(workspace: String) {
        dataset.sql("INSERT OR REPLACE INTO ${ProjectFormat.PROJECT_TABLE} (key, value) VALUES (${quote(Outbox.WORKSPACE_KEY)}, ${quote(workspace)})")
    }

    /** What the project has to send its workspace, oldest edit first: row id, layer id, field id, value (JSON) and edit time. */
    internal val outbox: List<Map<String, String?>> get() = Outbox.pending(dataset)

    /** The row id of feature [fid] of layer [layerId] in the workspace, once it has one. */
    internal fun rowId(layerId: String, fid: Long): String? = Outbox.rowId(dataset, layerId, fid)

    /**
     * Makes layer [name] as a team's workspace has it (protocol v1 7.1, 7.2):
     * with its id [id], geometry type (as GDAL names it, such as POINT), CRS,
     * and [fields] (id, name, type) by their ids, so that rows and fields go by
     * the same ids here and there. Saved at once, not undone.
     */
    internal fun createSharedLayer(id: String, name: String, geometry: String, crs: String, fields: List<Triple<String, String, FieldType>>): Layer {
        checkNewName(name)
        Crs.describe(crs) // a CRS GDAL does not know is refused before anything is made
        val described = buildJsonArray {
            for ((_, field, type) in fields) add(buildJsonObject { put("name", field); put("type", type.name.lowercase()) })
        }
        fieldsPerLayer()
        dataset.createLayer(name, geometry, crs, described.toString())
        dataset.sql("INSERT INTO ${ProjectFormat.LAYERS_TABLE} VALUES (${quote(name)}, ${quote(id)}, NULL)")
        for ((fieldId, field, _) in fields) {
            dataset.sql("INSERT INTO ${ProjectFormat.FIELDS_TABLE} VALUES (${quote(name)}, ${quote(field)}, ${quote(fieldId)})")
        }
        layers = readLayers(dataset)
        return layers.single { it.name == name }
    }

    // Field ids unique within their layer, not among all layers, as a workspace has them (protocol v1 7.2): another
    // layer of the team's may use an id one here uses. A project made before kept them unique among all: made again.
    private fun fieldsPerLayer() {
        val made = rows(dataset, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ${quote(ProjectFormat.FIELDS_TABLE)}").firstOrNull()?.get("sql").orEmpty()
        if (made.contains("UNIQUE (table_name, id)")) return
        dataset.begin()
        try {
            ProjectFormat.FIELDS_PER_LAYER.forEach(dataset::sql)
            dataset.commit()
        } catch (e: Exception) {
            runCatching { dataset.rollback() }
            throw e
        }
    }

    /** [block] with the project's file, for sync (ProjectSync), which writes GeoCrew's own tables in it. */
    internal fun <T> data(block: (VectorDataset) -> T): T = block(dataset)

    /** Layer [layer]'s fields by name, to the stable ids sync knows them by. */
    internal fun fieldIds(layer: String): Map<String, String> = Outbox.fieldIds(dataset, layer)

    /** Whether the project has been closed: its file is no longer open. */
    var closed = false
        private set

    override fun close() {
        closed = true
        dataset.close()
    }

    companion object {
        // Tables of GeoPackage, its spatial indexes, SQLite and GeoCrew itself.
        val RESERVED_PREFIXES = listOf("gpkg_", "rtree_", "sqlite_", "geocrew_")

        /** Tianditu imagery and its annotation (ADR 0015). */
        val BASEMAPS = listOf("img", "cia")

        /** A group's key in the display table, where its layers name it as their parent. */
        fun groupKey(id: String) = "group:$id"
    }
}

/** A layer brought into a project that does not say its CRS: there is no knowing where it is. */
class NoCrs(message: String) : IllegalArgumentException(message)
