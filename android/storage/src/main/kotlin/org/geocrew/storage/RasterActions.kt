package org.geocrew.storage

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.geocrew.core.action.Action
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.ActionSpec
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Risk
import org.geocrew.core.action.Stability

/**
 * Rasters under the open project's layers (GeoTIFF and the like, ADR 0006):
 * add one by its path, show or hide it and set its opacity, take it out. A
 * file is probed in a process of its own before it is added ([probe], ADR
 * 0003), and refused unless it is a raster that knows where it is. The AI,
 * plugins and MCP only add files in the GeoCrew folder ([geocrew], ADR 0011).
 * Each action gives the project as project.current describes it.
 */
class RasterActions(private val workspace: Workspace, private val geocrew: File, private val probe: suspend (File) -> JsonObject) {
    val actions: List<Action> = listOf(
        RasterAction(
            ADD, "Add raster",
            "Adds the raster file at [path] (GeoTIFF, JPEG 2000, MBTiles and others GDAL reads) under the open project's layers, " +
                "as [name] or else the file's own name; the file stays where it is. Refused unless it is a raster with a CRS",
            Risk.EDIT, ADD_PARAMS, """{"path": "/storage/emulated/0/Download/影像.tif"}""",
        ) { caller, p ->
            val project = workspace.current ?: return@RasterAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val file = File(p.text("path").orEmpty()).canonicalFile
            if (caller != Caller.USER && !within(file, geocrew.canonicalFile)) {
                return@RasterAction ActionResult.Failed(OUTSIDE, "${file.path} is outside ${geocrew.path}")
            }
            if (!file.exists()) return@RasterAction ActionResult.Failed(NOT_FOUND, "${file.path} is not there")
            val info = try {
                probe(file)
            } catch (e: Exception) {
                return@RasterAction ActionResult.Failed(UNREADABLE, e.message ?: "the file cannot be read")
            }
            if ((info["kind"] as? JsonPrimitive)?.contentOrNull != "raster") {
                return@RasterAction ActionResult.Failed(NOT_RASTER, "${file.name} is not a raster")
            }
            // Without a CRS and an extent there is no knowing where on the map it goes.
            if (info["crs"] !is JsonObject || (info["extent"] as? JsonArray)?.size != 4) {
                return@RasterAction ActionResult.Failed(NO_CRS, "${file.name} does not say where it is")
            }
            project.addRaster(file, info, p.text("name"))
            ActionResult.Done(workspace.about())
        },
        RasterAction(
            DISPLAY, "Raster display", "Shows or hides raster [name] of the open project and sets its opacity (0 to 1); saved at once, not undone",
            Risk.EDIT, DISPLAY_PARAMS, """{"name": "影像", "opacity": 0.6}""",
        ) { _, p ->
            val project = workspace.current ?: return@RasterAction ActionResult.Failed(NO_PROJECT, "no project is open")
            project.setDisplay(
                raster = p.text("name").orEmpty(),
                visible = (p["visible"] as? JsonPrimitive)?.booleanOrNull,
                opacity = (p["opacity"] as? JsonPrimitive)?.doubleOrNull,
            )
            ActionResult.Done(workspace.about())
        },
        RasterAction(
            REMOVE, "Remove raster", "Takes raster [name] out of the open project; its file stays where it is",
            Risk.EDIT, NAME_PARAMS, """{"name": "影像"}""",
        ) { _, p ->
            val project = workspace.current ?: return@RasterAction ActionResult.Failed(NO_PROJECT, "no project is open")
            project.removeRaster(p.text("name").orEmpty())
            ActionResult.Done(workspace.about())
        },
    )

    private fun within(file: File, folder: File) = file == folder || file.path.startsWith(folder.path.trimEnd(File.separatorChar) + File.separator)

    private class RasterAction(
        id: String,
        name: String,
        description: String,
        risk: Risk,
        params: JsonObject,
        example: String,
        private val body: suspend (Caller, JsonObject) -> ActionResult,
    ) : Action {
        override val spec = ActionSpec(id, name, description, params, risk, Stability.PREVIEW, examples = listOf(schema(example)))

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult = body(caller, params)
    }

    companion object {
        const val ADD = "raster.add"
        const val DISPLAY = "raster.display"
        const val REMOVE = "raster.remove"

        /** Failure codes: no project open, a file outside the GeoCrew folder (for the AI), and what is wrong with the file. */
        const val NO_PROJECT = "no_project"
        const val OUTSIDE = "outside"
        const val NOT_FOUND = "not_found"
        const val UNREADABLE = "unreadable"
        const val NOT_RASTER = "not_raster"
        const val NO_CRS = "no_crs"

        private fun schema(s: String) = Json.parseToJsonElement(s).jsonObject

        private val ADD_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["path"], "properties": {"path": {"type": "string"}, "name": {"type": "string"}}}""",
        )
        private val DISPLAY_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["name"],
                "properties": {"name": {"type": "string"}, "visible": {"type": "boolean"}, "opacity": {"type": "number"}}}""",
        )
        private val NAME_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["name"], "properties": {"name": {"type": "string"}}}""",
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
