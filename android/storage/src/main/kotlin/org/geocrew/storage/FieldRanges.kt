package org.geocrew.storage

import java.math.BigDecimal
import org.geocrew.core.edit.ValueRange
import org.geocrew.core.project.ProjectFormat.quote
import org.geocrew.nativelib.VectorDataset

/**
 * Fields' value ranges, kept as GeoPackage keeps them (ADR 0004): a range
 * constraint in gpkg_data_column_constraints, tied to its column in
 * gpkg_data_columns. This is the schema extension, which GDAL reads as a
 * range field domain and QGIS shows. The two tables are made, and the
 * extension registered, with the first range.
 *
 * A range is named after what it holds, "[0, 90]" or "[0, ∞)", so columns
 * with the same range share it and a renamed field leaves no stale name; a
 * range no column uses any more goes. Ranges made elsewhere keep their names.
 *
 * GDAL rewrites a column's row when it renames the column, from what it read
 * when the file was opened: a range set since would be lost, so renames go
 * through [renamingColumn] and [renamingTable], which tie the ranges again.
 */
internal object FieldRanges {
    private const val COLUMNS = "gpkg_data_columns"
    private const val CONSTRAINTS = "gpkg_data_column_constraints"
    private const val SCHEMA = "http://www.geopackage.org/spec/#extension_schema"

    /** The ranges of [layer]'s fields by field name; none when the file has none, or is no GeoPackage. */
    fun of(dataset: VectorDataset, layer: String): Map<String, ValueRange> {
        val found = runCatching {
            if (!made(dataset)) return emptyMap()
            rows(
                dataset,
                "SELECT dc.column_name AS field, c.min AS min, c.min_is_inclusive AS min_in, c.max AS max, c.max_is_inclusive AS max_in " +
                    "FROM $COLUMNS dc JOIN $CONSTRAINTS c ON c.constraint_name = dc.constraint_name AND c.constraint_type = 'range' " +
                    "WHERE lower(dc.table_name) = lower(${quote(layer)})",
            )
        }.getOrDefault(emptyList())
        return found.mapNotNull { row -> row["field"]?.let { field -> rangeOf(row)?.let { field to it } } }.toMap()
    }

    /** Gives field [field] of [layer] [range], or with null none; a range left unused goes. */
    fun set(dataset: VectorDataset, layer: String, field: String, range: ValueRange?) {
        if (range == null && !made(dataset)) return
        make(dataset)
        val where = "table_name = ${quote(layer)} AND column_name = ${quote(field)}"
        val old = tiedTo(dataset, layer, field)
        dataset.sql("UPDATE $COLUMNS SET constraint_name = NULL WHERE $where")
        old?.let { dropUnused(dataset, it) }
        if (range != null) {
            tie(dataset, layer, field, named(dataset, range))
        } else {
            // A row left with nothing in it goes.
            dataset.sql(
                "DELETE FROM $COLUMNS WHERE $where AND name IS NULL AND title IS NULL AND description IS NULL AND mime_type IS NULL AND constraint_name IS NULL",
            )
        }
    }

    /** Renames field [from] of [layer] to [to] by [rename], its range staying with it. */
    fun renamingColumn(dataset: VectorDataset, layer: String, from: String, to: String, rename: () -> Unit) {
        val name = if (made(dataset)) tiedTo(dataset, layer, from) else null
        rename()
        if (!made(dataset)) return
        dataset.sql("UPDATE $COLUMNS SET column_name = ${quote(to)} WHERE table_name = ${quote(layer)} AND column_name = ${quote(from)}")
        name?.let { tie(dataset, layer, to, it) }
    }

    /** Field [field] of [layer] is about to go: its row goes, and its range if no other column uses it. */
    fun columnDropped(dataset: VectorDataset, layer: String, field: String) {
        if (!made(dataset)) return
        val name = tiedTo(dataset, layer, field)
        dataset.sql("DELETE FROM $COLUMNS WHERE table_name = ${quote(layer)} AND column_name = ${quote(field)}")
        name?.let { dropUnused(dataset, it) }
    }

    /** Renames layer [from] to [to] by [rename], its fields' ranges staying with them. */
    fun renamingTable(dataset: VectorDataset, from: String, to: String, rename: () -> Unit) {
        val tied = if (made(dataset)) {
            rows(dataset, "SELECT column_name, constraint_name FROM $COLUMNS WHERE table_name = ${quote(from)} AND constraint_name IS NOT NULL")
        } else {
            emptyList()
        }
        rename()
        if (!made(dataset)) return
        dataset.sql("UPDATE $COLUMNS SET table_name = ${quote(to)} WHERE table_name = ${quote(from)}")
        for (row in tied) tie(dataset, to, row["column_name"] ?: continue, row["constraint_name"] ?: continue)
    }

    /** Layer [layer] is about to go: its rows go, and the ranges no other layer uses. */
    fun tableDropped(dataset: VectorDataset, layer: String) {
        if (!made(dataset)) return
        val names = rows(dataset, "SELECT DISTINCT constraint_name FROM $COLUMNS WHERE table_name = ${quote(layer)} AND constraint_name IS NOT NULL")
            .mapNotNull { it["constraint_name"] }
        dataset.sql("DELETE FROM $COLUMNS WHERE table_name = ${quote(layer)}")
        names.forEach { dropUnused(dataset, it) }
    }

    // The constraint field [field] of [layer] is tied to, if any.
    private fun tiedTo(dataset: VectorDataset, layer: String, field: String): String? =
        rows(dataset, "SELECT constraint_name FROM $COLUMNS WHERE table_name = ${quote(layer)} AND column_name = ${quote(field)}")
            .firstOrNull()?.get("constraint_name")

