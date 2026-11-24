package org.geocrew.feature.team

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.AppSettings
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.data.team.TeamRepository
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.data.team.TeamStatus
import org.geocrew.core.model.Project
import org.geocrew.core.network.ApiException
import org.geocrew.core.network.Invite
import org.geocrew.core.network.Member
import org.geocrew.feature.team.push.TeamPush
import java.io.IOException
import javax.inject.Inject

/** Navigation arguments a `geocrew://join` link fills in. */
const val INVITE_SERVER_ARG = "server"
const val INVITE_CODE_ARG = "code"

/** Form for creating or joining a workspace. */
data class ConnectForm(
    val server: String = "",
    /** Workspace name when creating, invite code when joining. */
    val value: String = "",
    val nickname: String = "",
    val share: Boolean = true,
)

@HiltViewModel
class TeamViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val projects: ProjectRepository,
    private val team: TeamRepository,
    private val session: TeamSession,
    private val settingsRepo: SettingsRepository,
    private val push: TeamPush,
    features: FeatureRepository,
) : ViewModel() {
    val project: StateFlow<Project?> = projects.current.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val status: StateFlow<TeamStatus> = session.status
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _invite = MutableStateFlow<String?>(null)
    val invite: StateFlow<String?> = _invite.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Task progress per member id, from the project's synced records (protocol v0 §6.1). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val progress: StateFlow<Map<String, MemberProgress>> = projects.current
        .flatMapLatest { p -> features.observeProject(p.id) }
        .map { list -> MemberProgress.of(list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Whether the linked server offers push notices (its "push" capability). */
    private val _pushAvailable = MutableStateFlow(false)
    val pushAvailable: StateFlow<Boolean> = _pushAvailable.asStateFlow()

    /** An invite that arrived through a link or QR code, waiting to be confirmed. */
    private val _incoming = MutableStateFlow(Invite.of(savedState[INVITE_SERVER_ARG], savedState[INVITE_CODE_ARG]))
    val incoming: StateFlow<Invite?> = _incoming.asStateFlow()

    fun consumeIncoming() {
        _incoming.value = null
        // Forget the link's arguments too, so it is not offered again after rotation.
        savedState[INVITE_SERVER_ARG] = null
        savedState[INVITE_CODE_ARG] = null
    }

    /** Opens a fresh project so an invite can be accepted without touching the linked one. */
    fun newProjectFor(invite: Invite) = act(null) {
        val p = projects.create("新工程")
        projects.open(p.id)
        _incoming.value = invite
    }

    init {
        // Reload the roster whenever the project's link changes or the channel (re)connects.
        viewModelScope.launch {
            project.map { it?.team?.workspaceId }.distinctUntilChanged().collect { refresh() }
        }
        viewModelScope.launch {
            session.status.map { it.online }.distinctUntilChanged().collect { if (_members.value.isNotEmpty()) refresh() }
        }
    }

    fun consumeMessage() { _message.value = null }

    fun create(form: ConnectForm) = act("已创建工作组") {
        val p = projects.ensureCurrent()
        settingsRepo.setSharePosition(form.share)
        _invite.value = team.create(p.id, form.server, form.value, form.nickname)
    }

    fun join(form: ConnectForm) = act("已加入工作组") {
        val p = projects.ensureCurrent()
        settingsRepo.setSharePosition(form.share)
        team.join(p.id, form.server, form.value, form.nickname)
    }

    fun refresh() {
        viewModelScope.launch {
            val h = project.value?.id?.let { team.handle(it) } ?: run {
                _members.value = emptyList(); _invite.value = null; return@launch
            }
            runCatching {
                val ws = team.workspace(h)
                _invite.value = ws.inviteCode
                _members.value = team.members(h)
            }
            runCatching { _pushAvailable.value = "push" in h.api.info().capabilities }
        }
    }

    fun resetInvite() = withHandle("邀请码已更换，旧码失效") { h -> _invite.value = team.resetInvite(h) }

    fun setRole(member: Member, role: String) = withHandle("已将 ${member.nickname} 设为${roleLabel(role)}") { h ->
        team.setRole(h, member.id, role)
        _members.value = team.members(h)
    }

    fun revoke(member: Member) = withHandle("已移出 ${member.nickname}") { h ->
        team.revoke(h, member.id)
        _members.value = team.members(h)
    }

    fun leave() = act("已退出工作组，本地数据保留") {
        project.value?.let { p ->
            if (p.config.pushEnabled) push.disable(p.id)
            team.leave(p.id)
        }
        _members.value = emptyList()
        _invite.value = null
    }

    fun syncNow() = act(null) {
        val r = session.syncNow() ?: return@act
        _message.value = "同步完成：上传 ${r.pushed} 条，下载 ${r.pulled} 条" +
            (if (r.overwritten > 0) "，${r.overwritten} 条本地改动被组员更新覆盖" else "")
    }

    /** Saves the whole workspace as a project package at [uri] (from the system file picker). */
    fun downloadPackage(context: android.content.Context, uri: android.net.Uri) = withHandle("已保存工作组数据，可在工程列表里导入为新工程") { h ->
        val bytes = try {
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                h.api.downloadPackage(h.link.workspaceId, out)
            } ?: throw IOException("无法写入所选文件")
        } catch (e: ApiException) {
            // Delete the empty file the picker created.
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            if (e.status == 404) throw IllegalStateException("服务器版本较旧，不支持下载整组数据") else throw e
        }
        if (bytes == 0L) throw IOException("服务器没有返回数据")
    }

    /**
     * Turns background notices on through the user's UnifiedPush
     * distributor; the server learns the endpoint once the distributor
     * hands it over (see [TeamPush.onNewEndpoint]).
     */
    fun enablePush(activity: android.app.Activity) {
        val id = project.value?.id ?: return
        if (!push.hasDistributor()) {
            _message.value = "没有找到推送分发应用。请先安装 ntfy 等支持 UnifiedPush 的应用"
            return
        }
        push.enable(activity, id) { ok ->
            _message.value = if (ok) "正在开启后台通知" else "没有选择推送分发应用，后台通知未开启"
        }
    }

    fun disablePush() = act("已关闭后台通知") {
        project.value?.id?.let { push.disable(it) }
    }

    fun setShare(on: Boolean) {
        viewModelScope.launch { settingsRepo.setSharePosition(on) }
    }

    private fun withHandle(done: String, block: suspend (org.geocrew.core.data.team.TeamHandle) -> Unit) = act(done) {
        val h = project.value?.id?.let { team.handle(it) } ?: throw IllegalStateException("当前工程未连接工作组")
        block(h)
    }

    private fun act(done: String?, block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                done?.let { _message.value = it }
            } catch (e: ApiException) {
                _message.value = explain(e)
            } catch (e: IOException) {
                _message.value = if (e.message?.contains("CLEARTEXT", ignoreCase = true) == true) {
                    "只能通过 HTTPS 连接服务器，请为服务器配置证书"
                } else {
                    "连接失败：${e.message ?: "请检查服务器地址和网络"}"
                }
            } catch (e: IllegalArgumentException) {
                _message.value = "地址无效：${e.message}"
            } catch (e: IllegalStateException) {
                _message.value = e.message
            } finally {
                _busy.value = false
            }
        }
    }

    private fun explain(e: ApiException) = when {
        e.code == "protocol" -> e.message
        e.status == 404 && e.code == "bad_invite" -> "邀请码无效或已更换"
        e.status == 404 -> "服务器上找不到对应内容（${e.code}）"
        e.status == 403 && e.code == "create_disabled" -> "这台服务器不接受新建工作组，请向组长要邀请码加入"
        e.status == 429 -> "尝试次数过多，请稍后再试"
        e.status == 401 -> "身份已失效，请重新加入"
        e.status == 403 -> "当前角色没有这个权限"
        e.status == 400 -> "输入有误：${e.message}"
        else -> e.message ?: "请求失败（${e.status}）"
    }

    companion object {
        fun roleLabel(role: String) = when (role) {
            TeamRepository.ROLE_LEADER -> "组长"
            TeamRepository.ROLE_MEMBER -> "组员"
            TeamRepository.ROLE_READONLY -> "只读成员"
            else -> role
        }
    }
}
