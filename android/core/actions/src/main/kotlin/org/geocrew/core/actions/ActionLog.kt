package org.geocrew.core.actions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.geocrew.core.model.action.ActionSource
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** How one record looked before an action, enough to put it back. */
data class Snapshot(
    val id: String,
    /** False when the action created the record; undoing deletes it. */
    val existed: Boolean,
    val kind: String? = null,
    val geometry: JsonElement? = null,
    val properties: JsonObject = JsonObject(emptyMap()),
    val deleted: Boolean = false,
) {
    fun toJson() = buildJsonObject {
        put("id", id); put("existed", existed); kind?.let { put("kind", it) }
        put("geometry", geometry ?: JsonNull); put("properties", properties); put("deleted", deleted)
    }

    companion object {
        fun fromJson(o: JsonObject) = Snapshot(
            id = o["id"]!!.jsonPrimitive.content,
            existed = o["existed"]!!.jsonPrimitive.boolean,
            kind = o["kind"]?.jsonPrimitive?.contentOrNull,
            geometry = o["geometry"]?.takeIf { it !is JsonNull },
            properties = o["properties"] as? JsonObject ?: JsonObject(emptyMap()),
            deleted = o["deleted"]?.jsonPrimitive?.boolean ?: false,
        )
    }
}

/** One action as the user sees it in 设置 → AI 操作记录. */
data class LogEntry(
    val id: String,
    /** Calls made for one request share this, so a request can be undone at once. */
    val requestId: String,
    val action: String,
    val title: String,
    val summary: String,
    val source: ActionSource,
    val at: Instant,
    val before: List<Snapshot> = emptyList(),
    val undone: Boolean = false,
) {
    val undoable: Boolean get() = before.isNotEmpty() && !undone

    fun toJson() = buildJsonObject {
        put("id", id); put("request", requestId); put("action", action); put("title", title); put("summary", summary)
        put("source", source.name); put("at", at.toEpochMilli()); put("undone", undone)
        put("before", JsonArray(before.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JsonObject) = LogEntry(
            id = o["id"]!!.jsonPrimitive.content,
            requestId = o["request"]!!.jsonPrimitive.content,
            action = o["action"]!!.jsonPrimitive.content,
            title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            summary = o["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            source = runCatching { ActionSource.valueOf(o["source"]!!.jsonPrimitive.content) }.getOrDefault(ActionSource.AI),
            at = Instant.ofEpochMilli(o["at"]!!.jsonPrimitive.long),
            before = o["before"]?.jsonArray?.map { Snapshot.fromJson(it.jsonObject) }.orEmpty(),
            undone = o["undone"]?.jsonPrimitive?.boolean ?: false,
        )
    }
}

/**
 * Every action that ran, newest first, kept on this phone (the last
 * [MAX] entries). Entries with snapshots can be undone one by one or a
 * whole request at once.
 */
@Singleton
class ActionLog @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file: File get() = File(context.filesDir, "actions/log.json")
    private val lock = Mutex()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()
    @Volatile private var loaded = false

    private suspend fun load() {
        if (loaded) return
        _entries.value = withContext(Dispatchers.IO) {
            runCatching { json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { runCatching { LogEntry.fromJson(it.jsonObject) }.getOrNull() } }
                .getOrDefault(emptyList())
        }
        loaded = true
    }

    suspend fun ensureLoaded() = lock.withLock { load() }

    suspend fun add(entry: LogEntry) = update { listOf(entry) + it }

    suspend fun markUndone(ids: Set<String>) = update { list -> list.map { if (it.id in ids) it.copy(undone = true) else it } }

    suspend fun clear() = update { emptyList() }

    private suspend fun update(edit: (List<LogEntry>) -> List<LogEntry>) = lock.withLock {
        load()
        val next = edit(_entries.value).take(MAX)
        _entries.value = next
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonArray(next.map { it.toJson() }).toString())
            tmp.renameTo(file)
        }
    }

    companion object {
        const val MAX = 300
    }
}