    // Ties field [field] of [layer] to constraint [name], making its row when there is none.
    private fun tie(dataset: VectorDataset, layer: String, field: String, name: String) {
        val where = "table_name = ${quote(layer)} AND column_name = ${quote(field)}"
        val row = rows(dataset, "SELECT constraint_name FROM $COLUMNS WHERE $where")
        when {
            row.isEmpty() -> dataset.sql(
                "INSERT INTO $COLUMNS (table_name, column_name, constraint_name) VALUES (${quote(layer)}, ${quote(field)}, ${quote(name)})",
            )
            row.single()["constraint_name"] != name -> dataset.sql("UPDATE $COLUMNS SET constraint_name = ${quote(name)} WHERE $where")
        }
    }

    private fun made(dataset: VectorDataset): Boolean =
        rows(dataset, "SELECT count(*) AS n FROM sqlite_master WHERE type = 'table' AND name IN ('$COLUMNS', '$CONSTRAINTS')").single()["n"] == "2"

    // The tables as the GeoPackage standard (and GDAL) write them, registered as the schema extension.
    private fun make(dataset: VectorDataset) {
        if (made(dataset)) return
        dataset.sql(
            "CREATE TABLE IF NOT EXISTS gpkg_extensions (table_name TEXT, column_name TEXT, extension_name TEXT NOT NULL, " +
                "definition TEXT NOT NULL, scope TEXT NOT NULL, CONSTRAINT ge_tce UNIQUE (table_name, column_name, extension_name))",
        )
        dataset.sql(
            "CREATE TABLE IF NOT EXISTS $COLUMNS (table_name TEXT NOT NULL, column_name TEXT NOT NULL, name TEXT, title TEXT, " +
                "description TEXT, mime_type TEXT, constraint_name TEXT, CONSTRAINT pk_gdc PRIMARY KEY (table_name, column_name), " +
                "CONSTRAINT gdc_tn UNIQUE (table_name, name))",
        )
        dataset.sql(
            "CREATE TABLE IF NOT EXISTS $CONSTRAINTS (constraint_name TEXT NOT NULL, constraint_type TEXT NOT NULL, value TEXT, " +
                "min NUMERIC, min_is_inclusive BOOLEAN, max NUMERIC, max_is_inclusive BOOLEAN, description TEXT, " +
                "CONSTRAINT gdcc_ntv UNIQUE (constraint_name, constraint_type, value))",
        )
        for (table in listOf(COLUMNS, CONSTRAINTS)) {
            dataset.sql(
                "INSERT INTO gpkg_extensions (table_name, column_name, extension_name, definition, scope) " +
                    "SELECT '$table', NULL, 'gpkg_schema', '$SCHEMA', 'read-write' " +
                    "WHERE NOT EXISTS (SELECT 1 FROM gpkg_extensions WHERE table_name = '$table' AND extension_name = 'gpkg_schema')",
            )
        }
    }

    // The name of a range constraint holding [range]: the one already there, or a new one.
    private fun named(dataset: VectorDataset, range: ValueRange): String {
        val base = label(range)
        var name = base
        var n = 1
        while (true) {
            val found = rows(
                dataset,
                "SELECT constraint_type, min, min_is_inclusive AS min_in, max, max_is_inclusive AS max_in FROM $CONSTRAINTS WHERE constraint_name = ${quote(name)}",
            )
            if (found.isEmpty()) {
                dataset.sql(
                    "INSERT INTO $CONSTRAINTS (constraint_name, constraint_type, value, min, min_is_inclusive, max, max_is_inclusive, description) " +
                        "VALUES (${quote(name)}, 'range', NULL, ${number(range.min)}, ${flag(range.min, range.minIncluded)}, " +
                        "${number(range.max)}, ${flag(range.max, range.maxIncluded)}, NULL)",
                )
                return name
            }
            // A name made elsewhere for something else: the next one along.
            if (found.size == 1 && found.single()["constraint_type"] == "range" && rangeOf(found.single()) == range) return name
            name = "$base ${++n}"
        }
    }

    private fun dropUnused(dataset: VectorDataset, name: String) {
        dataset.sql(
            "DELETE FROM $CONSTRAINTS WHERE constraint_name = ${quote(name)} AND constraint_type = 'range' " +
                "AND NOT EXISTS (SELECT 1 FROM $COLUMNS WHERE constraint_name = ${quote(name)})",
        )
    }

    // A row's range; null for one that is no range (no ends, or the wrong way round).
    private fun rangeOf(row: Map<String, String?>): ValueRange? {
        fun included(v: String?) = v != "0" && v != "false"
        return runCatching {
            ValueRange(row["min"]?.toDoubleOrNull(), row["max"]?.toDoubleOrNull(), included(row["min_in"]), included(row["max_in"]))
        }.getOrNull()
    }

    // As intervals are written: brackets for ends in the range, ∞ for open ones.
    private fun label(range: ValueRange): String {
        val low = range.min?.let(::plain) ?: "-∞"
        val high = range.max?.let(::plain) ?: "∞"
        return (if (range.min != null && range.minIncluded) "[" else "(") + "$low, $high" + (if (range.max != null && range.maxIncluded) "]" else ")")
    }

    private fun plain(x: Double): String = BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()

    private fun number(x: Double?): String = x?.toString() ?: "NULL"

    private fun flag(end: Double?, included: Boolean): String = if (end == null) "NULL" else if (included) "1" else "0"
}
