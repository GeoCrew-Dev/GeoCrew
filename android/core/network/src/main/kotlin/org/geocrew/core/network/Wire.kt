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
    val online: List<String> = emptyList(),
)

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
