package org.geocrew.core.data.team

import okhttp3.OkHttpClient
import org.geocrew.core.data.repo.BuildDefaults
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.model.Project
import org.geocrew.core.model.TeamLink
import org.geocrew.core.network.ApiException
import org.geocrew.core.network.GeoCrewApi
import org.geocrew.core.network.JoinResult
import org.geocrew.core.network.Member
import org.geocrew.core.network.ServerInfo
import org.geocrew.core.network.WorkspaceInfo
import javax.inject.Inject
import javax.inject.Singleton

/** A project's team link plus the means to talk to its server. */
class TeamHandle(val project: Project, val link: TeamLink, val api: GeoCrewApi)

/**
 * Creating, joining and administering workspaces. Each local project can be
 * linked to one workspace; its features then sync with the team.
 */
@Singleton
class TeamRepository @Inject constructor(
    private val projects: ProjectRepository,
    private val credentials: TeamCredentials,
    private val settings: SettingsRepository,
    private val client: OkHttpClient,
    private val defaults: BuildDefaults,
) {
    fun api(serverUrl: String, token: String? = null) = GeoCrewApi(serverUrl, client, token, defaults.userAgent)

    /** Checks that a URL points at a compatible server. */
    suspend fun probe(serverUrl: String): ServerInfo {
        val info = api(serverUrl).info()
        if (info.protocol.substringBefore('.') != PROTOCOL_MAJOR) {
            throw ApiException(0, "protocol", "服务器协议版本 ${info.protocol} 与本应用 ($PROTOCOL_MAJOR) 不兼容")
        }
        return info
    }

    /** Creates a workspace as its leader and links [projectId] to it. Returns the invite code. */
    suspend fun create(projectId: String, serverUrl: String, workspaceName: String, nickname: String): String {
        probe(serverUrl)
        val r = api(serverUrl).createWorkspace(workspaceName.trim(), nickname.trim())
        link(projectId, serverUrl, r, nickname)
        return r.inviteCode.orEmpty()
    }

    /** Joins with an invite code and links [projectId] to the workspace. */
    suspend fun join(projectId: String, serverUrl: String, inviteCode: String, nickname: String): JoinResult {
        probe(serverUrl)
        val r = api(serverUrl).join(inviteCode.trim(), nickname.trim())
        link(projectId, serverUrl, r, nickname)
        return r
    }

    /** Unlinks the project; local data stays. The member token is forgotten. */
    suspend fun leave(projectId: String) {
        projects.linkTeam(projectId, null)
        credentials.clear(projectId)
    }

    suspend fun handle(projectId: String): TeamHandle? {
        val p = projects.get(projectId) ?: return null
        val link = p.team ?: return null
        val token = credentials.token(projectId) ?: return null
        return TeamHandle(p, link, api(link.serverUrl, token))
    }

    suspend fun workspace(h: TeamHandle): WorkspaceInfo = h.api.workspace(h.link.workspaceId).also { ws ->
        // Keep the cached name and role current (a leader may promote or rename).
        if (ws.name != h.link.workspaceName || ws.me.role != h.link.role || ws.me.nickname != h.link.nickname) {
            projects.linkTeam(h.project.id, h.link.copy(workspaceName = ws.name, role = ws.me.role, nickname = ws.me.nickname))
        }
    }

    suspend fun members(h: TeamHandle): List<Member> = h.api.members(h.link.workspaceId)

    /** Every revision of a feature the server kept, oldest first. */
    suspend fun history(h: TeamHandle, featureId: String): List<org.geocrew.core.model.GeoFeature> =
        h.api.history(h.link.workspaceId, h.project.id, featureId)
    suspend fun resetInvite(h: TeamHandle): String = h.api.resetInvite(h.link.workspaceId)
    suspend fun setRole(h: TeamHandle, memberId: String, role: String) = h.api.setRole(h.link.workspaceId, memberId, role)
    suspend fun revoke(h: TeamHandle, memberId: String) = h.api.revoke(h.link.workspaceId, memberId)

    private suspend fun link(projectId: String, serverUrl: String, r: JoinResult, nickname: String) {
        credentials.save(projectId, r.token)
        projects.linkTeam(
            projectId,
            TeamLink(
                serverUrl = GeoCrewApi.normalize(serverUrl).toString(),
                workspaceId = r.workspaceId,
                workspaceName = r.workspaceName,
                memberId = r.memberId,
                nickname = nickname.trim(),
                role = r.role,
                lastSeq = 0,
            ),
        )
        settings.setNickname(nickname)
    }

    companion object {
        const val PROTOCOL_MAJOR = "0"
        const val ROLE_LEADER = "leader"
        const val ROLE_MEMBER = "member"
        const val ROLE_READONLY = "readonly"
    }
}
