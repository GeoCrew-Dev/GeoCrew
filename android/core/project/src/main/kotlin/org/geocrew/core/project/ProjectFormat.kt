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

    /** The folder in a project's folder where its daily snapshots go (ADR 0004), named by their dates. */
    const val SNAPSHOTS = "快照"

    const val PROJECT_TABLE = "geocrew_project"
    const val LAYERS_TABLE = "geocrew_layers"
    const val FIELDS_TABLE = "geocrew_fields"

    /**
     * How each layer shows on the map: its place in the layer list (the first
     * drawn on top) among the nodes beside it, whether it shows and how opaque
     * it is, and the group it is in ([parent], group:<id>; none at the top).
     * Basemaps and groups have rows too, as basemap:<id> and group:<id>. Made
     * when first needed, so older projects open without being written to; the
     * parent column is added when a layer first goes into a group.
     */
    const val DISPLAY_TABLE = "geocrew_layer_display"
    const val DISPLAY_SCHEMA = "CREATE TABLE IF NOT EXISTS $DISPLAY_TABLE " +
        "(layer_id TEXT PRIMARY KEY NOT NULL, sort INTEGER, visible INTEGER NOT NULL DEFAULT 1, opacity REAL NOT NULL DEFAULT 1, parent TEXT)"
    const val DISPLAY_PARENT = "ALTER TABLE $DISPLAY_TABLE ADD COLUMN parent TEXT"

    /**
     * Groups in the layer list (ADR 0017), like the display kept on this phone
     * and not synced: a stable id, a name, and whether the group is a vector
     * layer, whose points, lines and areas are tables of their own. Made when
     * the first group is made.
     */
    const val GROUPS_TABLE = "geocrew_layer_groups"
    const val GROUPS_SCHEMA = "CREATE TABLE IF NOT EXISTS $GROUPS_TABLE (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, vector INTEGER NOT NULL DEFAULT 0)"

    /**
     * How each layer is drawn (ADR 0006), as JSON; made when a style is first
     * set, a layer without one drawn in a colour by when it was made. v1 has
     * one symbol per layer: {"kind": "single", "color": "#rrggbb" (or "#rrggbbaa" with its opacity), "size": a
     * point's radius in dp, "width": a line's width in dp, "fill": an area's
     * fill opacity from 0 to 1}. A categorized style is {"kind":
     * "categorized", the same keys, "field": a field of the layer, "classes":
     * [{"value", "color"}]}: features whose field has one of the values take
     * its colour, the rest the style's colour. A graduated style is {"kind":
     * "graduated", the same keys, "field": a number field, "ranges": [{"from",
     * "to", "color"}]}: a value from "from" up to "to" (the last range takes
     * "to" too) takes the range's colour. Any style may have "label": the
     * field whose values label the features, empty for none (without it, the
     * name field). Rule-based styles come as another kind.
     */
    const val STYLE_TABLE = "geocrew_layer_style"
    const val STYLE_SCHEMA = "CREATE TABLE IF NOT EXISTS $STYLE_TABLE (layer_id TEXT PRIMARY KEY NOT NULL, style TEXT NOT NULL)"

    /**
     * Rasters shown under the layers (GeoTIFF and the like), kept where they
     * are, as QGIS keeps them, and not synced with the team: each with a
     * stable id, a name in the project, its path (from the project's folder
     * when inside it), when it was added, and what the probe made of it (ADR
     * 0003) as JSON. Whether one shows and how opaque it is go in the display
     * table, by id. Made when the first raster is added.
     */
    const val RASTERS_TABLE = "geocrew_rasters"
    const val RASTERS_SCHEMA = "CREATE TABLE IF NOT EXISTS $RASTERS_TABLE " +
        "(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL UNIQUE, path TEXT NOT NULL, added TEXT NOT NULL, info TEXT NOT NULL)"

    /**
     * Saved map views, by name: where the map looked (WGS84 / CGCS2000), how far
     * in and how turned. Made when the first one is saved.
     */
    const val BOOKMARKS_TABLE = "geocrew_bookmarks"
    const val BOOKMARKS_SCHEMA = "CREATE TABLE IF NOT EXISTS $BOOKMARKS_TABLE " +
        "(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, longitude REAL NOT NULL, latitude REAL NOT NULL, " +
        "zoom REAL NOT NULL, bearing REAL NOT NULL DEFAULT 0, created TEXT NOT NULL)"

    /**
     * A linked project's rows (protocol v1 6.1, ADR 0008): each feature's row id
     * (a UUID) and the server sequence number it was last seen at, 0 until the
     * server has it. Made by the first save of a linked project.
     */
    const val ROWS_TABLE = "geocrew_rows"
    const val ROWS_SCHEMA = "CREATE TABLE IF NOT EXISTS $ROWS_TABLE " +
        "(layer_id TEXT NOT NULL, fid INTEGER NOT NULL, uuid TEXT NOT NULL UNIQUE, seq INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (layer_id, fid))"

    /**
     * What a linked project has to send its workspace (protocol v1 8.4): each
     * field edited since it was last sent, by row and field id ("geom" the
     * geometry, "deleted" a deletion), with its value as JSON and when it was
     * edited (UTC, the phone's clock); the last edit of a field kept.
     */
    const val OUTBOX_TABLE = "geocrew_outbox"
    const val OUTBOX_SCHEMA = "CREATE TABLE IF NOT EXISTS $OUTBOX_TABLE " +
        "(row TEXT NOT NULL, layer_id TEXT NOT NULL, field TEXT NOT NULL, value TEXT NOT NULL, edited_at TEXT NOT NULL, PRIMARY KEY (row, field))"

    /**
     * Changes on their way to a linked project's workspace (protocol v1 8.1,
     * 8.4): the edits of a row taken from [OUTBOX_TABLE] under one change id,
     * which a retry sends again as they are, the row's base seq, the change as
     * JSON ({set, delete}) and, once the server refused it, why. An edit made
     * while its change is on its way goes to the outbox again.
     */
    const val SENDING_TABLE = "geocrew_sending"
    const val SENDING_SCHEMA = "CREATE TABLE IF NOT EXISTS $SENDING_TABLE " +
        "(change TEXT PRIMARY KEY NOT NULL, row TEXT NOT NULL, layer_id TEXT NOT NULL, base_seq INTEGER NOT NULL, body TEXT NOT NULL, rejected TEXT)"

    /**
     * A linked project's conflict records (protocol v1 6.5, 8.2), as pulls
     * bring them: the row and field, what kind, the value kept and the one
     * lost (JSON, with who set it, when and at which seq), and whether someone
     * resolved it. The page that lists them reads this table.
     */
    const val CONFLICTS_TABLE = "geocrew_conflicts"
    const val CONFLICTS_SCHEMA = "CREATE TABLE IF NOT EXISTS $CONFLICTS_TABLE " +
        "(id TEXT PRIMARY KEY NOT NULL, layer_id TEXT NOT NULL, row TEXT NOT NULL, field TEXT, kind TEXT NOT NULL, kept TEXT, lost TEXT, " +
        "resolved INTEGER NOT NULL DEFAULT 0)"

    /** The project's key in its own table for the seq its last pull reached (protocol v1 8.2): where the next one starts. */
    const val PULLED_KEY = "pulled_seq"

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
