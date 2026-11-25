package org.geocrew.feature.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.team.CommentItem
import org.geocrew.core.data.team.TeamComments
import org.geocrew.core.data.team.TeamRepository
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.model.LngLat
import org.geocrew.core.network.ApiException
import java.io.IOException
import javax.inject.Inject

/** A message plus the title of the record it is about, if any and still there. */
data class ChatRow(val item: CommentItem, val featureTitle: String?)

/** Someone on the roll and their latest answer, if any. */
data class RosterRow(val memberId: String, val nickname: String, val answer: CommentItem?)

/** The current roll call as the chat shows it. */
data class RollCallUi(
    val call: CommentItem,
    /** This user's latest answer. */
    val mine: CommentItem?,
    /** Everyone asked (all but the caller), not yet answered first; null while the member list is unknown. */
    val roster: List<RosterRow>?,
    /** Answers received, for when the roster is unknown. */
    val answers: List<CommentItem>,
)

data class ChatUi(
    val linked: Boolean = false,
    val workspaceName: String = "",
    val canPost: Boolean = false,
    val isLeader: Boolean = false,
    /** Where the map was last left, offered as the message's position. */
    val mapCenter: LngLat? = null,
    val items: List<ChatRow> = emptyList(),
    /** The workspace's pinned notice, kept above the messages. */
    val notice: org.geocrew.core.model.TeamNotice? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TeamChatViewModel @Inject constructor(
    projects: ProjectRepository,
    features: FeatureRepository,
    private val comments: TeamComments,
    private val session: TeamSession,
    private val location: org.geocrew.core.sensors.LocationSource,
    private val team: TeamRepository,
    private val rollCalls: org.geocrew.core.sensors.RollCallActions,
    private val outingActions: org.geocrew.core.sensors.OutingActions,
) : ViewModel() {
    private val project = projects.current

    val ui: StateFlow<ChatUi> = project
        .distinctUntilChanged { a, b -> a.id == b.id && a.team == b.team && a.config.view == b.config.view && a.config.teamNotice == b.config.teamNotice }
        .flatMapLatest { p ->
            val link = p.team ?: return@flatMapLatest flowOf(ChatUi())
            val titles = features.observeProject(p.id).map { list -> list.associate { it.id to (it.title?.ifBlank { null } ?: "未命名记录") } }
            combine(comments.recent(p.id, link.memberId), titles) { items, names ->
                ChatUi(
                    linked = true,
                    workspaceName = link.workspaceName,
                    canPost = link.role != TeamRepository.ROLE_READONLY,
                    isLeader = link.role == TeamRepository.ROLE_LEADER,
                    mapCenter = p.config.view?.let { runCatching { LngLat(it.lng, it.lat) }.getOrNull() },
                    items = items.map { ChatRow(it, it.featureId?.let(names::get)) },
                    notice = p.config.teamNotice,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUi())

    private val members = MutableStateFlow<List<org.geocrew.core.network.Member>?>(null)

    private val currentCall = project
        .map { p -> p.team?.let { p.id to it.memberId } }
        .distinctUntilChanged()
        .flatMapLatest { k -> if (k == null) flowOf(null) else comments.rollCall(k.first, k.second) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The roll call of the last day, with who has answered. */
    val rollCall: StateFlow<RollCallUi?> = combine(currentCall, members) { rc, list ->
        rc ?: return@combine null
        val roster = list?.filter { !it.revoked && it.id != rc.call.authorId }
            ?.map { RosterRow(it.id, it.nickname, rc.answerOf(it.id)) }
            ?.sortedWith(compareBy<RosterRow>({ it.answer != null }, { it.answer?.needsHelp != true }, { it.nickname }))
        RollCallUi(rc.call, rc.answers.lastOrNull { it.mine }, roster, rc.answers)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Who is out on their own and when they are due back (protocol v0 §8.3). */
    val outings: StateFlow<List<org.geocrew.core.ui.OutingRow>> = project
        .map { p -> p.team?.let { Triple(p.id, it.memberId, it.role == TeamRepository.ROLE_LEADER) } }
        .distinctUntilChanged()
        .flatMapLatest { k ->
            if (k == null) flowOf(emptyList()) else comments.outings(k.first, k.second).map { list -> list.map { it.toRow(k.third) } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Return times offered with one tap when checking out. */
    fun returnChoices(): List<org.geocrew.core.model.Outing.Choice> =
        org.geocrew.core.model.Outing.choices(java.time.Instant.now(), java.time.ZoneId.systemDefault(), ui.value.mapCenter)

    /** Checks out to [where], due back at [backBy]; read-only members may too. */
    fun checkOut(where: String, backBy: java.time.Instant) = rollAction { outingActions.checkOut(it, where, backBy, ui.value.mapCenter) }

    /** Checks the outing [id] back in: one's own, or anyone's as a leader. */
    fun checkIn(id: String) = rollAction("只有本人和组长能报返回") { pid ->
        val outing = comments.openOutings(pid, memberId).firstOrNull { it.id == id } ?: return@rollAction null
        outingActions.checkIn(pid, outing, ui.value.mapCenter)
    }

    private val _rollBusy = MutableStateFlow(false)
    val rollBusy: StateFlow<Boolean> = _rollBusy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun consumeError() { _error.value = null }

    private var projectId: String? = null
    private var memberId: String? = null

    init {
        viewModelScope.launch { project.collect { projectId = it.id; memberId = it.team?.memberId } }
        // Who should answer: fetched when a roll call appears and again as answers come in.
        viewModelScope.launch {
            currentCall.map { it?.call?.id to (it?.answers?.size ?: 0) }.distinctUntilChanged().collect { (id, _) ->
                if (id != null) loadMembers()
            }
        }
    }

    private suspend fun loadMembers() {
        val id = projectId ?: return
        val h = team.handle(id) ?: return
        runCatching { team.members(h) }.getOrNull()?.let { members.value = it }
    }

    private fun rollAction(forbidden: String = "只有组长和发起人能结束点名", block: suspend (String) -> String?) = viewModelScope.launch {
        val id = projectId ?: return@launch
        if (_rollBusy.value) return@launch
        _rollBusy.value = true
        try {
            block(id)?.let { _error.value = it }
        } catch (e: IOException) {
            _error.value = if (e is ApiException && e.isForbidden) forbidden else "需要联网：${e.message}"
        } finally {
            _rollBusy.value = false
        }
    }

    /** Asks everyone to report in (leaders only, protocol v0 §8.2). */
    fun startRollCall(text: String) = rollAction { rollCalls.start(it, text) }

    /** Answers the current roll call with this phone's position. */
    fun answerRollCall(ok: Boolean) {
        val call = rollCall.value?.call ?: return
        rollAction { rollCalls.answer(it, call.id, ok, ui.value.mapCenter) }
    }

    /** Ends the current roll call; answers stay in the message stream. */
    fun endRollCall() {
        val call = rollCall.value?.call ?: return
        rollAction {
            if (call.pending) comments.discard(it, call.id) else rollCalls.end(call.id)
            null
        }
    }

    /** Called while the chat is on screen, as messages arrive. */
    fun markRead() = viewModelScope.launch {
        projectId?.let { comments.markRead(it) }
    }

    fun send(text: String, at: LngLat?) = viewModelScope.launch {
        val id = projectId ?: return@launch
        if (!comments.post(id, text, at = at)) _error.value = "当前工程没有加入工作组"
    }

    private val _sendingAlert = MutableStateFlow(false)
    val sendingAlert: StateFlow<Boolean> = _sendingAlert.asStateFlow()

    /**
     * Sends an urgent message to everyone (protocol v0 §8.1) with this
     * phone's position, or the map centre when no fix comes quickly.
     * Read-only members may send these too.
     */
    fun sendAlert(text: String) = viewModelScope.launch {
        val id = projectId ?: return@launch
        if (_sendingAlert.value) return@launch
        _sendingAlert.value = true
        try {
            val fix = if (location.hasPermission() && location.isEnabled()) {
                kotlinx.coroutines.withTimeoutOrNull(15_000) { location.fixes(intervalMillis = 1000).first() }
            } else {
                null
            }
            val at = fix?.position ?: ui.value.mapCenter
            val body = text.trim().ifEmpty { "需要帮助，请到我的位置" }
            if (!comments.post(id, body, at = at, urgent = true)) {
                _error.value = "当前工程没有加入工作组"
                return@launch
            }
            runCatching { session.syncNow() }
            if (fix == null) _error.value = "没有拿到定位，紧急消息附的是地图中心位置。消息会在联网后发出。"
        } finally {
            _sendingAlert.value = false
        }
    }

    fun delete(c: CommentItem) = viewModelScope.launch {
        val id = projectId ?: return@launch
        if (c.pending) {
            comments.discard(id, c.id)
            return@launch
        }
        try {
            session.deleteComment(c.id)
        } catch (e: IOException) {
            _error.value = if (e is ApiException && e.isForbidden) "只能删除自己的消息" else "删除需要联网：${e.message}"
        }
    }
}

private fun CommentItem.toRow(leader: Boolean) = org.geocrew.core.ui.OutingRow(
    id = id, nickname = nickname, where = body, backBy = backBy ?: createdAt, mine = mine,
    canClose = mine || leader, at = at,
)
