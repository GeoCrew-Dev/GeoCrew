package org.geocrew.storage

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.action.Action
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.ActionSpec
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Risk
import org.geocrew.core.action.Stability
import org.geocrew.core.edit.Attachment

/**
 * Attachments of the open project's features (ADR 0004). A file goes into the
 * project's attachment folder first ([IMPORT]); what that gives, feature.add
 * and feature.attachments.set take, so attachments are edited, undone and saved
 * with their features. [LIST] gives a feature's attachments as edited, with
 * where their files are. The AI, plugins and MCP only import files in the
 * GeoCrew folder ([geocrew], ADR 0011).
 */
class AttachmentActions(private val workspace: Workspace, private val geocrew: File) {
    val actions: List<Action> = listOf(
        AttachmentAction(
            IMPORT, "Import attachment",
            "Copies the file at [path] into the open project's attachment folder, named by its SHA-256, with a thumbnail for a photo or a video; " +
                "gives the attachment as feature.add and feature.attachments.set take it, with where its files are. [name] and [type] (MIME) default to the file's",
            Risk.EDIT, IMPORT_PARAMS, """{"path": "/storage/emulated/0/GeoCrew/导入/露头.jpg"}""",
        ) { caller, p ->
            val project = workspace.current ?: return@AttachmentAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val file = File(p.text("path").orEmpty()).canonicalFile
            if (caller != Caller.USER && !within(file, geocrew.canonicalFile)) {
                return@AttachmentAction ActionResult.Failed(OUTSIDE, "${file.path} is outside ${geocrew.path}")
            }
            if (!file.isFile) return@AttachmentAction ActionResult.Failed(NOT_FOUND, "${file.path} is not there")
            val name = p.text("name")?.takeIf { it.isNotBlank() } ?: file.name
            val stored = AttachmentFiles.store(project.attachments, file, p.text("type")?.takeIf { it.isNotBlank() } ?: AttachmentFiles.typeOf(name))
            ActionResult.Done(
                buildJsonObject {
                    put("file", stored.file)
                    stored.thumb?.let { put("thumb", it) }
                    put("type", stored.type)
                    put("size", stored.size)
                    put("name", name)
                    put("path", AttachmentFiles.fileOf(project.attachments, stored.file, stored.type).path)
                    stored.thumb?.let { put("thumbPath", AttachmentFiles.fileOf(project.attachments, it, "image/jpeg").path) }
                },
            )
        },
        AttachmentAction(
            LIST, "Feature attachments",
            "A feature's attachments as edited, in their order, each with the path of its file and of its thumbnail; missing when the file is not here",
            Risk.VIEW, LIST_PARAMS, """{"layer": "野外点", "fid": 12}""",
        ) { _, p ->
            val project = workspace.current ?: return@AttachmentAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val layer = p.getValue("layer").jsonPrimitive.content
            val fid = p.getValue("fid").jsonPrimitive.long
            val attachments = workspace.editing.attachments(layer, fid) ?: project.target(layer).store.attachments(fid)
            ActionResult.Done(
                buildJsonObject {
                    putJsonArray("attachments") {
                        for (a in attachments) addJsonObject { describe(project.attachments, a) }
                    }
                },
            )
        },
    )

    private fun kotlinx.serialization.json.JsonObjectBuilder.describe(folder: File, a: Attachment) {
        put("id", a.id)
        put("file", a.file)
        a.thumb?.let { put("thumb", it) }
        put("type", a.type)
        put("size", a.size)
        put("name", a.name)
        a.meta?.let { put("meta", it) }
        val file = AttachmentFiles.fileOf(folder, a.file, a.type)
        put("path", file.path)
        if (!file.exists()) put("missing", true)
        a.thumb?.let { thumb -> AttachmentFiles.fileOf(folder, thumb, "image/jpeg").takeIf { it.exists() }?.let { put("thumbPath", it.path) } }
    }

    private fun within(file: File, folder: File) = file == folder || file.path.startsWith(folder.path.trimEnd(File.separatorChar) + File.separator)

    private class AttachmentAction(
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
        const val IMPORT = "attachment.import"
        const val LIST = "feature.attachments"

        /** Failure codes: no project open, a file outside the GeoCrew folder (for the AI), no such file. */
        const val NO_PROJECT = "no_project"
        const val OUTSIDE = "outside"
        const val NOT_FOUND = "not_found"

        private fun schema(s: String) = Json.parseToJsonElement(s).jsonObject

        private val IMPORT_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["path"],
                "properties": {"path": {"type": "string"}, "name": {"type": "string"}, "type": {"type": "string"}}}""",
        )
        private val LIST_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["layer", "fid"],
                "properties": {"layer": {"type": "string"}, "fid": {"type": "integer"}}}""",
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
