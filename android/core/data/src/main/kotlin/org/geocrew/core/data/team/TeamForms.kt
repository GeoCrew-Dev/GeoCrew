package org.geocrew.core.data.team

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.Legend
import org.geocrew.core.network.ApiException
import org.geocrew.core.network.WorkspaceSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a linked project's shared setup, its record form and its map
 * legend, in step with its workspace's shared settings (protocol v0 §2.2,
 * keys `form` and `legend`). The workspace copy wins; leaders publish their
 * local edits, and an edit based on an outdated copy is refused rather than
 * silently overwriting another leader's change. Both keys move under the one
 * settings version, so they are always published and applied together.
 */
@Singleton
class TeamForms @Inject constructor(private val projects: ProjectRepository) {

    /** What travels in the workspace settings. */
    data class Shared(val fields: List<FieldTemplate>, val legend: Legend)

    /** Fetches the workspace copy and applies it when it is newer; publishes pending local edits first. */
    suspend fun sync(h: TeamHandle) {
        val cfg = projects.get(h.project.id)?.config ?: return
        val remote = h.api.settings(h.link.workspaceId)
        val leader = h.link.role == TeamRepository.ROLE_LEADER
        // A workspace that never had shared settings adopts the leader's existing ones.
        val adopt = remote.version == 0L && (cfg.fields.isNotEmpty() || cfg.legend.active)
        if (leader && (cfg.teamFormPending || adopt) && remote.version == cfg.teamFormVersion) {
            put(h, remote.settings, Shared(cfg.fields, cfg.legend), remote.version)
            return
        }
        if (remote.version != cfg.teamFormVersion || cfg.teamFormPending) apply(h, remote)
    }

    /**
     * Saves [fields] for a linked project: locally at once, and to the
     * workspace when the caller leads it. Returns a message when the
     * workspace copy had moved on and was loaded instead.
     */
    suspend fun publish(h: TeamHandle, fields: List<FieldTemplate>): String? = publish(h, "表单") { it.copy(fields = fields) }

    /** Saves [legend] like [publish] saves the form. */
    suspend fun publishLegend(h: TeamHandle, legend: Legend): String? = publish(h, "图例") { it.copy(legend = legend) }

    private suspend fun publish(h: TeamHandle, what: String, edit: (Shared) -> Shared): String? {
        val cfg = projects.get(h.project.id)?.config ?: return null
        val base = cfg.teamFormVersion
        val next = edit(Shared(cfg.fields, cfg.legend))
        projects.updateConfig(h.project.id) { it.copy(fields = next.fields, legend = next.legend, teamFormPending = true) }
        val moved = "组内${what}刚被其他组长修改过，已换成最新版本，请在此基础上再改"
        val remote = h.api.settings(h.link.workspaceId)
        if (remote.version != base) {
            apply(h, remote)
            return moved
        }
        return try {
            put(h, remote.settings, next, base)
            null
        } catch (e: ApiException) {
            if (e.status != 409) throw e
            sync(h)
            moved
        }
    }

    private suspend fun apply(h: TeamHandle, remote: WorkspaceSettings) {
        val shared = decodeShared(remote.settings)
        projects.updateConfig(h.project.id) {
            it.copy(fields = shared.fields, legend = shared.legend, teamFormVersion = remote.version, teamFormPending = false)
        }
    }

    private suspend fun put(h: TeamHandle, current: JsonObject, shared: Shared, base: Long) {
        // Keep keys other clients put there; only `form` and `legend` are ours.
        val next = JsonObject(current + (FORM_KEY to encode(shared.fields)) + (LEGEND_KEY to encodeLegend(shared.legend)))
        val stored = h.api.putSettings(h.link.workspaceId, next, base)
        projects.updateConfig(h.project.id) { it.copy(teamFormVersion = stored.version, teamFormPending = false) }
    }

    companion object {
        const val FORM_KEY = "form"
        const val LEGEND_KEY = "legend"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(fields: List<FieldTemplate>): JsonArray =
            JsonArray(fields.map { json.encodeToJsonElement(FieldTemplate.serializer(), it) })

        fun encodeLegend(legend: Legend): JsonElement = json.encodeToJsonElement(Legend.serializer(), legend)

        fun decodeShared(settings: JsonObject) = Shared(decode(settings), decodeLegend(settings))

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

        /** Reads the `legend` key; a missing or unreadable one means no legend. */
        fun decodeLegend(settings: JsonObject): Legend {
            val obj = settings[LEGEND_KEY] as? JsonObject ?: return Legend()
            val raw = runCatching { json.decodeFromJsonElement(Legend.serializer(), obj) }.getOrNull() ?: return Legend()
            return Legend.clean(raw)
        }
    }
}
