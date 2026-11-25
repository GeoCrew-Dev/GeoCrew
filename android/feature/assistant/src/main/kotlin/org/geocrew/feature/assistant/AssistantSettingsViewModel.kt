package org.geocrew.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.geocrew.core.actions.ActionRunner
import org.geocrew.core.actions.LogEntry
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.team.TeamForms
import org.geocrew.core.data.team.TeamRepository
import org.geocrew.core.model.RecordRules
import org.geocrew.core.model.action.ActionMode
import org.geocrew.core.model.action.ActionRisk
import javax.inject.Inject

@HiltViewModel
class AssistantSettingsViewModel @Inject constructor(
    private val config: AssistantConfig,
    private val client: OpenAiClient,
    private val runner: ActionRunner,
    private val projects: ProjectRepository,
    private val team: TeamRepository,
    private val teamForms: TeamForms,
) : ViewModel() {
    val values = config.values
    val log: StateFlow<List<LogEntry>> = runner.log.entries

    data class ProjectSide(val rules: RecordRules, val aiDeny: Set<ActionRisk>, val inTeam: Boolean, val canEdit: Boolean)

    val project: StateFlow<ProjectSide?> = projects.current.map { p ->
        ProjectSide(
            p.config.recordRules,
            p.config.teamAiDeny.mapNotNull { ActionRisk.of(it) }.toSet(),
            p.team != null,
            p.team == null || p.team?.role == TeamRepository.ROLE_LEADER,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()
    fun consumeNote() { _note.value = null }

    init { viewModelScope.launch { runner.log.ensureLoaded() } }

    fun saveEndpoint(base: String, model: String, key: String?) {
        if (!base.startsWith("https://") && !base.startsWith("http://")) { _note.value = "接口地址要以 https:// 开头"; return }
        config.setEndpoint(base, model)
        if (key != null) config.setKey(key)
        _note.value = "已保存"
    }

    fun clearKey() { config.setKey(null); _note.value = "已删除 API Key" }

    fun test() = viewModelScope.launch {
        _note.value = "正在连接…"
        _note.value = runCatching { client.models() }.fold(
            { list -> if (list.isEmpty()) "连接成功" else "连接成功，可用模型：" + list.take(8).joinToString("、") },
            { "连接失败：" + (it.message ?: "") },
        )
    }

    fun setMode(risk: ActionRisk, mode: ActionMode) = config.setPermissions(config.values.value.permissions.with(risk, mode))
    fun setSpeak(on: Boolean) = config.setSpeak(on)
    fun setRate(r: Float) = config.setRate(r)
    fun setHandsFree(on: Boolean) = config.setHandsFree(on)

    fun undo(entry: LogEntry) = viewModelScope.launch { _note.value = runner.undo(entry) ?: "已撤销" }
    fun undoRequest(id: String) = viewModelScope.launch { _note.value = runner.undoRequest(id) ?: "已撤销这一次的所有操作" }
    fun clearLog() = viewModelScope.launch { runner.log.clear() }

    /** Saves the record rules; a leader's change goes to the whole workspace (`record_rules`). */
    fun saveRules(rules: RecordRules) = viewModelScope.launch {
        val p = projects.current.first()
        if (p.team != null && p.team?.role != TeamRepository.ROLE_LEADER) { _note.value = "记录规则由组长设置"; return@launch }
        val h = if (p.team != null) team.handle(p.id) else null
        if (h == null) {
            projects.updateConfig(p.id) { it.copy(recordRules = rules.clean(), teamFormPending = it.teamFormPending || p.team != null) }
            _note.value = "已保存"
            return@launch
        }
        _note.value = try { teamForms.publishRules(h, rules) ?: "已同步给组员" } catch (e: java.io.IOException) { "已保存在本机，联网后会同步给组员" }
    }

    /** A leader limits what the assistant may do for anyone in the workspace (`ai_limits`). */
    fun saveTeamLimits(deny: Set<ActionRisk>) = viewModelScope.launch {
        val p = projects.current.first()
        if (p.team?.role != TeamRepository.ROLE_LEADER) { _note.value = "只有组长能设置"; return@launch }
        val h = team.handle(p.id) ?: run { _note.value = "工作组暂时连不上"; return@launch }
        _note.value = try { teamForms.publishAiLimits(h, deny) ?: "已同步给组员" } catch (e: java.io.IOException) { "已保存在本机，联网后会同步给组员" }
    }
}
