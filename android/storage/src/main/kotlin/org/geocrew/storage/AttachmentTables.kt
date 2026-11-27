package org.geocrew.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.geocrew.core.edit.Attachment
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.quote
import org.geocrew.nativelib.VectorDataset

/**
 * Where a project's attachments are listed (ADR 0004): one attributes table,
 * [TABLE], with a row per attachment as protocol v1 9.1 has them, and for each
 * layer that has any, a mapping table from its features to those rows,
 * registered with the GeoPackage Related Tables Extension, which GDAL reads as
 * a relationship. A mapping table is named after its layer's id, so a renamed
 * layer keeps it. The tables are made with the first attachment.
 *
 * A row's id is read as an expression ("row_id"): once GDAL knows the table as a
 * layer, it takes a plain "id" column for the result's fid and leaves it out.
 */
internal object AttachmentTables {
    const val TABLE = "geocrew_attachments"
    private const val RELATIONS = "gpkgext_relations"
    private const val EXTENSION = "gpkg_related_tables"
    private const val DEFINITION = "http://www.geopackage.org/18-000.html"

    /** The mapping table of the layer whose id is [layerId]. */
    fun mapping(layerId: String): String = "geocrew_attachments_" + layerId.replace("-", "")

    /** Feature [fid]'s attachments in the layer whose id is [layerId], in their order. */
    fun of(dataset: VectorDataset, layerId: String, fid: Long): List<Attachment> {
        val map = mapping(layerId)
        if (!exists(dataset, map)) return emptyList()
        return rows(
            dataset,
            "SELECT a.uuid AS uuid, a.file AS file, a.thumb AS thumb, a.type AS type, a.size AS size, a.name AS name, a.meta AS meta " +
                "FROM ${id(map)} m JOIN $TABLE a ON a.id = m.related_id WHERE m.base_id = $fid ORDER BY m.position",
        ).mapNotNull { row ->
            // A row that is no attachment (written by another program) is left out, not the whole list.
            runCatching {
                Attachment(
                    id = row["uuid"].orEmpty(),
                    file = row["file"].orEmpty(),
                    type = row["type"].orEmpty(),
                    size = row["size"]?.toLongOrNull() ?: 0,
                    name = row["name"].orEmpty(),
                    thumb = row["thumb"],
                    meta = row["meta"]?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
                )
            }.getOrNull()
        }
    }

    /** Gives feature [fid] of [layer] (whose id is [layerId]) these [attachments] and no others; rows it no longer has go. */
    fun set(dataset: VectorDataset, layer: String, layerId: String, fid: Long, attachments: List<Attachment>) {
        val map = mapping(layerId)
        if (attachments.isEmpty() && !exists(dataset, map)) return
        make(dataset, layer, map)
        val before = rows(dataset, "SELECT (a.id + 0) AS row_id, a.uuid AS uuid FROM ${id(map)} m JOIN $TABLE a ON a.id = m.related_id WHERE m.base_id = $fid")
        dataset.sql("DELETE FROM ${id(map)} WHERE base_id = $fid")
        val kept = attachments.map { it.id }.toSet()
        for (row in before) if (row["uuid"] !in kept) dataset.sql("DELETE FROM $TABLE WHERE id = ${row["row_id"]}")
        for ((position, a) in attachments.withIndex()) {
            val found = rowOf(dataset, a.id)
            if (found == null) {
                dataset.sql(
                    "INSERT INTO $TABLE (uuid, file, thumb, type, size, name, meta) VALUES " +
                        "(${quote(a.id)}, ${quote(a.file)}, ${text(a.thumb)}, ${quote(a.type)}, ${a.size}, ${quote(a.name)}, ${text(a.meta?.toString())})",
                )
            } else {
                dataset.sql(
                    "UPDATE $TABLE SET file = ${quote(a.file)}, thumb = ${text(a.thumb)}, type = ${quote(a.type)}, size = ${a.size}, " +
                        "name = ${quote(a.name)}, meta = ${text(a.meta?.toString())} WHERE id = $found",
                )
            }
            val row = found ?: checkNotNull(rowOf(dataset, a.id)) { "attachment ${a.id} was not written" }
            dataset.sql("INSERT INTO ${id(map)} (base_id, related_id, position) VALUES ($fid, $row, $position)")
        }
    }

    /** The layer whose id is [layerId] is now called [to]: its relationship follows. */
    fun tableRenamed(dataset: VectorDataset, layerId: String, to: String) {
        if (!exists(dataset, RELATIONS)) return
        dataset.sql("UPDATE $RELATIONS SET base_table_name = ${quote(to)} WHERE mapping_table_name = ${quote(mapping(layerId))}")
    }

