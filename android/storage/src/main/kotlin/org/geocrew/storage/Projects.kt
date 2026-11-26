package org.geocrew.storage

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

    /** What the project list shows of a project; null when its file cannot be read. */
    data class Summary(val layers: Int, val features: Long, val bytes: Long, val created: String?)

    /**
     * Reads project [name] without opening it for editing: its layers, how many
     * features they have, its size and when it was made.
     */
    fun summary(name: String): Summary? = runCatching {
        VectorDataset.open(gpkg(name).path).use { ds ->
            val present = ds.layers.toSet()
            val layers = rows(ds, "SELECT table_name FROM ${ProjectFormat.LAYERS_TABLE}").mapNotNull { it["table_name"] }.filter { it in present }
            val features = layers.sumOf { rowCount(ds, it) }
            val created = rows(ds, "SELECT value FROM ${ProjectFormat.PROJECT_TABLE} WHERE key = 'created'").firstOrNull()?.get("value")
            Summary(layers.size, features, gpkg(name).length() + File(gpkg(name).path + "-wal").length(), created)
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

    private companion object {
        const val EXPORTS = "导出"
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
    return rows(ds, "SELECT table_name, id, template FROM ${ProjectFormat.LAYERS_TABLE} ORDER BY rowid")
        .filter { it["table_name"] in present }
        .mapIndexed { made, it ->
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
    fun setDisplay(layer: String? = null, basemap: String? = null, visible: Boolean? = null, opacity: Double? = null) {
        val id = when {
            layer != null -> layers.firstOrNull { it.name == layer }?.id ?: throw IllegalArgumentException("there is no layer $layer")
            basemap != null -> basemap.takeIf { it in BASEMAPS }?.let { "basemap:$it" } ?: throw IllegalArgumentException("there is no basemap $basemap")
            else -> throw IllegalArgumentException("a layer or a basemap")
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
     * Makes layer [name] like layer [like]: its fields, geometry type, CRS and
     * template, and no features. A feature moved between the two keeps every value.
     */
    fun createLayerLike(name: String, like: String): Layer {
        checkNewName(name)
        val source = layers.firstOrNull { it.name == like } ?: throw IllegalArgumentException("there is no layer $like")
        val fields = VectorLayerStore(dataset, like).fields.filter { it.type != FieldType.OTHER } // fields GeoCrew cannot edit stay behind
        addLayer(dataset, name, source.geometry ?: "POINT", source.srs ?: "EPSG:4490", source.template, fields)
        layers = readLayers(dataset)
        putOnTop(name)
        return layers.single { it.name == name }
    }

    // A new layer goes on top of a list that has been put in order, as in QGIS; an unordered list keeps the order layers were made.
    private fun putOnTop(name: String) {
        if (layers.none { it.display.sort != null }) return
        setOrder(listOf(name) + layers.map { it.name }.filter { it != name })
    }

    private fun checkNewName(name: String) {
        require(name.isNotBlank() && name == name.trim() && name.length <= ProjectFormat.MAX_NAME) {
            "a layer name has 1 to ${ProjectFormat.MAX_NAME} characters, without spaces at either end"
        }
        require(RESERVED_PREFIXES.none { name.startsWith(it, ignoreCase = true) }) { "a layer name cannot start with ${RESERVED_PREFIXES.joinToString()}" }
        require(dataset.layers.none { it.equals(name, ignoreCase = true) }) { "the project already has a layer $name" }
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
