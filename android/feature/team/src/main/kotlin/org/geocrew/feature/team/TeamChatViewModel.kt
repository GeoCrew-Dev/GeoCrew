package org.geocrew.feature.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class ChatUi(
    val linked: Boolean = false,
    val workspaceName: String = "",
    val canPost: Boolean = false,
    val isLeader: Boolean = false,
    /** Where the map was last left, offered as the message's position. */
    val mapCenter: LngLat? = null,
    val items: List<ChatRow> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TeamChatViewModel @Inject constructor(
    projects: ProjectRepository,
    features: FeatureRepository,
    private val comments: TeamComments,
    private val session: TeamSession,
) : ViewModel() {
    private val project = projects.current

    val ui: StateFlow<ChatUi> = project
        .distinctUntilChanged { a, b -> a.id == b.id && a.team == b.team && a.config.view == b.config.view }
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
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUi())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun consumeError() { _error.value = null }

    private var projectId: String? = null

    init {
        viewModelScope.launch { project.collect { projectId = it.id } }
    }

    /** Called while the chat is on screen, as messages arrive. */
    fun markRead() = viewModelScope.launch {
        projectId?.let { comments.markRead(it) }
    }

    fun send(text: String, at: LngLat?) = viewModelScope.launch {
        val id = projectId ?: return@launch
        if (!comments.post(id, text, at = at)) _error.value = "当前工程没有加入工作组"
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
