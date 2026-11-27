package org.geocrew.core.files

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.action.Action
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.ActionSpec
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Risk
import org.geocrew.core.action.Stability

/**
 * The built-in file manager's actions (ADR 0011): list a folder, make one, and
 * rename, copy, move or delete files and folders. The user reaches all of
 * [top] (the phone's shared storage with all-files access, else the GeoCrew
 * folder itself); the AI, plugins and MCP only [geocrew]. Nothing inside the
 * folder [busy] gives (the open project's) is renamed, moved or deleted, nor
 * a folder holding it, nor [top] or [geocrew] themselves.
 */
class FileActions(private val geocrew: File, private val top: () -> File, private val busy: () -> File?) {
    val actions: List<Action> = listOf(
        FileAction(
            LIST, "List folder",
            "The files and folders in a folder (the GeoCrew folder by default): folders first, then by name, digits by value; " +
                "each with its path, size in bytes, last change (ms since 1970), kind (folder, vector, raster, image, archive, " +
                "document, other) and, for a folder, how many items it holds. Hidden ones (starting with a dot) only when asked",
            Risk.VIEW, LIST_PARAMS, """{"path": "/storage/emulated/0/GeoCrew"}""",
        ) { caller, p ->
            val folder = resolve(caller, p.text("path"))
            if (!folder.isDirectory) refuse(NOT_FOUND, "${folder.path} is not a folder")
            val hidden = (p["hidden"] as? JsonPrimitive)?.booleanOrNull == true
            val shown = { f: File -> hidden || !f.name.startsWith(".") }
            val children = folder.listFiles()?.filter(shown) ?: refuse(UNREADABLE, "${folder.path} cannot be read")
            val sorted = children.sortedWith(compareBy<File> { !it.isDirectory }.thenBy(NaturalOrder) { it.name })
            ActionResult.Done(
                buildJsonObject {
                    put("path", folder.path)
                    put("parent", if (folder == reach(caller)) null else folder.parentFile?.path)
                    putJsonArray("entries") {
                        for (f in sorted) {
                            addJsonObject {
                                put("name", f.name)
                                put("path", f.path)
                                put("kind", FileKind.of(f).name.lowercase())
                                put("modified", f.lastModified())
                                if (f.isDirectory) put("items", f.listFiles()?.count(shown) ?: 0) else put("size", f.length())
                            }
                        }
                    }
                },
            )
        },
        FileAction(
            MAKE_FOLDER, "Make folder", "Makes folder [name] in folder [path]; a name already there is refused",
            Risk.EDIT, NAMED_PARAMS, """{"path": "/storage/emulated/0/GeoCrew", "name": "影像"}""",
        ) { caller, p ->
            val folder = resolve(caller, p.text("path"))
            val made = File(folder, checkName(p.text("name")))
            if (made.exists()) refuse(EXISTS, "${made.path} is there already")
            if (!made.mkdir()) refuse(FAILED, "${made.path} could not be made")
            ActionResult.Done(buildJsonObject { put("path", made.path) })
        },
        FileAction(
            RENAME, "Rename file", "Renames the file or folder at [path] to [name], in the same folder; a name already there is refused",
            Risk.EDIT, NAMED_PARAMS, """{"path": "/storage/emulated/0/GeoCrew/导出/a.kml", "name": "b.kml"}""",
        ) { caller, p ->
            val file = changeable(caller, p.text("path"))
            val name = checkName(p.text("name"))
            val renamed = File(file.parentFile, name)
            if (name == file.name) return@FileAction ActionResult.Done(buildJsonObject { put("path", file.path) })
            // Shared storage ignores case: a name that differs only in case is the same file, renamed by way of another name.
            val caseOnly = name.equals(file.name, ignoreCase = true)
            if (!caseOnly && renamed.exists()) refuse(EXISTS, "${renamed.path} is there already")
            val moved = if (caseOnly) {
                val between = free(file.parentFile, ".$name.renaming")
                file.renameTo(between) && between.renameTo(renamed)
            } else {
                file.renameTo(renamed)
            }
            if (!moved) refuse(FAILED, "${file.path} could not be renamed")
            ActionResult.Done(buildJsonObject { put("path", renamed.path) })
        },
        FileAction(
            COPY, "Copy file", "Copies the file or folder at [path] into folder [to]; a name already there gets a number, as a (2).kml. The open project's files are not copied while it is open",
            Risk.EDIT, MOVE_PARAMS, """{"path": "/storage/emulated/0/Download/影像.tif", "to": "/storage/emulated/0/GeoCrew"}""",
        ) { caller, p ->
            val file = resolve(caller, p.text("path"))
            if (!file.exists()) refuse(NOT_FOUND, "${file.path} is not there")
            // An open project's GeoPackage keeps saved edits in its -wal file for a while: a copy of it would miss them.
            busy()?.canonicalFile?.let { open ->
                if (within(file, open) || within(open, file)) refuse(BUSY, "${file.path} belongs to the open project")
            }
            val into = destination(caller, file, p.text("to"))
            val copy = free(into, file.name)
            if (!file.copyRecursively(copy)) refuse(FAILED, "${file.path} could not be copied")
            ActionResult.Done(buildJsonObject { put("path", copy.path) })
        },
        FileAction(
            MOVE, "Move file", "Moves the file or folder at [path] into folder [to]; a name already there gets a number, as a (2).kml",
            Risk.EDIT, MOVE_PARAMS, """{"path": "/storage/emulated/0/Download/影像.tif", "to": "/storage/emulated/0/GeoCrew"}""",
        ) { caller, p ->
            val file = changeable(caller, p.text("path"))
            val into = destination(caller, file, p.text("to"))
            if (into == file.parentFile) return@FileAction ActionResult.Done(buildJsonObject { put("path", file.path) })
            val moved = free(into, file.name)
            // Within one storage a rename moves it at once; across storages it is copied, then the original deleted.
            if (!file.renameTo(moved)) {
                if (!file.copyRecursively(moved)) refuse(FAILED, "${file.path} could not be moved")
                if (!file.deleteRecursively()) refuse(FAILED, "${file.path} was copied to ${moved.path} but could not be deleted")
            }
            ActionResult.Done(buildJsonObject { put("path", moved.path) })
        },
        FileAction(
            DELETE, "Delete file", "Deletes the file or folder at [path], a folder with everything in it; it cannot be undone",
            Risk.DELETE, PATH_PARAMS, """{"path": "/storage/emulated/0/GeoCrew/导出/a.kml"}""",
        ) { caller, p ->
            val file = changeable(caller, p.text("path"))
            if (!file.deleteRecursively()) refuse(FAILED, "${file.path} could not be deleted in full")
            ActionResult.Done(buildJsonObject { put("path", file.parentFile?.path) })
        },
    )

