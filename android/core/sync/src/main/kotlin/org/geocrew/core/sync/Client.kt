package org.geocrew.core.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** How the client reaches a server: one request, its answer (an HTTP connection, or a fake in tests). */
fun interface Transport {
    /** Sends [body] (JSON, or null) to [path] with [method], signed in with [token] when there is one. */
    fun send(method: String, path: String, token: String?, body: String?): Reply
}

/** A server's answer: HTTP [status] and the [body] as text. */
data class Reply(val status: Int, val body: String)

/**
 * A refusal (protocol v1 3.2): HTTP [status], the machine [code] to act on,
 * the [body] with any keys the code brings (a stale layer, the missing
 * permission); its message is for people.
 */
class ServerError(val status: Int, val code: String, message: String, val body: JsonObject?) : Exception(message)

/** What a server says of itself (protocol v1 3.3). */
data class ServerInfo(
    val protocol: Int,
    val version: String,
    val capabilities: Set<String>,
    val maxPushBatch: Int,
    val maxPullBatch: Int,
    val maxFileSize: Long,
)

/** Signed in to a workspace (protocol v1 3.4): who the member is, and the device's token, given only once. */
data class Joined(val workspace: String, val workspaceName: String, val member: String, val leader: String, val inviteCode: String?, val token: String)

/** A field of a shared layer (protocol v1 7.2), by the stable id rows use. */
data class FieldDef(val id: String, val name: String, val type: String, val options: JsonArray? = null)

/** A shared layer to create (protocol v1 5.6): its id is the client's, kept from the local project. */
data class LayerSpec(val id: String, val name: String, val geometryType: String, val crs: String, val fields: List<FieldDef>)

/** One row's edits to push (protocol v1 6.2, 8.1); [id] is the change's, the same again when it is retried. */
data class PushChange(val id: String, val layer: String, val row: String, val baseSeq: Long, val set: Map<String, FieldEdit>, val delete: FieldEdit? = null)

/** What became of one pushed change (protocol v1 8.1). */
data class PushResult(val id: String, val status: String, val seq: Long?, val conflicts: List<Conflict>, val error: String?, val message: String?) {
    companion object {
        const val APPLIED = "applied"
        const val DUPLICATE = "duplicate"
        const val REJECTED = "rejected"
    }
}

/** A shared layer as a pull gives it (protocol v1 7.1, 8.2). */
data class PulledLayer(
    val id: String,
    val geometryType: String,
    val crs: String,
    val fields: List<FieldDef>,
    val schemaVersion: Long,
    val name: String?,
    val deleted: Boolean,
    val seq: Long,
)

/** A row as a pull gives it: every field with who set it, when and at which seq. */
data class PulledRow(val layer: String, val id: String, val fields: Map<String, FieldValue>, val deleted: Boolean, val conflicted: Boolean)

/** A conflict record (protocol v1 6.5) and where it is. */
data class PulledConflict(val id: String, val layer: String, val row: String, val seq: Long, val resolved: Boolean, val conflict: Conflict)

/** One page of changes (protocol v1 8.2): [seq] is the next since; [more] means pull again at once. */
data class Pulled(val layers: List<PulledLayer>, val rows: List<PulledRow>, val conflicts: List<PulledConflict>, val seq: Long, val more: Boolean)

/**
 * A team's server, protocol v1 (protocol-v1.md): the calls a phone makes to
 * join a workspace and keep a project in sync with it. Each call blocks;
 * a refusal is a [ServerError], a network failure an [IOException].
 */
class SyncClient(private val transport: Transport) {
    private val json = Json { ignoreUnknownKeys = true }

    fun info(): ServerInfo {
        val o = call("GET", "/api/v1/info", null, null)
        val limits = o.obj("limits")
        return ServerInfo(
            protocol = o.int("protocol"),
            version = o.text("version").orEmpty(),
            capabilities = o.array("capabilities").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
            maxPushBatch = limits.int("max_push_batch"),
            maxPullBatch = limits.int("max_pull_batch"),
            maxFileSize = limits.long("max_file_size"),
        )
    }

