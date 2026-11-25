package org.geocrew.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.geocrew.core.formats.GeoJson
import org.geocrew.core.formats.ImportContext
import org.geocrew.core.model.GeoFeature
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failed call. [code] is the protocol error code (e.g. "invalid_token"), [status] the HTTP status. */
class ApiException(val status: Int, val code: String, message: String) : IOException(message) {
    val isAuth get() = status == 401
    val isForbidden get() = status == 403
}

/** Outcome of pushing one feature (protocol v0 §4.3). */
sealed interface PushOutcome {
    val id: String

    data class Applied(override val id: String, val seq: Long) : PushOutcome
    data class Duplicate(override val id: String, val seq: Long) : PushOutcome

    /** The server holds a newer revision, returned so the client can adopt it. */
    data class Stale(override val id: String, val current: GeoFeature?) : PushOutcome
}

data class PushResponse(val outcomes: List<PushOutcome>, val seq: Long)

data class PullResponse(val features: List<GeoFeature>, val seq: Long, val more: Boolean)

/**
 * Client for one GeoCrew server (protocol v0). Stateless apart from the
 * token, so one instance per workspace membership.
 */
class GeoCrewApi(
    baseUrl: String,
    private val client: OkHttpClient,
    private val token: String? = null,
    private val userAgent: String = "GeoCrew",
) {
    val base: HttpUrl = normalize(baseUrl)

    fun withToken(token: String) = GeoCrewApi(base.toString(), client, token, userAgent)

    suspend fun info(): ServerInfo = get(url("api/v0/info"), ServerInfo.serializer())

    suspend fun createWorkspace(name: String, nickname: String): JoinResult =
        post(url("api/v0/workspaces"), mapOf("name" to name, "nickname" to nickname), JoinResult.serializer())

    suspend fun join(inviteCode: String, nickname: String): JoinResult =
        post(url("api/v0/workspaces/join"), mapOf("invite_code" to inviteCode.trim(), "nickname" to nickname), JoinResult.serializer())

    suspend fun workspace(ws: String): WorkspaceInfo = get(wsUrl(ws), WorkspaceInfo.serializer())

    suspend fun members(ws: String): List<Member> = get(wsUrl(ws, "members"), ListSerializer(Member.serializer()))

    suspend fun resetInvite(ws: String): String = send(wsUrl(ws, "invite"), "POST", "{}".toRequestBody(JSON), InviteResp.serializer()).inviteCode

    suspend fun settings(ws: String): WorkspaceSettings = get(wsUrl(ws, "settings"), WorkspaceSettings.serializer())

    /**
     * Replaces the shared settings (leaders only). With [baseVersion] the
     * server refuses with 409 `stale` if someone changed them meanwhile.
     */
    suspend fun putSettings(ws: String, settings: JsonObject, baseVersion: Long?): WorkspaceSettings {
        val body = json.encodeToString(PutSettingsReq.serializer(), PutSettingsReq(settings, baseVersion)).toRequestBody(JSON)
        return send(wsUrl(ws, "settings"), "PUT", body, WorkspaceSettings.serializer())
    }

    suspend fun setRole(ws: String, member: String, role: String) {
        exec(wsUrl(ws, "members", member), "PATCH", encode(mapOf("role" to role)))
    }

    suspend fun revoke(ws: String, member: String) {
        exec(wsUrl(ws, "members", member), "DELETE", null)
    }

    suspend fun push(ws: String, projectId: String, features: List<GeoFeature>): PushResponse {
        val body = PushReq(features.map { GeoJson.encodeFeature(it, includeSync = true) })
        val r = send(wsUrl(ws, "features"), "POST", json.encodeToString(PushReq.serializer(), body).toRequestBody(JSON), PushRespWire.serializer())
        val ctx = ImportContext(projectId)
        return PushResponse(
            r.results.map { w ->
                when (w.status) {
                    "applied" -> PushOutcome.Applied(w.id, w.seq)
                    "duplicate" -> PushOutcome.Duplicate(w.id, w.seq)
                    else -> PushOutcome.Stale(w.id, w.current?.let { GeoJson.decodeFeature(it, ctx) })
                }
            },
            r.seq,
        )
    }

    suspend fun pull(ws: String, projectId: String, since: Long, limit: Int = 500): PullResponse {
        val u = wsUrl(ws, "features").newBuilder()
            .addQueryParameter("since", since.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val r = get(u, PullRespWire.serializer())
        val ctx = ImportContext(projectId)
        return PullResponse(r.features.map { GeoJson.decodeFeature(it, ctx) }, r.seq, r.more)
    }

    suspend fun history(ws: String, projectId: String, featureId: String): List<GeoFeature> {
        val list = get(wsUrl(ws, "features", featureId, "history"), ListSerializer(JsonObject.serializer()))
        val ctx = ImportContext(projectId)
        return list.map { GeoJson.decodeFeature(it, ctx) }
    }

    /** Comments with seq > [since] (protocol v0 §8), including deleted ones. */
    suspend fun comments(ws: String, since: Long, limit: Int = 500): CommentPage {
        val u = wsUrl(ws, "comments").newBuilder()
            .addQueryParameter("since", since.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return get(u, CommentPage.serializer())
    }

    /** Posts a comment. Retrying with the same [id] returns the stored one. */
    suspend fun addComment(
        ws: String,
        id: String,
        body: String,
        featureId: String? = null,
        at: List<Double>? = null,
        urgent: Boolean = false,
        rollCall: Boolean = false,
        answerTo: String? = null,
        backBy: String? = null,
        backFrom: String? = null,
    ): Comment {
        val req = json.encodeToString(
            AddCommentReq.serializer(),
            AddCommentReq(id, featureId, body, at, urgent, rollCall, answerTo, backBy, backFrom),
        ).toRequestBody(JSON)
        return send(wsUrl(ws, "comments"), "POST", req, Comment.serializer())
    }

    /** Deletes a comment (its author or a leader). */
    suspend fun deleteComment(ws: String, id: String) {
        exec(wsUrl(ws, "comments", id), "DELETE", null)
    }

    /**
     * Registers this device's push endpoint for the workspace (servers with
     * the "push" capability), replacing any earlier one.
     */
    suspend fun setPush(ws: String, endpoint: String, keys: PushKeys?, events: List<String>? = null) {
        val req = json.encodeToString(PutPushReq.serializer(), PutPushReq(endpoint, events, keys)).toRequestBody(JSON)
        exec(wsUrl(ws, "push"), "PUT", req)
    }

    /** Stops push notices for this member. */
    suspend fun deletePush(ws: String) {
        exec(wsUrl(ws, "push"), "DELETE", null)
    }

    /**
     * Makes a read-only link that opens the workspace in a browser for
     * [hours] (1 to 168). Needs the "view" capability.
     */
    suspend fun createViewLink(ws: String, hours: Int = 24): ViewLink {
        val req = json.encodeToString(ViewLinkReq.serializer(), ViewLinkReq(hours)).toRequestBody(JSON)
        return send(wsUrl(ws, "view-links"), "POST", req, ViewLink.serializer())
    }

    /** Revokes every view link this member made; returns how many were still valid. */
    suspend fun revokeViewLinks(ws: String): Int =
        send(wsUrl(ws, "view-links"), "DELETE", null, RevokedResp.serializer()).revoked

    /**
     * The full address to open for [link]. The server's path is relative to
     * its root, which is this client's base (also behind a sub-path proxy).
     */
    fun viewUrl(link: ViewLink): String = base.toString().trimEnd('/') + "/" + link.path.trimStart('/')

    /** True if the server already has the attachment. */
    suspend fun hasFile(ws: String, sha256: String): Boolean = call(request(wsUrl(ws, "files", sha256)).head().build()) { r ->
        when (r.code) {
            200 -> true
            404 -> false
            else -> throw error(r)
        }
    }

    suspend fun uploadFile(ws: String, sha256: String, file: File, contentType: String = "image/jpeg") {
        exec(wsUrl(ws, "files", sha256), "PUT", file.asRequestBody(contentType.toMediaType()))
    }

    /** Downloads an attachment into [target] (written via a temp file). */
    suspend fun downloadFile(ws: String, sha256: String, target: File) {
        call(request(wsUrl(ws, "files", sha256)).get().build()) { r ->
            if (!r.isSuccessful) throw error(r)
            val tmp = File(target.parentFile, target.name + ".part")
            r.body!!.byteStream().use { input: InputStream -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * Streams the whole workspace as a project package (records, photos and
     * the shared form) into [output] and returns the byte count. Servers
     * without the "package" capability answer 404.
     */
    suspend fun downloadPackage(ws: String, output: java.io.OutputStream): Long =
        call(request(wsUrl(ws, "export.geocrew.zip")).get().build()) { r ->
            if (!r.isSuccessful) throw error(r)
            r.body!!.byteStream().use { it.copyTo(output) }
        }

    /** ws:// URL for the realtime channel (token sent as a header, see [RealtimeClient]). */
    fun realtimeUrl(ws: String): String = wsUrl(ws, "ws").toString()

    internal fun authHeader(): String? = token?.let { "Bearer $it" }

    // --- plumbing ------------------------------------------------------------

    private fun url(path: String): HttpUrl = base.resolve(path)!!

    private fun wsUrl(ws: String, vararg segments: String): HttpUrl {
        val b = base.newBuilder().addPathSegments("api/v0/workspaces").addPathSegment(ws)
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun request(u: HttpUrl): Request.Builder = Request.Builder().url(u).header("User-Agent", userAgent).apply {
        authHeader()?.let { header("Authorization", it) }
    }

    private suspend fun <T> get(u: HttpUrl, s: KSerializer<T>): T = send(u, "GET", null, s)

    private suspend fun <T> post(u: HttpUrl, body: Map<String, String>, s: KSerializer<T>): T = send(u, "POST", encode(body), s)

    private fun encode(body: Map<String, String>): RequestBody =
        JsonObject(body.mapValues { JsonPrimitive(it.value) }).toString().toRequestBody(JSON)

    private suspend fun <T> send(u: HttpUrl, method: String, body: RequestBody?, s: KSerializer<T>): T =
        call(request(u).method(method, body).build()) { r ->
            if (!r.isSuccessful) throw error(r)
            json.decodeFromString(s, r.body!!.string())
        }

    /** A call whose response body is not needed. */
    private suspend fun exec(u: HttpUrl, method: String, body: RequestBody?) {
        call(request(u).method(method, body).build()) { r ->
            if (!r.isSuccessful) throw error(r)
        }
    }

    private fun error(r: Response): ApiException {
        val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
        val e = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        return ApiException(r.code, e?.error?.ifBlank { null } ?: "http_${r.code}", e?.message?.ifBlank { null } ?: "HTTP ${r.code}")
    }

    private suspend fun <T> call(req: Request, handle: (Response) -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val c = client.newCall(req)
            cont.invokeOnCancellation { c.cancel() }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(handle) }
                    result.fold(cont::resume, cont::resumeWithException)
                }
            })
        }
    }

    companion object {
        internal val JSON = "application/json; charset=utf-8".toMediaType()
        internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        /** Accepts "host:port", "http(s)://host/prefix" etc.; defaults to https. */
        fun normalize(input: String): HttpUrl {
            var s = input.trim()
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            if (!s.endsWith("/")) s += "/"
            return s.toHttpUrl()
        }
    }
}
