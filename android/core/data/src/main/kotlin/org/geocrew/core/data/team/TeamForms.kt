package org.geocrew.core.data.team

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.Legend
import org.geocrew.core.model.LocalGrid
import org.geocrew.core.model.PhraseGroup
import org.geocrew.core.model.FieldTerms
import org.geocrew.core.model.SharedOverlay
import org.geocrew.core.model.TeamNotice
import org.geocrew.core.model.tiles.SharedBasemap
import org.geocrew.core.network.ApiException
import org.geocrew.core.network.WorkspaceSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a linked project's shared setup, its record form, its map legend,
 * its local grid, its description words, the overlays and the base maps its
 * leaders share, its pinned notice, in step with its workspace's shared settings (protocol v0
 * §2.2, keys `form`, `legend`, `local_grid`, `phrases`, `overlays`,
 * `basemaps` and `notice`). The workspace copy wins; leaders publish their
 * local edits, and an edit based on an outdated copy is refused rather than
 * silently overwriting another leader's change. All keys move under the one
 * settings version, so they are always published and applied together.
 */
@Singleton
class TeamForms @Inject constructor(
    private val projects: ProjectRepository,
    private val overlayImages: org.geocrew.core.data.repo.OverlayImages,
) {

    /** What travels in the workspace settings. */
    data class Shared(
        val fields: List<FieldTemplate>,
        val legend: Legend,
        val localGrid: LocalGrid? = null,
        val overlays: List<SharedOverlay> = emptyList(),
        val basemaps: List<SharedBasemap> = emptyList(),
        val phrases: List<PhraseGroup> = emptyList(),
        val notice: TeamNotice? = null,
        /** Assistant limits set by the leaders (`ai_limits`), as risk ids. */
        val aiDeny: List<String> = emptyList(),
        /** How records are filled in and points named (`record_rules`); null when never shared. */
        val rules: org.geocrew.core.model.RecordRules? = null,
    )

    private fun shared(cfg: org.geocrew.core.model.ProjectConfig) =
        Shared(cfg.fields, cfg.legend, cfg.localGrid, SharedOverlay.published(cfg.overlays), cfg.teamBasemaps, cfg.phrases, cfg.teamNotice, cfg.teamAiDeny, cfg.recordRules.takeIf { it.active || it != org.geocrew.core.model.RecordRules() })

    /** Fetches the workspace copy and applies it when it is newer; publishes pending local edits first. */
    suspend fun sync(h: TeamHandle) {
        val cfg = projects.get(h.project.id)?.config ?: return
        val remote = h.api.settings(h.link.workspaceId)
        val leader = h.link.role == TeamRepository.ROLE_LEADER
        // A workspace that never had shared settings adopts the leader's existing ones.
        val adopt = remote.version == 0L && (cfg.fields.isNotEmpty() || cfg.legend.active || cfg.localGrid != null || cfg.overlays.any { it.shared } || cfg.teamBasemaps.isNotEmpty() || cfg.phrases.isNotEmpty())
        if (leader && (cfg.teamFormPending || adopt) && remote.version == cfg.teamFormVersion) {
            put(h, remote.settings, shared(cfg), remote.version)
        } else if (remote.version != cfg.teamFormVersion || cfg.teamFormPending) {
            apply(h, remote)
        }
        fetchOverlays(h)
    }

    /** Publishes the open project's shared overlays after a leader changed, added or removed one. */
    suspend fun publishOverlays(h: TeamHandle): String? = publish(h, "叠加图") { it }

    /** Downloads shared overlay pictures this phone lacks; failures wait for the next sync. */
    private suspend fun fetchOverlays(h: TeamHandle) {
        val wanted = projects.get(h.project.id)?.config?.overlays.orEmpty().filter { it.shared }
        for (o in wanted) {
            val target = overlayImages.file(o.file) ?: continue
            if (target.isFile) continue
            runCatching {
                val tmp = java.io.File.createTempFile("ovl_", ".part")
                try {
                    h.api.downloadFile(h.link.workspaceId, o.sha256, tmp)
                    tmp.inputStream().use { overlayImages.putBlocking(o.file, it) }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    /**
     * Saves [fields] for a linked project: locally at once, and to the
     * workspace when the caller leads it. Returns a message when the
     * workspace copy had moved on and was loaded instead.
     */
    suspend fun publish(h: TeamHandle, fields: List<FieldTemplate>): String? = publish(h, "表单") { it.copy(fields = fields) }

    /** Saves [legend] like [publish] saves the form. */
    suspend fun publishLegend(h: TeamHandle, legend: Legend): String? = publish(h, "图例") { it.copy(legend = legend) }

    /** Saves the local grid like [publish] saves the form, so the whole team converts alike; null stops using one. */
    suspend fun publishGrid(h: TeamHandle, grid: LocalGrid?): String? = publish(h, "本地坐标系") { it.copy(localGrid = grid) }

    /** Shares [list] as the workspace's base maps, in this order; an empty list stops sharing them. */
    suspend fun publishBasemaps(h: TeamHandle, list: List<SharedBasemap>): String? =
        publish(h, "共享底图") { it.copy(basemaps = list.filter(SharedBasemap::valid).distinctBy { b -> b.id }) }

    /** Saves the project's description words like [publish] saves the form. */
    suspend fun publishPhrases(h: TeamHandle, phrases: List<PhraseGroup>): String? =
        publish(h, "常用词") { it.copy(phrases = FieldTerms.clean(phrases)) }

    /** Sets what the in-app assistant may not do for anyone in the workspace. */
    suspend fun publishAiLimits(h: TeamHandle, deny: Set<org.geocrew.core.model.action.ActionRisk>): String? =
        publish(h, "AI 限制") { it.copy(aiDeny = deny.filter { r -> r != org.geocrew.core.model.action.ActionRisk.READ }.map { r -> r.id }.sorted()) }

    /** Shares how records are filled in and points named, for the whole workspace. */
    suspend fun publishRules(h: TeamHandle, rules: org.geocrew.core.model.RecordRules): String? =
        publish(h, "记录规则") { it.copy(rules = rules.clean()) }

    /** Posts [text] as the workspace's pinned notice, signed with the leader's nickname; blank text clears it. */
    suspend fun publishNotice(h: TeamHandle, text: String): String? =
        publish(h, "公告") { it.copy(notice = TeamNotice.of(text, java.time.Instant.now(), h.link.nickname)) }

    private suspend fun publish(h: TeamHandle, what: String, edit: (Shared) -> Shared): String? {
        val cfg = projects.get(h.project.id)?.config ?: return null
        val base = cfg.teamFormVersion
        val next = edit(shared(cfg))
        projects.updateConfig(h.project.id) { it.copy(fields = next.fields, legend = next.legend, localGrid = next.localGrid, teamBasemaps = next.basemaps, phrases = next.phrases, teamNotice = next.notice, teamAiDeny = next.aiDeny, recordRules = next.rules ?: it.recordRules, teamFormPending = true) }
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
        // A workspace whose leaders never set a grid leaves each phone's own one alone.
        val gridShared = GRID_KEY in remote.settings
        val overlaysShared = OVERLAYS_KEY in remote.settings
        val basemapsShared = BASEMAPS_KEY in remote.settings
        val phrasesShared = PHRASES_KEY in remote.settings
        projects.updateConfig(h.project.id) {
            it.copy(
                fields = shared.fields,
                legend = shared.legend,
                localGrid = if (gridShared) shared.localGrid else it.localGrid,
                overlays = if (overlaysShared) SharedOverlay.merge(it.overlays, shared.overlays) else it.overlays,
                teamBasemaps = if (basemapsShared) shared.basemaps else it.teamBasemaps,
                phrases = if (phrasesShared) shared.phrases else it.phrases,
                teamNotice = shared.notice,
                teamAiDeny = shared.aiDeny,
                recordRules = if (RULES_KEY in remote.settings) shared.rules ?: org.geocrew.core.model.RecordRules() else it.recordRules,
                teamFormVersion = remote.version,
                teamFormPending = false,
            )
        }
    }

    private suspend fun put(h: TeamHandle, current: JsonObject, shared: Shared, base: Long) {
        // Keep keys other clients put there; only `form`, `legend`, `local_grid`, `phrases`, `overlays` and `basemaps` are ours.
        // An explicit null tells members the leader stopped using a grid; no key at all means it was never shared.
        val grid = shared.localGrid?.let { encodeGrid(it) } ?: JsonNull
        val keep = shared.localGrid != null || GRID_KEY in current
        val ours = buildMap<String, JsonElement> {
            put(FORM_KEY, encode(shared.fields))
            put(LEGEND_KEY, encodeLegend(shared.legend))
            if (keep) put(GRID_KEY, grid)
            if (shared.overlays.isNotEmpty() || OVERLAYS_KEY in current) put(OVERLAYS_KEY, encodeOverlays(shared.overlays))
            if (shared.basemaps.isNotEmpty() || BASEMAPS_KEY in current) put(BASEMAPS_KEY, encodeBasemaps(shared.basemaps))
            if (shared.phrases.isNotEmpty() || PHRASES_KEY in current) put(PHRASES_KEY, encodePhrases(shared.phrases))
            // A cleared notice drops the key, so older clients and the viewer see none.
            shared.notice?.let { put(NOTICE_KEY, encodeNotice(it)) }
            if (shared.aiDeny.isNotEmpty() || AI_KEY in current) put(AI_KEY, encodeAi(shared.aiDeny))
            shared.rules?.let { put(RULES_KEY, json.encodeToJsonElement(org.geocrew.core.model.RecordRules.serializer(), it.clean())) }
        }
        // Pictures go up first, so a member never sees an overlay whose file is not there yet.
        for (o in shared.overlays) {
            val file = overlayImages.file(o.file + if (o.type == SharedOverlay.PNG) ".png" else ".jpg")?.takeIf { it.isFile } ?: continue
            if (!h.api.hasFile(h.link.workspaceId, o.file)) h.api.uploadFile(h.link.workspaceId, o.file, file, o.type)
        }
        val next = JsonObject(current - GRID_KEY - OVERLAYS_KEY - BASEMAPS_KEY - PHRASES_KEY - NOTICE_KEY - AI_KEY - RULES_KEY + ours)
        val stored = h.api.putSettings(h.link.workspaceId, next, base)
        projects.updateConfig(h.project.id) { it.copy(teamFormVersion = stored.version, teamFormPending = false) }
    }

    companion object {
        const val FORM_KEY = "form"
        const val LEGEND_KEY = "legend"
        const val GRID_KEY = "local_grid"
        const val OVERLAYS_KEY = "overlays"
        const val BASEMAPS_KEY = "basemaps"
        const val PHRASES_KEY = "phrases"
        const val NOTICE_KEY = "notice"
        const val AI_KEY = org.geocrew.core.model.action.TeamAiLimits.KEY
        const val RULES_KEY = "record_rules"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(fields: List<FieldTemplate>): JsonArray =
            JsonArray(fields.map { json.encodeToJsonElement(FieldTemplate.serializer(), it) })

        fun encodeLegend(legend: Legend): JsonElement = json.encodeToJsonElement(Legend.serializer(), legend)

        fun encodeGrid(grid: LocalGrid): JsonElement = json.encodeToJsonElement(LocalGrid.serializer(), grid)

        fun encodeOverlays(list: List<SharedOverlay>): JsonArray =
            JsonArray(list.map { json.encodeToJsonElement(SharedOverlay.serializer(), it) })

        fun encodeBasemaps(list: List<SharedBasemap>): JsonArray =
            JsonArray(list.map { json.encodeToJsonElement(SharedBasemap.serializer(), it) })

        fun decodeShared(settings: JsonObject) =
            Shared(decode(settings), decodeLegend(settings), decodeGrid(settings), decodeOverlays(settings), decodeBasemaps(settings), decodePhrases(settings), decodeNotice(settings), decodeAi(settings), decodeRules(settings))

        fun encodePhrases(list: List<PhraseGroup>): JsonArray =
            JsonArray(FieldTerms.clean(list).map { json.encodeToJsonElement(PhraseGroup.serializer(), it) })

        /** Reads the `phrases` key: groups of words, cleaned of blanks, repeats and over-long entries. */
        fun decodePhrases(settings: JsonObject): List<PhraseGroup> {
            val arr = settings[PHRASES_KEY] as? JsonArray ?: return emptyList()
            return FieldTerms.clean(arr.mapNotNull { e -> runCatching { json.decodeFromJsonElement(PhraseGroup.serializer(), e) }.getOrNull() })
        }

        fun encodeAi(deny: List<String>): JsonElement =
            org.geocrew.core.model.action.TeamAiLimits(deny.mapNotNull { org.geocrew.core.model.action.ActionRisk.of(it) }.toSet()).toJson()

        /** Reads `ai_limits`; unknown categories are dropped, reading is never forbidden. */
        fun decodeAi(settings: JsonObject): List<String> =
            org.geocrew.core.model.action.TeamAiLimits.fromJson(settings[AI_KEY] as? JsonObject).denied.map { it.id }.sorted()

        /** Reads `record_rules`; unknown items are dropped. */
        fun decodeRules(settings: JsonObject): org.geocrew.core.model.RecordRules? {
            val obj = settings[RULES_KEY] as? JsonObject ?: return null
            return runCatching { json.decodeFromJsonElement(org.geocrew.core.model.RecordRules.serializer(), obj) }.getOrNull()?.clean()
        }

        fun encodeNotice(n: TeamNotice): JsonElement = json.encodeToJsonElement(TeamNotice.serializer(), n)

        /** Reads the `notice` key; a missing, blank or unreadable one means no notice. */
        fun decodeNotice(settings: JsonObject): TeamNotice? {
            val obj = settings[NOTICE_KEY] as? JsonObject ?: return null
            return TeamNotice.clean(runCatching { json.decodeFromJsonElement(TeamNotice.serializer(), obj) }.getOrNull())
        }

        /** Reads the `basemaps` key, skipping entries that need a key or cannot be drawn. */
        fun decodeBasemaps(settings: JsonObject): List<SharedBasemap> {
            val arr = settings[BASEMAPS_KEY] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { e -> runCatching { json.decodeFromJsonElement(SharedBasemap.serializer(), e) }.getOrNull()?.takeIf(SharedBasemap::valid) }
                .distinctBy { it.id }
        }

        /** Reads the `overlays` key, skipping entries this version cannot draw. */
        fun decodeOverlays(settings: JsonObject): List<SharedOverlay> {
            val arr = settings[OVERLAYS_KEY] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { e -> runCatching { json.decodeFromJsonElement(SharedOverlay.serializer(), e) }.getOrNull()?.takeIf(SharedOverlay::valid) }
        }

        /** Reads the `local_grid` key; a missing, unknown or implausible one means none. */
        fun decodeGrid(settings: JsonObject): LocalGrid? {
            val obj = settings[GRID_KEY] as? JsonObject ?: return null
            val g = runCatching { json.decodeFromJsonElement(LocalGrid.serializer(), obj) }.getOrNull() ?: return null
            return g.takeIf { LocalGrid.problem(it) == null }
        }

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
