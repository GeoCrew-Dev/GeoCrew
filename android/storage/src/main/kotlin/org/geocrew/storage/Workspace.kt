package org.geocrew.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.action.Action
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.ActionSpec
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Risk
import org.geocrew.core.action.Stability
import org.geocrew.core.edit.Editing
import org.geocrew.core.edit.Field
import org.geocrew.core.edit.FieldType
import org.geocrew.core.edit.ValueRange
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.Templates

/**
 * The project the app has open, with the actions that list, create, open and
 * close projects and make their layers (ADR 0010). The open project's layers are edited through
 * [editing]; a project closes only when none of its layers is being edited.
 */
class Workspace(private val projects: Projects) {
    /** The open project, if any. Read it inside an action, while no other runs. */
    var current: Project? = null
        private set

    // One undo history for the whole project, as its save covers every layer.
    // Features move between layers of different CRSs through PROJ.
    val editing = Editing(sharedScope = PROJECT_SCOPE, reproject = { g, from, to -> Crs.transform(listOf(g), from, to).single() }) { layer ->
        checkNotNull(current) { "no project is open" }.target(layer)
    }

    /** The project actions and the editing actions, for the registry. */
    val actions: List<Action> = listOf(
        WorkspaceAction(
            LIST, "List projects", "The projects in the GeoCrew folder, latest change first, with their layers, features, size and age",
            Risk.VIEW, NO_PARAMS, "{}",
        ) {
            val listed = projects.list().map { it to projects.modified(it) }.sortedByDescending { it.second }
            ActionResult.Done(
                buildJsonObject {
                    putJsonArray("projects") {
                        for ((name, modified) in listed) {
                            val summary = projects.summary(name)
                            addJsonObject {
                                put("name", name)
                                put("modified", modified)
                                if (summary != null) {
                                    put("layers", summary.layers)
                                    put("features", summary.features)
                                    put("points", summary.points)
                                    put("lines", summary.lines)
                                    put("areas", summary.areas)
                                    put("bytes", summary.bytes)
                                    summary.created?.let { put("created", it) }
                                }
                            }
                        }
                    }
                },
            )
        },
        WorkspaceAction(
            CREATE, "Create project", "Makes a project with the built-in layers, named in the language given (zh or en), and opens it",
            Risk.EDIT, CREATE_PARAMS, """{"name": "测区一号", "language": "zh"}""",
        ) { p ->
            val language = (p["language"] as? JsonPrimitive)?.contentOrNull ?: "en"
            switchTo { projects.create(p.name(), language = language) }
        },
        WorkspaceAction(OPEN, "Open project", "Opens a project, closing the one that is open", Risk.VIEW, NAME_PARAMS, """{"name": "测区一号"}""") { p ->
            switchTo { projects.open(p.name()) }
        },
        WorkspaceAction(CURRENT, "Current project", "The open project and its layers; an empty object when none is open", Risk.VIEW, NO_PARAMS, "{}") {
            ActionResult.Done(current?.let(::about) ?: JsonObject(emptyMap()))
        },
        WorkspaceAction(
            FEATURES, "Layer features",
            "A layer of the open project as GeoJSON, as edited, with its fields and whether it is being edited; " +
                "geometries in the layer's CRS, or in srs when given (as the map wants EPSG:4490)",
            Risk.VIEW, FEATURES_PARAMS, """{"layer": "野外点", "srs": "EPSG:4490"}""",
        ) { p ->
            val layer = p.getValue("layer").jsonPrimitive.content
            val project = checkNotNull(current) { "no project is open" }
            val store = project.target(layer).store
            val features = editing.features(layer) ?: store.features()
            val own = project.layers.firstOrNull { it.name == layer }?.srs
            val wanted = (p["srs"] as? JsonPrimitive)?.contentOrNull
            val geometries = if (wanted == null || own == null) features.map { it.geometry } else Crs.transform(features.map { it.geometry }, own, wanted)
            ActionResult.Done(
                buildJsonObject {
                    put("type", "FeatureCollection")
                    put("srs", if (wanted != null && own != null) wanted else own)
                    putJsonArray("features") {
                        for ((f, geometry) in features.zip(geometries)) {
                            addJsonObject {
                                put("type", "Feature")
                                put("id", f.fid)
                                put("geometry", geometry ?: JsonNull)
                                put("properties", f.properties)
                            }
                        }
                    }
                    putJsonArray("fields") { for (f in store.fields) add(fieldJson(f)) }
                    put("editing", layer in editing.layersBeingEdited)
                    put("modified", editing.isModified(layer))
                    put("projectModified", editing.layersBeingEdited.any(editing::isModified))
                },
            )
        },
        WorkspaceAction(
            EXPORT, "Export project",
            "Writes the open project's saved data, or one layer of it, to a new file in the export folder: GeoPackage, Shapefile (zip), " +
                "KML, KMZ with the photos, GeoJSON, CSV, Excel, GPX (points and lines) or DXF; a project's layers in a format of one layer go in a zip. " +
                "language (zh or en) names the coordinate columns of CSV and Excel",
            Risk.SEND, EXPORT_PARAMS, """{"format": "kml"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            unsaved()?.let { return@WorkspaceAction it }
            val format = ExportFormat.of(p.getValue("format").jsonPrimitive.content)
            val layer = (p["layer"] as? JsonPrimitive)?.contentOrNull
            val language = (p["language"] as? JsonPrimitive)?.contentOrNull ?: "en"
            val layers = if (layer == null) project.layers else listOf(requireNotNull(project.layers.firstOrNull { it.name == layer }) { "the project has no layer $layer" })
            // GDAL makes nothing of a GeoPackage without layers, as a new project is.
            if (format != ExportFormat.GPKG && project.layers.isEmpty()) return@WorkspaceAction ActionResult.Failed("empty", "the project has no layers")
            if (format == ExportFormat.GPX && layers.none(Gpx(project)::holds)) {
                return@WorkspaceAction ActionResult.Failed("unsupported", "GPX holds points and lines only")
            }
            val file = Exports(projects.exports).export(project, format, layer, language)
            ActionResult.Done(buildJsonObject { put("path", file.path) })
        },
        WorkspaceAction(
            FIELD_ADD, "Add field", "Adds a field to a layer of the open project; no layer may have unsaved edits",
            Risk.EDIT, FIELD_PARAMS, """{"layer": "野外点", "name": "水位埋深", "type": "real"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val layer = p.getValue("layer").jsonPrimitive.content
            val name = p.getValue("name").jsonPrimitive.content.trim()
            val type = FieldType.of(p.getValue("type").jsonPrimitive.content)
            unsaved()?.let { return@WorkspaceAction it }
            val existing = project.target(layer).store.fields.map { it.name }
            require(name.isNotEmpty() && name.length <= ProjectFormat.MAX_NAME) { "a field name has 1 to ${ProjectFormat.MAX_NAME} characters" }
            require(existing.none { it.equals(name, ignoreCase = true) } && RESERVED.none { it.equals(name, ignoreCase = true) }) {
                "the layer already has a field $name"
            }
            val ended = changeFields(project, layer) { project.addField(layer, name, type) }
            ActionResult.Done(buildJsonObject { put("layer", layer); put("field", name) }, endsUndo = ended)
        },
        WorkspaceAction(
            FIELD_RENAME, "Rename field",
            "Renames a field of a layer of the open project; its values stay, and the layer's style and label follow it. No layer may have unsaved edits",
            Risk.EDIT, FIELD_RENAME_PARAMS, """{"layer": "野外点", "field": "描述", "to": "备注"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val layer = p.getValue("layer").jsonPrimitive.content
            val field = p.getValue("field").jsonPrimitive.content
            val to = p.getValue("to").jsonPrimitive.content.trim()
            unsaved()?.let { return@WorkspaceAction it }
            project.checkFieldRename(layer, field, to)
            val ended = changeFields(project, layer) { project.renameField(layer, field, to) }
            ActionResult.Done(buildJsonObject { put("layer", layer); put("field", to) }, endsUndo = ended)
        },
        WorkspaceAction(
            FIELD_DELETE, "Delete field",
            "Deletes a field of a layer of the open project with every value in it; it cannot be undone. No layer may have unsaved edits",
            Risk.DELETE, FIELD_DELETE_PARAMS, """{"layer": "野外点", "field": "备注"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val layer = p.getValue("layer").jsonPrimitive.content
            val field = p.getValue("field").jsonPrimitive.content
            unsaved()?.let { return@WorkspaceAction it }
            project.checkFieldDelete(layer, field)
            val ended = changeFields(project, layer) { project.deleteField(layer, field) }
            ActionResult.Done(buildJsonObject { put("layer", layer); put("field", field) }, endsUndo = ended)
        },
        WorkspaceAction(
            FIELD_RANGE, "Set field range",
            "Lets a number field of a layer of the open project take only the numbers from min to max, both included; " +
                "with neither, any number. Kept as a GeoPackage range constraint; values already there stay",
            Risk.EDIT, FIELD_RANGE_PARAMS, """{"layer": "野外点", "field": "倾角", "min": 0, "max": 90}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val layer = p.getValue("layer").jsonPrimitive.content
            val field = p.getValue("field").jsonPrimitive.content
            val min = (p["min"] as? JsonPrimitive)?.doubleOrNull
            val max = (p["max"] as? JsonPrimitive)?.doubleOrNull
            val range = if (min == null && max == null) null else ValueRange(min, max)
            project.setFieldRange(layer, field, range)
            ActionResult.Done(buildJsonObject { put("layer", layer); put("field", field); put("range", range?.toJson() ?: JsonNull) })
        },
        WorkspaceAction(
            LAYER_INFO, "Layer info", "A layer of the open project: its fields with their types and ranges, and how many features it has, as edited",
            Risk.VIEW, LAYER_PARAMS, """{"layer": "野外点"}""",
        ) { p ->
            val layer = p.getValue("layer").jsonPrimitive.content
            val project = checkNotNull(current) { "no project is open" }
            val store = project.target(layer).store
            ActionResult.Done(
                buildJsonObject {
                    put("name", layer)
                    put("template", project.layers.firstOrNull { it.name == layer }?.template)
                    putJsonArray("fields") { for (f in store.fields) add(fieldJson(f)) }
                    put("count", editing.features(layer)?.size?.toLong() ?: project.count(layer))
                },
            )
        },
        WorkspaceAction(
            LAYER_CREATE, "Create layer",
            "Adds a layer to the open project: like another layer (its fields, geometry type and CRS) or from a template " +
                "(field-point, field-line, field-polygon: a name and a description; track: those and when it started and ended, " +
                "its length in metres), its fields named in the language given",
            Risk.EDIT, LAYER_CREATE_PARAMS, """{"name": "野外点", "language": "zh"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val like = (p["like"] as? JsonPrimitive)?.contentOrNull
            if (like != null) {
                project.createLayerLike(p.name(), like)
            } else {
                val id = (p["template"] as? JsonPrimitive)?.contentOrNull ?: Templates.FIELD_POINT.id
                val template = requireNotNull(Templates.all.firstOrNull { it.id == id }) { "there is no template $id" }
                val srs = (p["srs"] as? JsonPrimitive)?.contentOrNull
                project.createLayer(p.name(), template, (p["language"] as? JsonPrimitive)?.contentOrNull ?: "en", srs)
            }
            ActionResult.Done(about(project))
        },
        WorkspaceAction(
            LAYER_RENAME, "Rename layer", "Renames a layer of the open project; no layer may have unsaved edits",
            Risk.EDIT, RENAME_PARAMS, """{"layer": "野外点", "to": "泉点"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            unsaved()?.let { return@WorkspaceAction it }
            // The file is opened again: every layer stops being edited, with nothing unsaved.
            val ended = stopEditing() + editing.scopeOf(p.getValue("layer").jsonPrimitive.content)
            project.renameLayer(p.getValue("layer").jsonPrimitive.content, p.getValue("to").jsonPrimitive.content)
            current = reopen(project)
            ActionResult.Done(about(current!!), endsUndo = ended.distinct())
        },
        WorkspaceAction(
            LAYER_DELETE, "Delete layer", "Deletes a layer of the open project with all its features; it cannot be undone. No layer may have unsaved edits",
            Risk.DELETE, LAYER_PARAMS, """{"layer": "泉点"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            unsaved()?.let { return@WorkspaceAction it }
            val ended = stopEditing() + editing.scopeOf(p.getValue("layer").jsonPrimitive.content)
            project.deleteLayer(p.getValue("layer").jsonPrimitive.content)
            current = reopen(project)
            ActionResult.Done(about(current!!), endsUndo = ended.distinct())
        },
        WorkspaceAction(
            LAYER_DISPLAY, "Layer display", "Shows or hides a layer of the open project and sets its opacity (0 to 1); saved in the project at once, not undone",
            Risk.EDIT, DISPLAY_PARAMS, """{"layer": "野外点", "visible": false}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            project.setDisplay(layer = p.getValue("layer").jsonPrimitive.content, visible = p.flag("visible"), opacity = p.number("opacity"))
            ActionResult.Done(about(project))
        },
        WorkspaceAction(
            LAYER_STYLE, "Layer style",
            "Sets how a layer of the open project is drawn (ADR 0006): one colour (#rrggbb, or #rrggbbaa with its opacity), a point's size and a line's width in dp " +
                "(size 1 to 30, width 0.5 to 20), an area's fill opacity (0 to 1); what is left out takes its default, an empty style " +
                "goes back to the default. Saved in the project at once, not undone",
            Risk.EDIT, STYLE_PARAMS, """{"layer": "野外点", "style": {"color": "#1e88e5", "size": 8}}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val layer = p.getValue("layer").jsonPrimitive.content
            project.setStyle(layer, p.getValue("style").jsonObject)
            // To the team as well, for a linked project (protocol v1 7.1).
            project.layers.firstOrNull { it.name == layer }?.id?.let { id -> project.data { Outbox.recordLayer(it, id, Outbox.STYLE, project.styles[id] ?: JsonNull) } }
            ActionResult.Done(about(project))
        },
        WorkspaceAction(
            BASEMAP_DISPLAY, "Basemap display", "Shows or hides a basemap under the layers (img: imagery, cia: its annotation) and sets its opacity",
            Risk.EDIT, BASEMAP_PARAMS, """{"basemap": "img", "opacity": 0.6}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            project.setDisplay(basemap = p.getValue("basemap").jsonPrimitive.content, visible = p.flag("visible"), opacity = p.number("opacity"))
            ActionResult.Done(about(project))
        },
        WorkspaceAction(BOOKMARKS, "Bookmarks", "The open project's saved map views, oldest first", Risk.VIEW, NO_PARAMS, "{}") {
            ActionResult.Done(bookmarksOf(checkNotNull(current) { "no project is open" }))
        },
        WorkspaceAction(
            BOOKMARK_ADD, "Add bookmark", "Saves a map view of the open project by name: its centre in degrees, zoom and bearing; saved at once, not undone",
            Risk.EDIT, BOOKMARK_ADD_PARAMS, """{"name": "一号剖面", "longitude": 114.3, "latitude": 30.6, "zoom": 15}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            project.addBookmark(
                p.getValue("name").jsonPrimitive.content, p.number("longitude")!!, p.number("latitude")!!, p.number("zoom")!!, p.number("bearing") ?: 0.0,
            )
            ActionResult.Done(bookmarksOf(project))
        },
        WorkspaceAction(
            BOOKMARK_DELETE, "Delete bookmark", "Deletes a saved map view of the open project; it cannot be undone",
            Risk.DELETE, BOOKMARK_DELETE_PARAMS, """{"id": 1}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            project.deleteBookmark(p.getValue("id").jsonPrimitive.content.toLong())
            ActionResult.Done(bookmarksOf(project))
        },
        WorkspaceAction(
            LAYER_ORDER, "Layer order", "Puts the open project's layers in the order given, every layer once, the first drawn on top",
            Risk.EDIT, ORDER_PARAMS, """{"layers": ["泉点", "野外点"]}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val before = project.layers.map { it.id }
            project.setOrder(p.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
            sendOrder(project, before)
            ActionResult.Done(about(project))
        },
        WorkspaceAction(
            GROUP_CREATE, "Create layer group",
            "Makes a group in the open project's layer list (ADR 0017), on top, with the layers given in it in that order; vector: a vector " +
                "layer, whose points, lines and areas are tables of their own. Groups stay on this phone; saved at once, not undone",
            Risk.EDIT, GROUP_CREATE_PARAMS, """{"name": "水文", "layers": ["泉点", "水井"]}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val before = project.layers.map { it.id }
            project.createGroup(p.name(), p.flag("vector") ?: false, (p["layers"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty())
            sendOrder(project, before)
            ActionResult.Done(about(project))
        },
        WorkspaceAction(
            LAYER_PART, "Add vector layer part",
            "Adds a part to the vector layer of a layer of the open project (ADR 0017): a table for its points, lines or areas with the layer's fields, " +
                "CRS and look (color: #rrggbb, when the layer has no style of its own), named after the vector layer and the kind in the language given. " +
                "A layer in no vector layer yet becomes one: a group named after it. Saved at once, not undone; gives the part's name as part",
            Risk.EDIT, PART_PARAMS, """{"layer": "断层", "geometry": "POINT", "language": "zh"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val part = project.createPart(
                p.getValue("layer").jsonPrimitive.content,
                p.getValue("geometry").jsonPrimitive.content,
                (p["language"] as? JsonPrimitive)?.contentOrNull ?: "en",
                (p["color"] as? JsonPrimitive)?.contentOrNull,
            )
            ActionResult.Done(JsonObject(about(project) + ("part" to JsonPrimitive(part.name))))
        },
        WorkspaceAction(
            GROUP_RENAME, "Rename layer group", "Renames a group of the open project's layer list; saved at once",
            Risk.EDIT, GROUP_RENAME_PARAMS, """{"group": "…", "to": "水文地质"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            project.renameGroup(p.getValue("group").jsonPrimitive.content, p.getValue("to").jsonPrimitive.content)
            ActionResult.Done(about(project))
        },
        WorkspaceAction(
            GROUP_REMOVE, "Remove layer group", "Takes a group out of the open project's layer list; its layers stay, where it was",
            Risk.EDIT, GROUP_PARAMS, """{"group": "…"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val before = project.layers.map { it.id }
            project.removeGroup(p.getValue("group").jsonPrimitive.content)
            sendOrder(project, before)
            ActionResult.Done(about(project))
        },
        WorkspaceAction(
            GROUP_DISPLAY, "Layer group display", "Shows or hides a group of the open project's layer list, and with it its layers; saved at once",
            Risk.EDIT, GROUP_DISPLAY_PARAMS, """{"group": "…", "visible": false}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            project.setGroupDisplay(p.getValue("group").jsonPrimitive.content, p.flag("visible")!!)
            ActionResult.Done(about(project))
        },
        WorkspaceAction(
            LAYER_MOVE, "Move in layer list",
            "Moves a layer or a group of the open project's layer list to a place (0 first, drawn on top) at the top or in a group " +
                "(into: the group's id; groups always go at the top); saved at once",
            Risk.EDIT, MOVE_PARAMS, """{"layer": "泉点", "into": "…", "index": 0}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            val before = project.layers.map { it.id }
            project.move(
                layer = (p["layer"] as? JsonPrimitive)?.contentOrNull,
                group = (p["group"] as? JsonPrimitive)?.contentOrNull,
                into = (p["into"] as? JsonPrimitive)?.contentOrNull,
                index = p.getValue("index").jsonPrimitive.content.toInt(),
            )
            sendOrder(project, before)
            ActionResult.Done(about(project))
        },
        WorkspaceAction(
            CRS_DESCRIBE, "Describe CRS", "What a CRS (EPSG:4549, WKT, a PROJ string) is: its name, id, whether geographic or projected, and unit",
            Risk.VIEW, CRS_PARAMS, """{"srs": "EPSG:4547"}""",
        ) { p ->
            ActionResult.Done(Crs.describe(p.getValue("srs").jsonPrimitive.content))
        },
        WorkspaceAction(
            CRS_SEARCH, "Search CRSs",
            "CRSs PROJ knows whose name or code has every word of text (Chinese words for China's CRSs work: 西安80, 高斯 3 度带, 38带), " +
                "of the kinds asked for (geographic and projected by default), applying at near [longitude, latitude] when given; " +
                "or, with srs, those CRSs by AUTH:CODE",
            Risk.VIEW, CRS_SEARCH_PARAMS, """{"text": "2000 3度带", "near": [114.3, 30.6]}""",
        ) { p ->
            val found = (p["srs"] as? JsonArray)?.let { list -> CrsCatalog.find(list.map { it.jsonPrimitive.content }) } ?: run {
                val near = (p["near"] as? JsonArray)?.map { it.jsonPrimitive.double }?.let { require(it.size == 2) { "near is [longitude, latitude]" }; it[0] to it[1] }
                val kinds = (p["kinds"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: CrsCatalog.HORIZONTAL
                CrsCatalog.search((p["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(), kinds, near, (p.number("limit") ?: 200.0).toInt())
            }
            ActionResult.Done(
                buildJsonObject {
                    putJsonArray("crs") {
                        for (e in found) addJsonObject {
                            put("srs", e.srs)
                            put("name", e.name)
                            put("type", e.type)
                            e.area?.let { put("area", it) }
                            if (e.deprecated) put("deprecated", true)
                        }
                    }
                },
            )
        },
        WorkspaceAction(
            CRS_TRANSFORM, "Transform coordinates",
            "x, y pairs (longitude or easting first) from one CRS to another; a pair that cannot be transformed comes back as null",
            Risk.VIEW, TRANSFORM_PARAMS, """{"from": "EPSG:4490", "to": "EPSG:4547", "xy": [114.0, 30.0]}""",
        ) { p ->
            val xy = p.getValue("xy").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
            require(xy.size % 2 == 0) { "coordinates come in x, y pairs" }
            val moved = Crs.transform(p.getValue("from").jsonPrimitive.content, p.getValue("to").jsonPrimitive.content, xy)
            ActionResult.Done(buildJsonObject { putJsonArray("xy") { for (v in moved) if (v.isFinite()) add(v) else add(JsonNull) } })
        },
        WorkspaceAction(
            SNAPSHOTS, "Project snapshots", "The days the open project has a daily snapshot of (ADR 0004), newest first",
            Risk.VIEW, NO_PARAMS, "{}",
        ) {
            val project = checkNotNull(current) { "no project is open" }
            ActionResult.Done(buildJsonObject { putJsonArray("days") { project.snapshotDays.forEach { add(it) } } })
        },
        WorkspaceAction(
            RESTORE, "Restore snapshot",
            "Puts the open project back as it was in a daily snapshot (ADR 0004), once no layer has unsaved edits; the project as it was " +
                "goes first into the snapshot folder (kept: its path). Undo cannot take it back; the kept copy can",
            Risk.DELETE, RESTORE_PARAMS, """{"day": "2026-11-27"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            unsaved()?.let { return@WorkspaceAction it }
            val day = p.getValue("day").jsonPrimitive.content
            require(day in project.snapshotDays) { "there is no snapshot $day" }
            val ended = stopEditing()
            val (restored, kept) = runCatching { projects.restore(project, day) }.getOrElse { e ->
                // Closed on the way: the project as it is, opened again.
                if (project.closed) current = runCatching { projects.open(project.name, snapshot = false) }.getOrNull()
                throw e
            }
            current = restored
            ActionResult.Done(JsonObject(about(restored) + ("kept" to JsonPrimitive(kept.path))), endsUndo = ended)
        },
        WorkspaceAction(CLOSE, "Close project", "Closes the open project, once no layer has unsaved edits", Risk.VIEW, NO_PARAMS, "{}") {
            unsaved() ?: run {
                val ended = stopEditing()
                current?.close()
                current = null
                ActionResult.Done(endsUndo = ended)
            }
        },
    ) + editing.actions

    // For a project linked to a team: the layers whose place in the draw order ([before]: ids, the top first) changed go
    // to it with their new place (the merged property order, protocol v1 7.1).
    private fun sendOrder(project: Project, before: List<String>) {
        val after = project.layers.map { it.id }
        if (after == before) return
        project.data { ds ->
            for ((i, id) in after.withIndex()) if (before.indexOf(id) != i) Outbox.recordLayer(ds, id, Outbox.ORDER, JsonPrimitive(i))
        }
    }

    private fun bookmarksOf(project: Project) = buildJsonObject {
        putJsonArray("bookmarks") {
            for (b in project.bookmarks) addJsonObject {
                put("id", b.id)
                put("name", b.name)
                put("longitude", b.longitude)
                put("latitude", b.latitude)
                put("zoom", b.zoom)
                put("bearing", b.bearing)
            }
        }
    }

    /**
     * Makes a new project named after [wanted] (characters no name takes as
     * "_", numbered when taken) and opens it, closing the open one: for joining
     * a team's workspace (SyncActions). Fails, leaving the open project as it
     * is, while that has unsaved edits.
     */
    internal fun createAndOpen(wanted: String, language: String): ActionResult {
        val taken = projects.list().map { it.lowercase() }.toSet()
        val base = wanted.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(ProjectFormat.MAX_NAME - 4).trim().ifEmpty { "workspace" }
        val name = generateSequence(1) { it + 1 }.take(1000).map { if (it == 1) base else "$base $it" }
            .firstOrNull { it.lowercase() !in taken && ProjectFormat.nameProblem(it) == null }
            ?: return ActionResult.Failed("bad_name", "no project name can be made of $wanted")
        return switchTo { projects.create(name, language = language) }
    }

    // The project's file opened again, as GDAL learns of changed tables when it opens the file; closed if that fails.
    private fun reopen(project: Project): Project = runCatching { projects.reopen(project) }.getOrElse {
        current = null
        throw it
    }

    // The open project stays open until the next one is: a failure leaves it as it was.
    private fun switchTo(open: () -> Project): ActionResult {
        unsaved()?.let { return it }
        val next = open()
        val ended = stopEditing()
        current?.close()
        current = next
        return ActionResult.Done(about(next), endsUndo = ended)
    }

    // A field as actions give it: its name, its type, and its range when it has one.
    private fun fieldJson(f: Field): JsonObject = buildJsonObject {
        put("name", f.name)
        put("type", f.type.name.lowercase())
        f.range?.let { put("range", it.toJson()) }
    }

    // A change to [layer]'s fields: every layer stops being edited, with nothing unsaved, and the project's file is
    // opened again afterwards, failed or not, as GDAL reads a layer's fields when it opens the file. The undo histories that end.
    private fun changeFields(project: Project, layer: String, change: () -> Unit): List<String> {
        val ended = (stopEditing() + editing.scopeOf(layer)).distinct()
        try {
            change()
        } finally {
            current = reopen(project)
        }
        return ended
    }

    // Unsaved edits are saved or discarded before the project's file closes, as QGIS asks.
    private fun unsaved(): ActionResult.Failed? {
        val layers = editing.layersBeingEdited.filter(editing::isModified)
        return if (layers.isEmpty()) null else ActionResult.Failed("unsaved", "save or discard the edits of ${layers.joinToString()} first")
    }

    // Every layer stops being edited, none having anything unsaved; the undo histories that end with it.
    private fun stopEditing(): List<String> {
        val layers = editing.layersBeingEdited
        layers.forEach(editing::release)
        return layers.map(editing::scopeOf).distinct()
    }

    /** The open project as project.current gives it; an empty object when none is open. */
    fun about(): JsonObject = current?.let(::about) ?: JsonObject(emptyMap())

    private fun about(project: Project): JsonObject = buildJsonObject {
        val styles = project.styles
        put("name", project.name)
        put("id", project.id)
        put("folder", project.folder.path)
        // Said once, by whatever first describes the project after it opened.
        project.takeSnapshotFailure()?.let { put("snapshotFailure", it) }
        putJsonArray("layers") {
            for (layer in project.layers) {
                addJsonObject {
                    put("name", layer.name)
                    put("id", layer.id)
                    put("template", layer.template)
                    put("geometry", layer.geometry)
                    put("srs", layer.srs)
                    put("made", layer.made)
                    // Whether it shows on the map (its group shown too), and its own switch.
                    put("visible", project.shows(layer))
                    put("ownVisible", layer.display.visible)
                    put("opacity", layer.display.opacity)
                    layer.display.parent?.let { key -> project.groups.firstOrNull { Project.groupKey(it.id) == key }?.let { put("group", it.id) } }
                    styles[layer.id]?.let { put("style", it) }
                }
            }
        }
        // The layer list as a tree (ADR 0017): at the top in order, a layer by its name or a group by its id with its layers.
        putJsonArray("groups") {
            for (group in project.groups) {
                addJsonObject {
                    put("id", group.id)
                    put("name", group.name)
                    put("vector", group.vector)
                    put("visible", group.display.visible)
                }
            }
        }
        putJsonArray("tree") {
            for ((group, layers) in project.tree) {
                addJsonObject {
                    if (group == null) {
                        put("layer", layers.single().name)
                    } else {
                        put("group", group.id)
                        putJsonArray("layers") { layers.forEach { add(it.name) } }
                    }
                }
            }
        }
        putJsonArray("rasters") {
            for (raster in project.rasters) {
                addJsonObject {
                    put("name", raster.name)
                    put("id", raster.id)
                    put("path", raster.file.path)
                    // A raster whose file has gone (moved, deleted) stays listed, to be found again or taken out.
                    if (!raster.file.exists()) put("missing", true)
                    put("visible", raster.display.visible)
                    put("opacity", raster.display.opacity)
                    put("render", raster.render)
                    put("info", raster.info)
                }
            }
        }
        putJsonArray("basemaps") {
            for ((id, display) in project.basemaps) {
                addJsonObject {
                    put("id", id)
                    put("visible", display.visible)
                    put("opacity", display.opacity)
                }
            }
        }
    }

    private class WorkspaceAction(
        id: String,
        name: String,
        description: String,
        risk: Risk,
        params: JsonObject,
        example: String,
        private val body: (JsonObject) -> ActionResult,
    ) : Action {
        override val spec = ActionSpec(id, name, description, params, risk, Stability.PREVIEW, examples = listOf(schema(example)))

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult = body(params)
    }

    companion object {
        const val LIST = "project.list"
        const val CREATE = "project.create"
        const val OPEN = "project.open"
        const val CLOSE = "project.close"
        const val CURRENT = "project.current"
        const val FEATURES = "layer.features"
        const val EXPORT = "project.export"
        const val FIELD_ADD = "layer.field.add"
        const val FIELD_RENAME = "layer.field.rename"
        const val FIELD_DELETE = "layer.field.delete"
        const val FIELD_RANGE = "layer.field.range"
        const val LAYER_INFO = "layer.info"
        const val LAYER_CREATE = "layer.create"
        const val CRS_DESCRIBE = "crs.describe"
        const val CRS_SEARCH = "crs.search"
        const val LAYER_DISPLAY = "layer.display"
        const val LAYER_RENAME = "layer.rename"
        const val LAYER_DELETE = "layer.delete"
        const val BASEMAP_DISPLAY = "basemap.display"
        const val LAYER_ORDER = "layer.order"
        const val LAYER_MOVE = "layer.move"
        const val SNAPSHOTS = "project.snapshots"
        const val RESTORE = "project.restore"
        const val LAYER_PART = "layer.part.create"
        const val GROUP_CREATE = "layer.group.create"
        const val GROUP_RENAME = "layer.group.rename"
        const val GROUP_REMOVE = "layer.group.remove"
        const val GROUP_DISPLAY = "layer.group.display"
        const val LAYER_STYLE = "layer.style"
        const val BOOKMARKS = "bookmark.list"
        const val BOOKMARK_ADD = "bookmark.add"
        const val BOOKMARK_DELETE = "bookmark.delete"
        const val CRS_TRANSFORM = "crs.transform"

        /** The registry scope of a project's undo history: one for all its layers. */
        const val PROJECT_SCOPE = "project"

        /** Columns every GeoPackage layer has: its feature id and its geometry. */
        val RESERVED = setOf("fid", "geom")

        private fun schema(s: String) = Json.parseToJsonElement(s).jsonObject

        private val NO_PARAMS = schema("""{"type": "object", "additionalProperties": false}""")
        private val LAYER_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer"], "properties": {"layer": {"type": "string"}}}""",
        )
        private val CREATE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["name"],
                "properties": {"name": {"type": "string"}, "language": {"enum": ["zh", "en"]}}}""",
        )
        private val FIELD_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "name", "type"],
                "properties": {"layer": {"type": "string"}, "name": {"type": "string"},
                               "type": {"enum": ["string", "real", "integer", "date", "datetime", "boolean"]}}}""",
        )
        private val FIELD_RENAME_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "field", "to"],
                "properties": {"layer": {"type": "string"}, "field": {"type": "string"}, "to": {"type": "string"}}}""",
        )
        private val FIELD_DELETE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "field"],
                "properties": {"layer": {"type": "string"}, "field": {"type": "string"}}}""",
        )
        private val FIELD_RANGE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "field"],
                "properties": {"layer": {"type": "string"}, "field": {"type": "string"}, "min": {"type": "number"}, "max": {"type": "number"}}}""",
        )
        private val EXPORT_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["format"], "properties": {"format": {"enum": ${ExportFormat.entries.joinToString(prefix = "[", postfix = "]") { "\"${it.id}\"" }}}, """ +
                """"layer": {"type": "string"}, "language": {"enum": ["zh", "en"]}}}""",
        )
        private val LAYER_CREATE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["name"],
                "properties": {"name": {"type": "string"}, "like": {"type": "string"}, "template": {"enum": ["field-point", "field-line", "field-polygon", "track"]},
                               "language": {"enum": ["zh", "en"]}, "srs": {"type": "string"}}}""",
        )
        private val FEATURES_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer"], "properties": {"layer": {"type": "string"}, "srs": {"type": "string"}}}""",
        )
        private val BOOKMARK_ADD_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["name", "longitude", "latitude", "zoom"],
                "properties": {"name": {"type": "string"}, "longitude": {"type": "number"}, "latitude": {"type": "number"},
                "zoom": {"type": "number"}, "bearing": {"type": "number"}}}""",
        )
        private val BOOKMARK_DELETE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["id"], "properties": {"id": {"type": "integer"}}}""",
        )
        private val STYLE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "style"], "properties": {"layer": {"type": "string"},
                "style": {"type": "object", "additionalProperties": false, "properties": {"color": {"type": "string"}, "size": {"type": "number"},
                "width": {"type": "number"}, "fill": {"type": "number"}, "field": {"type": "string"}, "label": {"type": "string"},
                "classes": {"type": "array", "items": {"type": "object", "additionalProperties": false, "required": ["value", "color"],
                "properties": {"value": {"type": "string"}, "color": {"type": "string"}}}},
                "ranges": {"type": "array", "items": {"type": "object", "additionalProperties": false, "required": ["from", "to", "color"],
                "properties": {"from": {"type": "number"}, "to": {"type": "number"}, "color": {"type": "string"}}}}}}}}""",
        )
        private val RENAME_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "to"], "properties": {"layer": {"type": "string"}, "to": {"type": "string"}}}""",
        )
        private val DISPLAY_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer"],
                "properties": {"layer": {"type": "string"}, "visible": {"type": "boolean"}, "opacity": {"type": "number", "minimum": 0, "maximum": 1}}}""",
        )
        private val BASEMAP_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["basemap"],
                "properties": {"basemap": {"enum": ["img", "cia"]}, "visible": {"type": "boolean"}, "opacity": {"type": "number", "minimum": 0, "maximum": 1}}}""",
        )
        private val GROUP_CREATE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["name"],
                "properties": {"name": {"type": "string"}, "vector": {"type": "boolean"}, "layers": {"type": "array", "items": {"type": "string"}}}}""",
        )
        private val GROUP_RENAME_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["group", "to"], "properties": {"group": {"type": "string"}, "to": {"type": "string"}}}""",
        )
        private val GROUP_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["group"], "properties": {"group": {"type": "string"}}}""",
        )
        private val GROUP_DISPLAY_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["group", "visible"],
                "properties": {"group": {"type": "string"}, "visible": {"type": "boolean"}}}""",
        )
        private val PART_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "geometry"],
                "properties": {"layer": {"type": "string"}, "geometry": {"enum": ["POINT", "LINESTRING", "POLYGON"]}, "language": {"type": "string"},
                "color": {"type": "string", "pattern": "^#[0-9a-fA-F]{6}$"}}}""",
        )
        private val RESTORE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["day"], "properties": {"day": {"type": "string", "pattern": "^\\d{4}-\\d{2}-\\d{2}$"}}}""",
        )
        private val MOVE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["index"],
                "properties": {"layer": {"type": "string"}, "group": {"type": "string"}, "into": {"type": "string"}, "index": {"type": "integer", "minimum": 0}}}""",
        )
        private val ORDER_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layers"], "properties": {"layers": {"type": "array", "items": {"type": "string"}}}}""",
        )
        private val CRS_SEARCH_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "properties": {
                "text": {"type": "string"},
                "kinds": {"type": "array", "items": {"enum": ["geographic", "projected", "geographic3d", "geocentric", "vertical", "compound"]}},
                "near": {"type": "array", "items": {"type": "number"}, "minItems": 2, "maxItems": 2},
                "srs": {"type": "array", "items": {"type": "string"}},
                "limit": {"type": "integer"}}}""",
        )
        private val CRS_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["srs"], "properties": {"srs": {"type": "string"}}}""",
        )
        private val TRANSFORM_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["from", "to", "xy"],
                "properties": {"from": {"type": "string"}, "to": {"type": "string"}, "xy": {"type": "array", "items": {"type": "number"}}}}""",
        )
        private val NAME_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["name"], "properties": {"name": {"type": "string"}}}""",
        )

        private fun JsonObject.name(): String = getValue("name").jsonPrimitive.content

        private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    }
}
