package org.geocrew.core.data.team

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.network.ApiException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a linked project's record form in step with its workspace's shared
 * settings (protocol v0 §2.2, key `form`). The workspace copy wins; leaders
 * publish their local edits, and an edit based on an outdated copy is
 * refused rather than silently overwriting another leader's change.
 */
@Singleton
class TeamForms @Inject constructor(private val projects: ProjectRepository) {

    /** Fetches the workspace form and applies it when it is newer; publishes pending local edits first. */
    suspend fun sync(h: TeamHandle) {
        val cfg = projects.get(h.project.id)?.config ?: return
        val remote = h.api.settings(h.link.workspaceId)
        val leader = h.link.role == TeamRepository.ROLE_LEADER
        // A workspace that never had a form adopts the leader's existing one.
        val adopt = remote.version == 0L && cfg.fields.isNotEmpty()
        if (leader && (cfg.teamFormPending || adopt) && remote.version == cfg.teamFormVersion) {
            put(h, remote.settings, cfg.fields, remote.version)
            return
        }
        if (remote.version != cfg.teamFormVersion || cfg.teamFormPending) {
            val fields = decode(remote.settings)
            projects.updateConfig(h.project.id) { it.copy(fields = fields, teamFormVersion = remote.version, teamFormPending = false) }
        }
    }

    /**
     * Saves [fields] for a linked project: locally at once, and to the
     * workspace when the caller leads it. Returns a message when the
     * workspace copy had moved on and was loaded instead.
     */
    suspend fun publish(h: TeamHandle, fields: List<FieldTemplate>): String? {
        val base = projects.get(h.project.id)?.config?.teamFormVersion ?: return null
        projects.updateConfig(h.project.id) { it.copy(fields = fields, teamFormPending = true) }
        val remote = h.api.settings(h.link.workspaceId)
        if (remote.version != base) {
            projects.updateConfig(h.project.id) { it.copy(fields = decode(remote.settings), teamFormVersion = remote.version, teamFormPending = false) }
            return "组内表单刚被其他组长修改过，已换成最新版本，请在此基础上再改"
        }
        return try {
            put(h, remote.settings, fields, base)
            null
        } catch (e: ApiException) {
            if (e.status != 409) throw e
            sync(h)
            "组内表单刚被其他组长修改过，已换成最新版本，请在此基础上再改"
        }
    }

    private suspend fun put(h: TeamHandle, current: JsonObject, fields: List<FieldTemplate>, base: Long) {
        // Keep keys other clients put there; only `form` is ours.
        val next = JsonObject(current + (FORM_KEY to encode(fields)))
        val stored = h.api.putSettings(h.link.workspaceId, next, base)
        projects.updateConfig(h.project.id) { it.copy(teamFormVersion = stored.version, teamFormPending = false) }
    }

    companion object {
        const val FORM_KEY = "form"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(fields: List<FieldTemplate>): JsonArray =
            JsonArray(fields.map { json.encodeToJsonElement(FieldTemplate.serializer(), it) })

        /** Reads the `form` key, skipping entries this version cannot use. */
        fun decode(settings: JsonObject): List<FieldTemplate> {
            val arr = settings[FORM_KEY] as? JsonArray ?: return emptyList()
            val out = mutableListOf<FieldTemplate>()
            for (e in arr) {
                val f = runCatching { json.decodeFromJsonElement(FieldTemplate.serializer(), e) }.getOrNull() ?: continue
                if (FieldTemplate.problem(f, out) == null) out += f
            }
            return out
        }
    }
}
