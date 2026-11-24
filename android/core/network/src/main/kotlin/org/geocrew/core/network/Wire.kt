package org.geocrew.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Protocol v0 payloads. Field names follow docs/protocol-v0.md. */

@Serializable
data class ServerInfo(
    val name: String = "",
    val protocol: String,
    val version: String = "",
    val capabilities: List<String> = emptyList(),
    @SerialName("max_upload_bytes") val maxUploadBytes: Long = 0,
    @SerialName("max_push_batch") val maxPushBatch: Int = 500,
)

@Serializable
data class JoinResult(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("workspace_name") val workspaceName: String = "",
    @SerialName("member_id") val memberId: String,
    val role: String,
    val token: String,
    @SerialName("invite_code") val inviteCode: String? = null,
)

@Serializable
data class Member(
    val id: String,
    val nickname: String,
    val role: String,
    @SerialName("joined_at") val joinedAt: String = "",
    val revoked: Boolean = false,
)

@Serializable
data class WorkspaceInfo(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_seq") val lastSeq: Long = 0,
    val me: Member,
    @SerialName("invite_code") val inviteCode: String? = null,
    /** 0 until a leader first sets the shared settings; servers without the capability omit it. */
    @SerialName("settings_version") val settingsVersion: Long = 0,
    val online: List<String> = emptyList(),
)

/** A workspace's shared settings (protocol v0 §2.2); the server does not interpret them. */
@Serializable
data class WorkspaceSettings(val version: Long = 0, val settings: JsonObject = JsonObject(emptyMap()))

@Serializable
internal data class PutSettingsReq(val settings: JsonObject, @SerialName("base_version") val baseVersion: Long? = null)

/**
 * A comment on a record or, without [featureId], a message to the whole team
 * (protocol v0 §8). [at] is an optional `[lng, lat]` for team messages.
 */
@Serializable
data class Comment(
    val id: String,
    @SerialName("feature_id") val featureId: String? = null,
    val body: String = "",
    val at: List<Double>? = null,
    @SerialName("author_id") val authorId: String = "",
    val nickname: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val seq: Long = 0,
    val deleted: Boolean = false,
)

@Serializable
data class CommentPage(val comments: List<Comment> = emptyList(), val seq: Long, val more: Boolean = false)

@Serializable
internal data class AddCommentReq(
    val id: String,
    @SerialName("feature_id") val featureId: String? = null,
    val body: String,
    val at: List<Double>? = null,
)

/** A device's Web Push keys (base64url); the server encrypts notices with them. */
@Serializable
data class PushKeys(val p256dh: String, val auth: String)

@Serializable
internal data class PutPushReq(val endpoint: String, val events: List<String>? = null, val keys: PushKeys? = null)

/**
 * What a push carries (protocol v0 §9): only ids, never content. [type] is
 * "message", "comment" or "task"; unknown types should be ignored.
 */
@Serializable
data class PushNotice(
    val type: String,
    val workspace: String,
    val feature: String? = null,
    val from: String? = null,
    val seq: Long = 0,
) {
    companion object {
        /** Parses a notice, or returns null for anything else. */
        fun parse(bytes: ByteArray): PushNotice? =
            runCatching { GeoCrewApi.json.decodeFromString(serializer(), bytes.decodeToString()) }.getOrNull()
    }
}

@Serializable
internal data class InviteResp(@SerialName("invite_code") val inviteCode: String)

@Serializable
internal data class PushReq(val features: List<JsonObject>)

@Serializable
internal data class PushResultWire(val id: String, val status: String, val seq: Long = 0, val current: JsonObject? = null)

@Serializable
internal data class PushRespWire(val results: List<PushResultWire>, val seq: Long)

@Serializable
internal data class PullRespWire(val features: List<JsonObject>, val seq: Long, val more: Boolean = false)

@Serializable
internal data class ErrorBody(val error: String = "", val message: String = "")
