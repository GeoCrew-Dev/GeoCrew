package org.geocrew.core.model.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How much harm a wrong call can do, declared by every action when it is
 * registered. The permission check works on these categories only, so a new
 * action needs no rule of its own: it picks a risk and is handled like its
 * peers.
 */
enum class ActionRisk(val id: String, val label: String, val defaultMode: ActionMode) {
    /** Looks, finds, measures; changes nothing. */
    READ("read", "查看和查询", ActionMode.ALLOW),
    /** Creates or edits one record on this phone; goes into the undo log. */
    EDIT("edit", "新建和修改（可撤销）", ActionMode.ALLOW),
    /** Changes several records at once. */
    BULK("bulk", "批量修改", ActionMode.ASK),
    /** Removes records or attachments. */
    DELETE("delete", "删除", ActionMode.ASK),
    /** Reaches other people: group messages, whole-group pushes, uploads. */
    SEND("send", "对外发送（组内消息、全组推送、上传）", ActionMode.ASK),
    /** Settings, keys, workspace membership. */
    SETTINGS("settings", "改设置、密钥和工作组成员", ActionMode.ASK),
    ;

    companion object {
        fun of(id: String?): ActionRisk? = entries.firstOrNull { it.id == id }
    }
}

/** What the user lets the assistant do for one [ActionRisk]. */
enum class ActionMode(val id: String, val label: String) {
    ALLOW("allow", "直接执行"),
    ASK("ask", "先问我"),
    DENY("deny", "禁止"),
    ;

    companion object {
        fun of(id: String?): ActionMode? = entries.firstOrNull { it.id == id }
    }
}

/** Who asked for a call. Local quick commands count as the user's own taps. */
enum class ActionSource { USER, AI }

/**
 * One registered action. [parameters] is a JSON Schema object, so the same
 * description serves the assistant's tool list, plugins and automation.
 * [scopes] names the pages where the action is offered to the assistant;
 * empty means everywhere. [riskFor] lets an action raise its risk for a
 * given call (setting an attribute on one record is an edit, on twenty it
 * is a bulk change).
 */
data class ActionSpec(
    val name: String,
    val title: String,
    val description: String,
    val risk: ActionRisk,
    val parameters: JsonObject = Schema.obj {},
    val scopes: Set<String> = emptySet(),
    val undoable: Boolean = risk != ActionRisk.READ,
    val riskFor: (JsonObject) -> ActionRisk = { risk },
) {
    fun offeredOn(page: String?): Boolean = scopes.isEmpty() || page == null || page in scopes
}

/** A request to run [name] with [args]; [id] ties it to the model's tool call. */
data class ActionCall(val id: String, val name: String, val args: JsonObject = JsonObject(emptyMap()))

/**
 * What an action did, kept short: the assistant reads [summary] (and
 * [data] when it asked a question), the user sees [summary] in the list of
 * what was done.
 */
data class ActionResult(
    val ok: Boolean,
    val summary: String,
    val data: JsonElement? = null,
    /** Record ids the call created or changed, for "show me" and undo. */
    val touched: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ok", ok)
        put("summary", summary)
        data?.let { put("data", it) }
        if (touched.isNotEmpty()) put("ids", JsonArray(touched.map(::JsonPrimitive)))
    }

    companion object {
        fun fail(why: String) = ActionResult(false, why)
    }
}

/** Small builder for the JSON Schema objects actions declare. */
object Schema {
    class Builder {
        private val props = LinkedHashMap<String, JsonObject>()
        private val required = mutableListOf<String>()

        private fun add(name: String, required: Boolean, o: JsonObject) {
            props[name] = o
            if (required) this.required += name
        }

        fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) = add(
            name, required,
            buildJsonObject {
                put("type", "string"); put("description", description)
                enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
            },
        )

        fun number(name: String, description: String, required: Boolean = false, min: Double? = null, max: Double? = null) = add(
            name, required,
            buildJsonObject {
                put("type", "number"); put("description", description)
                min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
            },
        )

        fun integer(name: String, description: String, required: Boolean = false, min: Int? = null, max: Int? = null) = add(
            name, required,
            buildJsonObject {
                put("type", "integer"); put("description", description)
                min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
            },
        )

        fun boolean(name: String, description: String, required: Boolean = false) = add(
            name, required, buildJsonObject { put("type", "boolean"); put("description", description) },
        )

        fun strings(name: String, description: String, required: Boolean = false) = add(
            name, required,
            buildJsonObject {
                put("type", "array"); put("description", description)
                put("items", buildJsonObject { put("type", "string") })
            },
        )

        fun build(): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(props))
            if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
    }

    fun obj(block: Builder.() -> Unit): JsonObject = Builder().apply(block).build()
}
