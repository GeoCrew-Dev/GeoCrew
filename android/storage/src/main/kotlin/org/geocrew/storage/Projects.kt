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
import org.geocrew.core.edit.FileJournal
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

    /** What the project list shows of a project; null when its file cannot be read. */
    data class Summary(val layers: Int, val features: Long, val bytes: Long, val created: String?)

    /**
     * Reads project [name] without opening it for editing: its layers, their
     * features (GeoPackage keeps the counts), its size and when it was made.
     */
    fun summary(name: String): Summary? = runCatching {
        VectorDataset.open(gpkg(name).path).use { ds ->
            val present = ds.layers.toSet()
            val layers = rows(ds, "SELECT table_name FROM ${ProjectFormat.LAYERS_TABLE}").mapNotNull { it["table_name"] }.filter { it in present }
            val features = layers.sumOf {
                Json.parseToJsonElement(ds.layerInfo(it)).jsonObject["count"]?.let { c -> (c as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
            }
            val created = rows(ds, "SELECT value FROM ${ProjectFormat.PROJECT_TABLE} WHERE key = 'created'").firstOrNull()?.get("value")
            Summary(layers.size, features, gpkg(name).length() + File(gpkg(name).path + "-wal").length(), created)
        }
    }.getOrNull()

    /** Makes project [name] with a layer for each of [templates], and opens it. */
    fun create(name: String, templates: List<LayerTemplate> = Templates.all): Project {
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
                for (template in templates) addLayer(ds, template)
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
            val present = ds.layers.toSet()
            val layers = rows(ds, "SELECT table_name, id, template FROM ${ProjectFormat.LAYERS_TABLE} ORDER BY rowid")
                .map { Project.Layer(it.getValue("table_name")!!, it.getValue("id")!!, it["template"]) }
                .filter { it.name in present }
            val id = about.getValue("id")!!
            return Project(name, File(dir, name), ds, id, layers, File(journals, "$id-${pathKey(file)}"))
        } catch (e: Exception) {
            ds.close()
            throw e
        }
    }

    private fun gpkg(name: String) = File(File(dir, name), "$name.gpkg")

    private companion object {
        const val EXPORTS = "导出"
    }

    // A copied project folder keeps the project's id; its path tells the copies'
    // journals apart, so one copy's unsaved edits never turn up in another.
    private fun pathKey(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.canonicalPath.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private fun addLayer(ds: VectorDataset, template: LayerTemplate) {
        val fields = buildJsonArray {
            for (f in template.fields) add(buildJsonObject { put("name", f.name); put("type", f.type.name.lowercase()) })
        }
        ds.createLayer(template.name, template.geometryType, template.crs, fields.toString())
        val table = quote(template.name)
        ds.sql("INSERT INTO ${ProjectFormat.LAYERS_TABLE} VALUES ($table, ${quote(UUID.randomUUID().toString())}, ${quote(template.id)})")
        for (f in template.fields) {
            ds.sql("INSERT INTO ${ProjectFormat.FIELDS_TABLE} VALUES ($table, ${quote(f.name)}, ${quote(UUID.randomUUID().toString())})")
        }
    }

    // The rows of a query, as column name to text.
    private fun rows(ds: VectorDataset, sql: String): List<Map<String, String?>> =
        Json.parseToJsonElement(ds.sql(sql)).jsonArray.map { row ->
            row.jsonObject.getValue("properties").jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
        }
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
    val layers: List<Layer>,
    private val journals: File,
) : AutoCloseable {
    /** A layer of the project: its table [name], stable [id] and the template it was made from. */
    data class Layer(val name: String, val id: String, val template: String?)

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

    /** What editing [layer] needs: its store, and its journal in the app's own files, named by the layer's id. */
    fun target(layer: String): EditTarget {
        val found = layers.firstOrNull { it.name == layer } ?: throw IllegalArgumentException("there is no layer $layer")
        journals.mkdirs()
        return EditTarget(VectorLayerStore(dataset, found.name), FileJournal(File(journals, "${found.id}.jsonl")))
    }

    override fun close() = dataset.close()
}
