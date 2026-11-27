package org.geocrew.storage

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geocrew.core.action.Action
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.ActionSpec
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Risk
import org.geocrew.core.action.Stability
import org.geocrew.nativelib.VectorDataset

/**
 * Vector data brought into the open project (ADR 0002's import): the layers
 * of a GeoPackage, Shapefile (also zipped), GeoJSON, KML, GPX and the other
 * formats GDAL reads become project layers, copied with their fields and
 * features. The file is probed in a process of its own first ([probe], ADR
 * 0003). The AI, plugins and MCP only import files in the GeoCrew folder
 * ([geocrew], ADR 0011). The action gives the project as project.current
 * describes it.
 */
class ImportActions(private val workspace: Workspace, private val geocrew: File, private val probe: suspend (File) -> JsonObject) {
    val actions: List<Action> = listOf(
        object : Action {
            override val spec = ActionSpec(
                IMPORT, "Import layers",
                "Copies the layers of the vector file at [path] (GeoPackage, Shapefile or a zip of one, GeoJSON, KML, GPX, CSV with " +
                    "geometry and others GDAL reads) into the open project as new layers: those named in [layers], or all. A file with " +
                    "one layer gives a layer named after the file, others keep their layers' names; a name taken gets a number. " +
                    "Fields GeoCrew cannot edit stay behind; a layer without a CRS is refused",
                PARAMS, Risk.EDIT, Stability.PREVIEW,
                examples = listOf(Json.parseToJsonElement("""{"path": "/storage/emulated/0/Download/WeiXin/路线.kml"}""").jsonObject),
            )

            override suspend fun run(caller: Caller, params: JsonObject): ActionResult {
                val project = workspace.current ?: return ActionResult.Failed(NO_PROJECT, "no project is open")
                val path = (params["path"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return ActionResult.Failed(NOT_FOUND, "no path given")
                val file = File(path).canonicalFile
                if (caller != Caller.USER && !within(file, geocrew.canonicalFile)) {
                    return ActionResult.Failed(OUTSIDE, "${file.path} is outside ${geocrew.path}")
                }
                if (!file.exists()) return ActionResult.Failed(NOT_FOUND, "${file.path} is not there")
                val read = try {
                    probe(file)
                } catch (e: Exception) {
                    return ActionResult.Failed(UNREADABLE, e.message ?: "the file cannot be read")
                }
                if ((read["kind"] as? JsonPrimitive)?.contentOrNull != "vector") return ActionResult.Failed(NOT_VECTOR, "${file.name} holds no vector data")
                val there = (read["layers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                val wanted = (params["layers"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: there
                val unknown = wanted - there.toSet()
                if (unknown.isNotEmpty()) return ActionResult.Failed(NOT_FOUND, "${file.name} has no layer ${unknown.joinToString()}")
                if (wanted.isEmpty()) return ActionResult.Failed(NOT_VECTOR, "${file.name} has no layers")
                VectorDataset.open(gdalPath(file), update = false, wal = false).use { source ->
                    for (layer in wanted) {
                        // One layer is named after its file (路线.kml gives 路线), several keep their own names.
                        val name = project.freeLayerName(if (there.size == 1) file.nameWithoutExtension else layer)
                        try {
                            project.importLayer(source, layer, name)
                        } catch (e: IllegalArgumentException) {
                            return ActionResult.Failed(NO_CRS, e.message ?: "$layer does not say its CRS")
                        }
                    }
                }
                return ActionResult.Done(workspace.about())
            }
        },
    )

    private fun within(file: File, folder: File) = file == folder || file.path.startsWith(folder.path.trimEnd(File.separatorChar) + File.separator)

    companion object {
        const val IMPORT = "layer.import"

        /** Failure codes: no project open, a file outside the GeoCrew folder (for the AI), and what is wrong with the file. */
        const val NO_PROJECT = "no_project"
        const val OUTSIDE = "outside"
        const val NOT_FOUND = "not_found"
        const val UNREADABLE = "unreadable"
        const val NOT_VECTOR = "not_vector"
        const val NO_CRS = "no_crs"

        /** How GDAL is to open [file]: a zip from inside, as the Shapefile it holds. */
        fun gdalPath(file: File): String = if (file.extension.equals("zip", ignoreCase = true)) "/vsizip/${file.path}" else file.path

        private val PARAMS = Json.parseToJsonElement(
            """{"type": "object", "additionalProperties": false, "required": ["path"],
                "properties": {"path": {"type": "string"}, "layers": {"type": "array", "items": {"type": "string"}}}}""",
        ).jsonObject
    }
}