    /** Drops the layer whose id is [layerId] by [drop]; once it is gone, so are its attachment rows, mapping table and relationship. */
    fun droppingTable(dataset: VectorDataset, layerId: String, drop: () -> Unit) {
        val map = mapping(layerId)
        // Noted first: GDAL may drop what it knows of the relationship with the layer.
        val ids = if (exists(dataset, map)) rows(dataset, "SELECT (related_id + 0) AS row_id FROM ${id(map)}").mapNotNull { it["row_id"] } else emptyList()
        drop()
        for (chunk in ids.chunked(500)) dataset.sql("DELETE FROM $TABLE WHERE id IN (${chunk.joinToString()})")
        if (exists(dataset, map)) dataset.sql("DROP TABLE ${id(map)}")
        if (exists(dataset, RELATIONS)) dataset.sql("DELETE FROM $RELATIONS WHERE mapping_table_name = ${quote(map)}")
        if (exists(dataset, "gpkg_extensions")) dataset.sql("DELETE FROM gpkg_extensions WHERE table_name = ${quote(map)}")
    }

    private fun rowOf(dataset: VectorDataset, uuid: String): String? =
        rows(dataset, "SELECT (id + 0) AS row_id FROM $TABLE WHERE uuid = ${quote(uuid)}").firstOrNull()?.get("row_id")

    // The attachment table (an attributes table, as the extension wants), the relationships table and the layer's mapping table.
    private fun make(dataset: VectorDataset, layer: String, map: String) {
        if (!exists(dataset, TABLE)) {
            dataset.sql(
                "CREATE TABLE $TABLE (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE, file TEXT NOT NULL, thumb TEXT, " +
                    "type TEXT NOT NULL, size INTEGER NOT NULL, name TEXT NOT NULL, meta TEXT)",
            )
            dataset.sql("INSERT INTO gpkg_contents (table_name, data_type, identifier, description) VALUES ('$TABLE', 'attributes', '$TABLE', '')")
        }
        if (!exists(dataset, RELATIONS)) {
            dataset.sql(
                "CREATE TABLE $RELATIONS (id INTEGER PRIMARY KEY AUTOINCREMENT, base_table_name TEXT NOT NULL, " +
                    "base_primary_column TEXT NOT NULL DEFAULT 'id', related_table_name TEXT NOT NULL, related_primary_column TEXT NOT NULL DEFAULT 'id', " +
                    "relation_name TEXT NOT NULL, mapping_table_name TEXT NOT NULL UNIQUE)",
            )
            register(dataset, RELATIONS)
        }
        if (!exists(dataset, map)) {
            dataset.sql("CREATE TABLE ${id(map)} (base_id INTEGER NOT NULL, related_id INTEGER NOT NULL UNIQUE, position INTEGER NOT NULL)")
            val key = rows(dataset, "SELECT name FROM pragma_table_info(${quote(layer)}) WHERE pk = 1").firstOrNull()?.get("name") ?: "fid"
            dataset.sql(
                "INSERT INTO $RELATIONS (base_table_name, base_primary_column, related_table_name, related_primary_column, relation_name, mapping_table_name) " +
                    "VALUES (${quote(layer)}, ${quote(key)}, '$TABLE', 'id', 'attributes', ${quote(map)})",
            )
            register(dataset, map)
        }
    }

    private fun register(dataset: VectorDataset, table: String) {
        dataset.sql(
            "CREATE TABLE IF NOT EXISTS gpkg_extensions (table_name TEXT, column_name TEXT, extension_name TEXT NOT NULL, " +
                "definition TEXT NOT NULL, scope TEXT NOT NULL, CONSTRAINT ge_tce UNIQUE (table_name, column_name, extension_name))",
        )
        dataset.sql(
            "INSERT INTO gpkg_extensions (table_name, column_name, extension_name, definition, scope) " +
                "SELECT ${quote(table)}, NULL, '$EXTENSION', '$DEFINITION', 'read-write' " +
                "WHERE NOT EXISTS (SELECT 1 FROM gpkg_extensions WHERE table_name = ${quote(table)} AND extension_name = '$EXTENSION')",
        )
    }

    private fun exists(dataset: VectorDataset, table: String): Boolean =
        rows(dataset, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ${quote(table)}").isNotEmpty()

    private fun id(name: String): String = ProjectFormat.identifier(name)

    private fun text(s: String?): String = s?.let(::quote) ?: "NULL"
}
