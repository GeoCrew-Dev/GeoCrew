package org.geocrew.core.project

/**
 * How a project is laid out (ADR 0004): a folder `工程/<name>/` under the GeoCrew
 * folder, holding `<name>.gpkg` and `附件/`. The GeoPackage keeps GeoCrew's own
 * tables next to the layers: the format version and the project's id, the
 * layers' ids and templates, and a stable id for every field, so renaming a
 * field does not upset merging (ADR 0008).
 */
object ProjectFormat {
    /** The format this app writes. It opens this one and older ones, never newer. */
    const val VERSION = 1

    const val PROJECTS = "工程"
    const val ATTACHMENTS = "附件"

    const val PROJECT_TABLE = "geocrew_project"
    const val LAYERS_TABLE = "geocrew_layers"
    const val FIELDS_TABLE = "geocrew_fields"

    /**
     * How each layer shows on the map: its place in the layer list (the first
     * drawn on top), whether it shows and how opaque it is. Basemaps have rows
     * too, as basemap:<id>. Made when first needed, so older projects open
     * without being written to.
     */
    const val DISPLAY_TABLE = "geocrew_layer_display"
    const val DISPLAY_SCHEMA = "CREATE TABLE IF NOT EXISTS $DISPLAY_TABLE " +
        "(layer_id TEXT PRIMARY KEY NOT NULL, sort INTEGER, visible INTEGER NOT NULL DEFAULT 1, opacity REAL NOT NULL DEFAULT 1)"

    /**
     * Saved map views, by name: where the map looked (WGS84 / CGCS2000), how far
     * in and how turned. Made when the first one is saved.
     */
    const val BOOKMARKS_TABLE = "geocrew_bookmarks"
    const val BOOKMARKS_SCHEMA = "CREATE TABLE IF NOT EXISTS $BOOKMARKS_TABLE " +
        "(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, longitude REAL NOT NULL, latitude REAL NOT NULL, " +
        "zoom REAL NOT NULL, bearing REAL NOT NULL DEFAULT 0, created TEXT NOT NULL)"

    /** The statements that make GeoCrew's own tables in a new project. */
    val SCHEMA = listOf(
        "CREATE TABLE $PROJECT_TABLE (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)",
        "CREATE TABLE $LAYERS_TABLE (table_name TEXT PRIMARY KEY NOT NULL, id TEXT NOT NULL UNIQUE, template TEXT)",
        "CREATE TABLE $FIELDS_TABLE (table_name TEXT NOT NULL, column_name TEXT NOT NULL, id TEXT NOT NULL UNIQUE, " +
            "PRIMARY KEY (table_name, column_name))",
    )

    /** [s] as an SQL string literal. */
    fun quote(s: String): String = "'" + s.replace("'", "''") + "'"

    /** [s] as an SQL identifier. */
    fun identifier(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    /** The longest project name. */
    const val MAX_NAME = 64

    /** Why a name cannot name a project folder; [message] is for programs and logs. */
    enum class NameProblem(val message: String) {
        EMPTY("the name is empty"),
        SPACE("the name starts or ends with a space"),
        TOO_LONG("the name is longer than $MAX_NAME characters"),
        DOT("the name cannot end with a dot"),
        CHARACTER("the name has a character folders cannot have: \\ / : * ? \" < > |"),
    }

    /**
     * Why [name] cannot name a project folder, or null when it can. The rules are
     * those of FAT and exFAT memory cards, which are the strictest.
     */
    fun nameProblem(name: String): NameProblem? = when {
        name.isBlank() -> NameProblem.EMPTY
        name != name.trim() -> NameProblem.SPACE
        name.length > MAX_NAME -> NameProblem.TOO_LONG
        name.endsWith(".") -> NameProblem.DOT
        name.any { it < ' ' || it == '\u007f' || it in "\\/:*?\"<>|" } -> NameProblem.CHARACTER
        else -> null
    }
}
