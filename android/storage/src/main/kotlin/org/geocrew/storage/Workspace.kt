package org.geocrew.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
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
import org.geocrew.core.edit.FieldType
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
    val editing = Editing(sharedScope = PROJECT_SCOPE) { layer -> checkNotNull(current) { "no project is open" }.target(layer) }

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
                    put("srs", wanted ?: own)
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
                    putJsonArray("fields") {
                        for (f in store.fields) addJsonObject { put("name", f.name); put("type", f.type.name.lowercase()) }
                    }
                    put("editing", layer in editing.layersBeingEdited)
                    put("modified", editing.isModified(layer))
                    put("projectModified", editing.layersBeingEdited.any(editing::isModified))
                },
            )
        },
        WorkspaceAction(
            EXPORT, "Export project", "Writes the open project's saved data to a new GeoPackage or KML file in the export folder",
            Risk.SEND, EXPORT_PARAMS, """{"format": "kml"}""",
        ) { p ->
            val project = checkNotNull(current) { "no project is open" }
            unsaved()?.let { return@WorkspaceAction it }
            val format = p.getValue("format").jsonPrimitive.content
            // GDAL makes no KML of a GeoPackage without layers, as a new project is.
            if (format == "kml" && project.layers.isEmpty()) return@WorkspaceAction ActionResult.Failed("empty", "the project has no layers")
            val exports = Exports(projects.exports)
            val file = when (format) {
                "gpkg" -> exports.gpkg(project)
                else -> exports.kml(project)
            }
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
            // The project's file is opened again: every layer stops being edited, with nothing unsaved.
            val ended = (stopEditing() + editing.scopeOf(layer)).distinct()
            project.addField(layer, name, type)
            current = runCatching { projects.reopen(project) }.getOrElse {
                current = null // closed, and not open again
                throw it
            }
            ActionResult.Done(buildJsonObject { put("layer", layer); put("field", name) }, endsUndo = ended)
        },
        WorkspaceAction(
            LAYER_INFO, "Layer info", "A layer of the open project: its fields with their types, and how many features it has, as edited",
            Risk.VIEW, LAYER_PARAMS, """{"layer": "野外点"}""",
        ) { p ->
            val layer = p.getValue("layer").jsonPrimitive.content
            val project = checkNotNull(current) { "no project is open" }
            val store = project.target(layer).store
            ActionResult.Done(
                buildJsonObject {
                    put("name", layer)
                    put("template", project.layers.firstOrNull { it.name == layer }?.template)
                    putJsonArray("fields") {
                        for (f in store.fields) addJsonObject { put("name", f.name); put("type", f.type.name.lowercase()) }
                    }
                    put("count", editing.features(layer)?.size?.toLong() ?: project.count(layer))
                },
            )
        },
        WorkspaceAction(
            LAYER_CREATE, "Create layer",
            "Adds a point layer to the open project: like another layer (its fields, geometry type and CRS) or from a template " +
                "(field-point: a name and a description), its fields named in the language given",
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
            CRS_DESCRIBE, "Describe CRS", "What a CRS (EPSG:4549, WKT, a PROJ string) is: its name, id, whether geographic or projected, and unit",
            Risk.VIEW, CRS_PARAMS, """{"srs": "EPSG:4547"}""",
        ) { p ->
            ActionResult.Done(Crs.describe(p.getValue("srs").jsonPrimitive.content))
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
        WorkspaceAction(CLOSE, "Close project", "Closes the open project, once no layer has unsaved edits", Risk.VIEW, NO_PARAMS, "{}") {
            unsaved() ?: run {
                val ended = stopEditing()
                current?.close()
                current = null
                ActionResult.Done(endsUndo = ended)
            }
        },
    ) + editing.actions

    // The open project stays open until the next one is: a failure leaves it as it was.
    private fun switchTo(open: () -> Project): ActionResult {
        unsaved()?.let { return it }
        val next = open()
        val ended = stopEditing()
        current?.close()
        current = next
        return ActionResult.Done(about(next), endsUndo = ended)
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

    private fun about(project: Project): JsonObject = buildJsonObject {
        put("name", project.name)
        put("id", project.id)
        put("folder", project.folder.path)
        putJsonArray("layers") {
            for (layer in project.layers) {
                addJsonObject {
                    put("name", layer.name)
                    put("id", layer.id)
                    put("template", layer.template)
                    put("geometry", layer.geometry)
                    put("srs", layer.srs)
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
        const val LAYER_INFO = "layer.info"
        const val LAYER_CREATE = "layer.create"
        const val CRS_DESCRIBE = "crs.describe"
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
        private val EXPORT_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["format"], "properties": {"format": {"enum": ["gpkg", "kml"]}}}""",
        )
        private val LAYER_CREATE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["name"],
                "properties": {"name": {"type": "string"}, "like": {"type": "string"}, "template": {"enum": ["field-point"]},
                               "language": {"enum": ["zh", "en"]}, "srs": {"type": "string"}}}""",
        )
        private val FEATURES_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer"], "properties": {"layer": {"type": "string"}, "srs": {"type": "string"}}}""",
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
    }
}