    // What the caller may reach: the user all of the storage, others the GeoCrew folder.
    private fun reach(caller: Caller): File = (if (caller == Caller.USER) top() else geocrew).canonicalFile

    // [path] as a file the caller may reach, with ".." and links resolved: the GeoCrew folder when there is none,
    // and a relative path is taken from there.
    private fun resolve(caller: Caller, path: String?): File {
        val base = reach(caller)
        val file = when {
            path.isNullOrBlank() -> geocrew.canonicalFile
            File(path).isAbsolute -> File(path).canonicalFile
            else -> File(geocrew, path).canonicalFile
        }
        if (!within(file, base)) refuse(OUTSIDE, "$path is outside ${base.path}")
        return file
    }

    // [path] as a file that may be renamed, moved or deleted.
    private fun changeable(caller: Caller, path: String?): File {
        val file = resolve(caller, path)
        if (!file.exists()) refuse(NOT_FOUND, "${file.path} is not there")
        if (file == reach(caller) || file == geocrew.canonicalFile) refuse(PROTECTED, "${file.path} stays")
        busy()?.canonicalFile?.let { open ->
            if (within(file, open) || within(open, file)) refuse(BUSY, "${file.path} belongs to the open project")
        }
        return file
    }

    // Folder [to], into which [file] may go: not the file itself, nor a folder inside it.
    private fun destination(caller: Caller, file: File, to: String?): File {
        val into = resolve(caller, to)
        if (!into.isDirectory) refuse(NOT_FOUND, "${into.path} is not a folder")
        if (within(into, file)) refuse(INSIDE, "${file.path} cannot go into itself")
        return into
    }

    private fun within(file: File, folder: File) = file == folder || file.path.startsWith(folder.path.trimEnd(File.separatorChar) + File.separator)

    // A name a file may have: something, no slash, not . or .., no space at either end, at most 255 characters.
    private fun checkName(name: String?): String {
        if (name.isNullOrEmpty() || name != name.trim() || name == "." || name == ".." || name.length > 255 || name.any { it == '/' || it == '\u0000' }) {
            refuse(BAD_NAME, "\"$name\" cannot name a file")
        }
        return name
    }

    // [name] in [folder], or the first of "a (2).kml", "a (3).kml", ... not taken.
    private fun free(folder: File, name: String): File {
        val first = File(folder, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return generateSequence(2) { it + 1 }.map { File(folder, "${name.substring(0, dot)} ($it)${name.substring(dot)}") }.first { !it.exists() }
    }

    private class Refused(val code: String, message: String) : Exception(message)

    private fun refuse(code: String, message: String): Nothing = throw Refused(code, message)

    private class FileAction(
        id: String,
        name: String,
        description: String,
        risk: Risk,
        params: JsonObject,
        example: String,
        private val body: (Caller, JsonObject) -> ActionResult,
    ) : Action {
        override val spec = ActionSpec(id, name, description, params, risk, Stability.PREVIEW, examples = listOf(schema(example)))

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult =
            try {
                body(caller, params)
            } catch (e: Refused) {
                ActionResult.Failed(e.code, e.message.orEmpty())
            }
    }

    companion object {
        const val LIST = "file.list"
        const val MAKE_FOLDER = "file.folder.make"
        const val RENAME = "file.rename"
        const val COPY = "file.copy"
        const val MOVE = "file.move"
        const val DELETE = "file.delete"

        /** Failure codes: a name already there, the open project's files, the storage or GeoCrew folder itself, and the rest. */
        const val EXISTS = "exists"
        const val BUSY = "busy"
        const val PROTECTED = "protected"
        const val OUTSIDE = "outside"
        const val NOT_FOUND = "not_found"
        const val UNREADABLE = "unreadable"
        const val INSIDE = "inside"
        const val BAD_NAME = "bad_name"
        const val FAILED = "failed"

        private fun schema(s: String) = Json.parseToJsonElement(s).jsonObject

        private val LIST_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "properties": {"path": {"type": "string"}, "hidden": {"type": "boolean"}}}""",
        )
        private val PATH_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["path"], "properties": {"path": {"type": "string"}}}""",
        )
        private val NAMED_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["path", "name"], "properties": {"path": {"type": "string"}, "name": {"type": "string"}}}""",
        )
        private val MOVE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["path", "to"], "properties": {"path": {"type": "string"}, "to": {"type": "string"}}}""",
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
