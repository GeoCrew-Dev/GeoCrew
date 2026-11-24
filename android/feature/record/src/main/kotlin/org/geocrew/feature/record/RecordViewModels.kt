package org.geocrew.feature.record

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.PhotoStore
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.data.team.CommentItem
import org.geocrew.core.data.team.TeamComments
import org.geocrew.core.data.team.TeamRepository
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Props
import org.geocrew.core.model.string
import java.io.File
import javax.inject.Inject

data class Revision(val feature: GeoFeature, val author: String)

sealed interface HistoryState {
    data object Idle : HistoryState
    data object Loading : HistoryState
    data class Loaded(val revisions: List<Revision>) : HistoryState
    data class Failed(val message: String) : HistoryState
}

@HiltViewModel
class RecordListViewModel @Inject constructor(
    projects: ProjectRepository,
    features: FeatureRepository,
) : ViewModel() {
    val kindFilter = MutableStateFlow<FeatureKind?>(null)
    val query = MutableStateFlow("")

    private val all: StateFlow<List<GeoFeature>> = projects.current
        .map { it.id }
        .distinctUntilChanged()
        .flatMapLatest { features.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val projectName: StateFlow<String> = projects.current.map { it.name }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val items: StateFlow<List<GeoFeature>> = combine(all, kindFilter, query) { list, kind, q ->
        list.asSequence()
            .filter { kind == null || it.kind == kind }
            .filter { q.isBlank() || (it.title ?: "").contains(q, ignoreCase = true) || (it.note ?: "").contains(q, ignoreCase = true) }
            .sortedByDescending { it.sync.updatedAt }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val counts: StateFlow<Map<FeatureKind, Int>> = all.map { l -> l.groupingBy { it.kind }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
}

/** Route argument holding the feature id. */
const val FEATURE_ID_ARG = "featureId"

@HiltViewModel
class FeatureDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val features: FeatureRepository,
    val photos: PhotoStore,
    settings: SettingsRepository,
    private val projects: ProjectRepository,
    private val team: TeamRepository,
    private val teamComments: TeamComments,
    private val session: TeamSession,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[FEATURE_ID_ARG])

    /** True when the feature's project syncs with a workspace, so the server keeps its history. */
    val shared: StateFlow<Boolean> = projects.current.map { it.team != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _history = MutableStateFlow<HistoryState>(HistoryState.Idle)
    val history: StateFlow<HistoryState> = _history.asStateFlow()

    fun loadHistory() = viewModelScope.launch {
        val f = feature.value ?: return@launch
        val h = team.handle(f.projectId) ?: run { _history.value = HistoryState.Failed("当前工程未连接工作组"); return@launch }
        _history.value = HistoryState.Loading
        _history.value = try {
            val names = runCatching { team.members(h).associate { it.id to it.nickname } }.getOrDefault(emptyMap())
            HistoryState.Loaded(
                team.history(h, id).asReversed().map { r ->
                    Revision(r, r.sync.updatedBy?.let { names[it] ?: "已移出的成员" } ?: "未知")
                },
            )
        } catch (e: java.io.IOException) {
            HistoryState.Failed(if (e is org.geocrew.core.network.ApiException && e.status == 404) "服务器上还没有这条记录，同步后再看" else "读取失败：${e.message}")
        }
    }

    fun restore(r: Revision) = viewModelScope.launch {
        features.restore(id, r.feature)
        _history.value = HistoryState.Idle
    }

    val feature: StateFlow<GeoFeature?> = features.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The project's form: the fields its records are meant to fill in. */
    val form: StateFlow<List<FieldTemplate>> = projects.current.map { it.config.fields }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _fieldError = MutableStateFlow<String?>(null)
    val fieldError: StateFlow<String?> = _fieldError.asStateFlow()
    fun consumeFieldError() { _fieldError.value = null }

    /** Sets one form field, typed by its template. */
    fun setField(field: FieldTemplate, raw: String) = viewModelScope.launch {
        val value = try {
            field.encode(raw)
        } catch (e: IllegalArgumentException) {
            _fieldError.value = e.message; return@launch
        }
        val cur = feature.value ?: return@launch
        if (cur.properties[field.key] == value) return@launch
        features.edit(id) { put(field.key, value) }
    }

    val coordinateFormat: StateFlow<CoordinateFormat> = settings.settings.map { it.coordinateFormat }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CoordinateFormat.DECIMAL)

    /** Saves text fields; blank values remove the property. */
    fun saveText(title: String, note: String, lithology: String) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        if (cur.title.orEmpty() == title && cur.note.orEmpty() == note && cur.properties.string(Props.LITHOLOGY).orEmpty() == lithology) return@launch
        features.edit(id) {
            this[Props.TITLE] = title.trim().ifBlank { null }
            this[Props.NOTE] = note.trim().ifBlank { null }
            this[Props.LITHOLOGY] = lithology.trim().ifBlank { null }
        }
    }

    fun setAttitude(a: Attitude?) = viewModelScope.launch {
        features.edit(id) {
            if (a == null) {
                remove(Props.STRIKE); remove(Props.DIP_DIRECTION); remove(Props.DIP)
            } else {
                a.writeTo(this)
            }
        }
    }

    /** Sets a free attribute. Numbers are stored as numbers so analysis tools can use them. */
    fun setAttribute(key: String, raw: String) = viewModelScope.launch {
        val k = key.trim()
        if (k.isEmpty() || k in RESERVED_KEYS) return@launch
        val v = raw.trim()
        features.edit(id) {
            val n = v.toDoubleOrNull()?.takeIf { it.isFinite() }
            when {
                v.isEmpty() -> remove(k)
                n != null -> this[k] = n
                else -> this[k] = v
            }
        }
    }

    fun removeAttribute(key: String) = viewModelScope.launch {
        if (key in RESERVED_KEYS) return@launch
        features.edit(id) { remove(key) }
    }

    fun setKind(kind: FeatureKind) = viewModelScope.launch {
        features.changeKind(id, kind)
    }

    fun newCaptureTarget() = photos.newCaptureTarget()

    fun addCaptured(file: File) = viewModelScope.launch {
        photos.commit(file)?.let(::addPhoto)
    }

    fun addPicked(uri: android.net.Uri) = viewModelScope.launch {
        photos.import(uri)?.let(::addPhoto)
    }

    private fun addPhoto(sha: String) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        if (sha in cur.photos) return@launch
        features.edit(id) { strings(Props.PHOTOS, cur.photos + sha) }
    }

    fun removePhoto(sha: String) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        features.edit(id) { strings(Props.PHOTOS, cur.photos - sha) }
    }

    fun delete() = viewModelScope.launch { features.delete(id) }

    // --- discussion (protocol v0 §8) ------------------------------------------

    /** Who may do what in the discussion; null when the project has no team. */
    data class CommentAccess(val memberId: String, val canPost: Boolean, val isLeader: Boolean)

    val commentAccess: StateFlow<CommentAccess?> = projects.current.map { p ->
        p.team?.let { CommentAccess(it.memberId, it.role != TeamRepository.ROLE_READONLY, it.role == TeamRepository.ROLE_LEADER) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val comments: StateFlow<List<CommentItem>> = projects.current
        .map { it.id to it.team?.memberId }
        .distinctUntilChanged()
        .flatMapLatest { (pid, me) -> teamComments.forFeature(pid, id, me) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _commentError = MutableStateFlow<String?>(null)
    val commentError: StateFlow<String?> = _commentError.asStateFlow()
    fun consumeCommentError() { _commentError.value = null }

    fun postComment(text: String) = viewModelScope.launch {
        val pid = feature.value?.projectId ?: return@launch
        teamComments.post(pid, text, featureId = id)
    }

    /** Unsent comments are simply dropped; sent ones need the server. */
    fun deleteComment(c: CommentItem) = viewModelScope.launch {
        val pid = feature.value?.projectId ?: return@launch
        if (c.pending) {
            teamComments.discard(pid, c.id)
            return@launch
        }
        try {
            session.deleteComment(c.id)
        } catch (e: java.io.IOException) {
            _commentError.value = if (e is org.geocrew.core.network.ApiException && e.isForbidden) "只能删除自己的发言" else "删除需要联网：${e.message}"
        }
    }
}

/** Properties with their own editors or managed by the app; free attributes never overwrite them. */
internal val RESERVED_KEYS = Props.MANAGED