    /** A new workspace with the caller as its leader. */
    fun create(name: String, nickname: String, device: String): Joined =
        joined(call("POST", "/api/v1/workspaces", null, buildJsonObject { put("name", name); put("nickname", nickname); put("device", device) }))

    /** Joins with an invite code as a new member. */
    fun join(inviteCode: String, nickname: String, device: String): Joined =
        joined(call("POST", "/api/v1/join", null, buildJsonObject { put("invite_code", inviteCode); put("nickname", nickname); put("device", device) }))

    /** Signs this device in as the member a transfer code was issued for. */
    fun joinWithTransferCode(code: String, device: String): Joined =
        joined(call("POST", "/api/v1/join", null, buildJsonObject { put("transfer_code", code); put("device", device) }))

    /** Creates a shared layer; the same id again with the same schema gives the layer as it is (a retry). */
    fun createLayer(workspace: String, token: String, layer: LayerSpec): PulledLayer {
        val body = buildJsonObject {
            put("id", layer.id)
            put("name", layer.name)
            put("geometry_type", layer.geometryType)
            put("crs", layer.crs)
            putJsonArray("fields") { for (f in layer.fields) add(fieldJson(f)) }
        }
        return layerOf(call("POST", "/api/v1/workspaces/${segment(workspace)}/layers", token, body))
    }

    /** Pushes [changes] (at most max_push_batch, one a row), the results in their order, and the workspace's latest seq. */
    fun push(workspace: String, token: String, clientNow: Instant, changes: List<PushChange>): Pair<List<PushResult>, Long> {
        val body = buildJsonObject {
            put("client_now", clientNow.toString())
            putJsonArray("changes") {
                for (c in changes) add(
                    buildJsonObject {
                        put("id", c.id)
                        put("layer", c.layer)
                        put("row", c.row)
                        put("base_seq", c.baseSeq)
                        if (c.set.isNotEmpty()) put("set", JsonObject(c.set.mapValues { (_, e) -> editJson(e) }))
                        c.delete?.let { put("delete", editJson(it)) }
                    },
                )
            }
        }
        val o = call("POST", "/api/v1/workspaces/${segment(workspace)}/rows", token, body)
        val results = o.array("results").map { r ->
            val x = r.jsonObject
            PushResult(
                id = x.text("id").orEmpty(),
                status = x.text("status").orEmpty(),
                seq = (x["seq"] as? JsonPrimitive)?.longOrNull,
                conflicts = (x["conflicts"] as? JsonArray).orEmpty().map { conflictOf(it.jsonObject) },
                error = x.text("error"),
                message = x.text("message"),
            )
        }
        return results to o.long("seq")
    }

