package org.geocrew.core.model.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The OpenAI-compatible chat completions format with tool calls, which most
 * hosted and self-hosted model servers speak. Only JSON lives here, so the
 * format is tested on the JVM; the Android side does the HTTP.
 */
object OpenAiWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun system(text: String) = message("system", text)
    fun user(text: String) = message("user", text)

    private fun message(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }

    fun toolResult(callId: String, result: JsonObject) = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", callId)
        put("content", result.toString())
    }

    fun tool(spec: ActionSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", spec.name)
                put("description", spec.title + "。" + spec.description)
                put("parameters", spec.parameters)
            },
        )
    }

    fun request(model: String, messages: List<JsonObject>, tools: List<ActionSpec>): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", JsonArray(messages))
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools.map(::tool)))
            put("tool_choice", "auto")
        }
        put("temperature", 0.2)
    }

    /** One reply: text for the user and/or tool calls; [message] goes back into the history as is. */
    data class Reply(val text: String?, val calls: List<ActionCall>, val message: JsonObject, val badArguments: List<String> = emptyList())

    fun parse(body: String): Reply {
        val root = json.parseToJsonElement(body).jsonObject
        root["error"]?.let { e ->
            val msg = (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString()
            throw IllegalStateException(msg)
        }
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw IllegalStateException("回复里没有内容")
        val text = (message["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val bad = mutableListOf<String>()
        val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val f = o["function"] as? JsonObject ?: return@mapNotNull null
            val name = f["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val raw = (f["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { "{}" }
            val args = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { bad += id; JsonObject(emptyMap()) }
            ActionCall(id, name, args)
        }
        // Keep only what the next request needs; some servers reject extra fields.
        val keep = buildJsonObject {
            put("role", "assistant")
            put("content", text?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            message["tool_calls"]?.let { put("tool_calls", it) }
        }
        return Reply(text, calls, keep, bad)
    }

    /** The current state, put in the system prompt so the assistant needs no calls to learn it. */
    fun stateBlock(lines: List<String>): String = buildString {
        appendLine("当前状态（自动生成）：")
        lines.forEach { appendLine("- $it") }
    }

    fun models(body: String): List<String> = runCatching {
        json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
    }.getOrDefault(emptyList())

}
