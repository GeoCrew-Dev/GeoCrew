package org.geocrew.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
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

/**
 * The project the app has open, with the actions that list, create, open and
 * close projects (ADR 0010). The open project's layers are edited through
 * [editing]; a project closes only when none of its layers is being edited.
 */
class Workspace(private val projects: Projects) {
    /** The open project, if any. Read it inside an action, while no other runs. */
    var current: Project? = null
        private set

    val editing = Editing { layer -> checkNotNull(current) { "no project is open" }.target(layer) }

    /** The project actions and the editing actions, for the registry. */
    val actions: List<Action> = listOf(
        WorkspaceAction(LIST, "List projects", "The projects in the GeoCrew folder", Risk.VIEW, NO_PARAMS, "{}") {
            ActionResult.Done(buildJsonObject { putJsonArray("projects") { projects.list().forEach { add(it) } } })
        },
        WorkspaceAction(CREATE, "Create project", "Makes a project with the built-in layers and opens it", Risk.EDIT, NAME_PARAMS, """{"name": "测区一号"}""") { p ->
            switchTo { projects.create(p.name()) }
        },
        WorkspaceAction(OPEN, "Open project", "Opens a project, closing the one that is open", Risk.VIEW, NAME_PARAMS, """{"name": "测区一号"}""") { p ->
            switchTo { projects.open(p.name()) }
        },
        WorkspaceAction(CLOSE, "Close project", "Closes the open project", Risk.VIEW, NO_PARAMS, "{}") {
            busy() ?: ActionResult.Done().also {
                current?.close()
                current = null
            }
        },
    ) + editing.actions

    // The open project stays open until the next one is: a failure leaves it as it was.
    private fun switchTo(open: () -> Project): ActionResult {
        busy()?.let { return it }
        val next = open()
        current?.close()
        current = next
        return ActionResult.Done(about(next))
    }

    // A project cannot close while one of its layers is being edited.
    private fun busy(): ActionResult.Failed? {
        val layers = editing.layersBeingEdited
        return if (layers.isEmpty()) null else ActionResult.Failed("editing", "stop editing ${layers.joinToString()} first")
    }

    private fun about(project: Project): JsonObject = buildJsonObject {
        put("name", project.name)
        putJsonArray("layers") {
            for (layer in project.layers) addJsonObject { put("name", layer.name); put("template", layer.template) }
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

        private fun schema(s: String) = Json.parseToJsonElement(s).jsonObject

        private val NO_PARAMS = schema("""{"type": "object", "additionalProperties": false}""")
        private val NAME_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["name"], "properties": {"name": {"type": "string"}}}""",
        )

        private fun JsonObject.name(): String = getValue("name").jsonPrimitive.content
    }
}