    /** What changed after [since], oldest first; [limit] at most max_pull_batch. */
    fun pull(workspace: String, token: String, since: Long, limit: Int? = null): Pulled {
        val query = "since=$since" + (limit?.let { "&limit=$it" } ?: "")
        val o = call("GET", "/api/v1/workspaces/${segment(workspace)}/changes?$query", token, null)
        return Pulled(
            layers = o.array("layers").map { layerOf(it.jsonObject) },
            rows = o.array("rows").map { r ->
                val x = r.jsonObject
                PulledRow(
                    layer = x.text("layer").orEmpty(),
                    id = x.text("id").orEmpty(),
                    fields = (x["fields"] as? JsonObject).orEmpty().mapValues { (_, f) -> fieldValueOf(f.jsonObject) },
                    deleted = (x["deleted"] as? JsonObject)?.let { fieldValueOf(it).value == JsonPrimitive(true) } == true,
                    conflicted = (x["conflicted"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            },
            conflicts = o.array("conflicts").map { pulledConflictOf(it.jsonObject) },
            seq = o.long("seq"),
            more = (o["more"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /** Resolves conflict [conflict] by keeping the current value ([KEPT]) or putting the losing one back ([LOST]). */
    fun resolve(workspace: String, token: String, conflict: String, choose: String): PulledConflict {
        val o = call("POST", "/api/v1/workspaces/${segment(workspace)}/conflicts/${segment(conflict)}/resolve", token, buildJsonObject { put("choose", choose) })
        return pulledConflictOf(o.obj("conflict"))
    }

    companion object {
        /** Resolving a conflict: keep the current value, only marking the conflict resolved. */
        const val KEPT = "kept"

        /** Resolving a conflict: put the losing value back, as a change by the one resolving it. */
        const val LOST = "lost"
    }

    private fun call(method: String, path: String, token: String?, body: JsonObject?): JsonObject {
        val reply = transport.send(method, path, token, body?.toString())
        val parsed = runCatching { json.parseToJsonElement(reply.body) }.getOrNull()
        if (reply.status !in 200..299) {
            val o = parsed as? JsonObject
            throw ServerError(reply.status, o?.text("error") ?: "http_${reply.status}", o?.text("message") ?: reply.body.take(200), o)
        }
        return parsed as? JsonObject ?: throw IOException("the server's answer to $method $path is not a JSON object")
    }

    private fun joined(o: JsonObject): Joined {
        val ws = o.obj("workspace")
        return Joined(ws.text("id").orEmpty(), ws.text("name").orEmpty(), o.obj("member").text("id").orEmpty(), ws.text("leader").orEmpty(), ws.text("invite_code"), o.text("token").orEmpty())
    }

    private fun layerOf(o: JsonObject) = PulledLayer(
        id = o.text("id").orEmpty(),
        geometryType = o.text("geometry_type").orEmpty(),
        crs = o.text("crs").orEmpty(),
        fields = o.array("fields").map { f ->
            val x = f.jsonObject
            FieldDef(x.text("id").orEmpty(), x.text("name").orEmpty(), x.text("type").orEmpty(), x["options"] as? JsonArray)
        },
        schemaVersion = o.long("schema_version"),
        name = (o["name"] as? JsonObject)?.let { (it["value"] as? JsonPrimitive)?.contentOrNull },
        deleted = (o["deleted"] as? JsonObject)?.let { it["value"] == JsonPrimitive(true) } == true,
        seq = o.long("seq"),
    )

    private fun pulledConflictOf(o: JsonObject) =
        PulledConflict(o.text("id").orEmpty(), o.text("layer").orEmpty(), o.text("row").orEmpty(), o.long("seq"), (o["resolved"] as? JsonPrimitive)?.booleanOrNull == true, conflictOf(o))

    private fun conflictOf(o: JsonObject) = Conflict(
        kind = o.text("kind").orEmpty(),
        field = o.text("field"),
        kept = (o["kept"] as? JsonObject)?.let(::fieldValueOf),
        lost = (o["lost"] as? JsonObject)?.let(::fieldValueOf),
    )

    private fun fieldValueOf(o: JsonObject) = FieldValue(o["value"] ?: JsonNull, o.text("by").orEmpty(), Instant.parse(o.text("at") ?: "1970-01-01T00:00:00Z"), o.long("seq"))

    private fun fieldJson(f: FieldDef) = buildJsonObject {
        put("id", f.id)
        put("name", f.name)
        put("type", f.type)
        f.options?.let { put("options", it) }
    }

    private fun editJson(e: FieldEdit) = buildJsonObject {
        put("value", e.value)
        put("edited_at", e.editedAt.toString())
    }

    // A path segment; the Charset overload of encode is not on Android before API 33.
    private fun segment(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0
    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: buildJsonArray { }
}

/**
 * The transport over HTTP(S) to the server at [base] (its root, as an invite
 * gives it), with [HttpURLConnection]: no library of its own. PATCH is not
 * among the methods it sends; sync does not use it. Certificate pinning
 * (ADR 0014) comes with the join screen.
 */
class HttpTransport(private val base: String, private val timeoutMs: Int = 30_000) : Transport {
    override fun send(method: String, path: String, token: String?, body: String?): Reply {
        val c = URI(base.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "application/json")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            return Reply(status, stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
        } finally {
            c.disconnect()
        }
    }
}

