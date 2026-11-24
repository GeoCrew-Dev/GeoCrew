package org.geocrew.core.data.team

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.geocrew.core.data.di.ApplicationScope
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.LngLat
import org.geocrew.core.network.ApiException
import org.geocrew.core.network.RealtimeClient
import org.geocrew.core.network.RealtimeEvent
import org.geocrew.core.network.RealtimeState
import org.geocrew.core.network.SyncEngine
import org.geocrew.core.network.SyncReport
import org.geocrew.core.network.TeamPosition
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI shows about the current project's team connection. */
data class TeamStatus(
    val linked: Boolean = false,
    /** True when the project is linked but its credential is missing or rejected. */
    val needsRejoin: Boolean = false,
    val workspaceName: String = "",
    val role: String = "",
    val connection: RealtimeState = RealtimeState.Idle,
    val syncing: Boolean = false,
    val lastSync: Instant? = null,
    val lastReport: SyncReport? = null,
    val error: String? = null,
    val pending: Int = 0,
    val online: Set<String> = emptySet(),
)

/**
 * Keeps the current project in step with its workspace while the app runs:
 * opens the realtime channel, syncs on start, after local edits and whenever
 * a teammate's change is announced, and collects teammates' live positions.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class TeamSession @Inject constructor(
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    private val team: TeamRepository,
    private val settings: SettingsRepository,
    private val store: RoomSyncStore,
    private val attachments: PhotoAttachments,
    private val client: OkHttpClient,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(TeamStatus())
    val status: StateFlow<TeamStatus> = _status.asStateFlow()

    private val _positions = MutableStateFlow<Map<String, TeamPosition>>(emptyMap())
    /** Teammates' last known positions by member id (own position excluded). */
    val positions: StateFlow<Map<String, TeamPosition>> = _positions.asStateFlow()

    private val syncLock = Mutex()
    @Volatile private var active: Active? = null
    @Volatile private var started = false

    private class Active(val handle: TeamHandle, val realtime: RealtimeClient) {
        var lastSent: LngLat? = null
        var lastSentAt: Long = 0
    }

    /** Starts following the current project. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            projects.current.collect { p ->
                val t = p.team ?: return@collect
                _status.update { if (it.linked) it.copy(workspaceName = t.workspaceName, role = t.role) else it }
            }
        }
        scope.launch {
            projects.current
                // lastSeq, role and names change during a session; only a new
                // project or a different membership restarts the connection.
                .distinctUntilChanged { a, b ->
                    a.id == b.id && a.team?.serverUrl == b.team?.serverUrl &&
                        a.team?.workspaceId == b.team?.workspaceId && a.team?.memberId == b.team?.memberId
                }
                .collectLatest { p ->
                    val projectId = p.id
                    val link = p.team
                    _positions.value = emptyMap()
                    if (link == null) {
                        _status.value = TeamStatus()
                        return@collectLatest
                    }
                    val handle = team.handle(projectId)
                    if (handle == null) {
                        _status.value = TeamStatus(linked = true, needsRejoin = true, workspaceName = link.workspaceName, role = link.role)
                        return@collectLatest
                    }
                    _status.value = TeamStatus(linked = true, workspaceName = link.workspaceName, role = link.role)
                    follow(handle)
                }
        }
    }

    /** Runs one sync round now. Returns the report, or null if not linked or it failed. */
    suspend fun syncNow(): SyncReport? {
        val a = active ?: return null
        return syncLock.withLock { syncOnce(a.handle) }
    }

    /**
     * Shares this device's position if the user enabled it. Throttled to one
     * message per 5 s unless the device moved more than 20 m.
     */
    fun reportPosition(position: LngLat, accuracy: Float?, bearing: Float?, speed: Float?) {
        val a = active ?: return
        if (a.realtime.state.value != RealtimeState.Connected) return
        scope.launch {
            if (!settings.current().sharePosition) return@launch
            val now = clock.millis()
            val moved = a.lastSent?.let { Geodesy.distance(it, position) } ?: Double.MAX_VALUE
            if (now - a.lastSentAt < 5_000 && moved < 20) return@launch
            if (a.realtime.sendPosition(position.lng, position.lat, position.alt, accuracy?.toDouble(), bearing?.toDouble(), speed?.toDouble())) {
                a.lastSent = position
                a.lastSentAt = now
            }
        }
    }

    private suspend fun follow(handle: TeamHandle) = coroutineScope {
        val projectId = handle.project.id
        val realtime = RealtimeClient(handle.api, handle.link.workspaceId, client, this)
        val a = Active(handle, realtime)
        active = a
        try {
            launch { realtime.state.collect { s -> _status.update { it.copy(connection = s, needsRejoin = it.needsRejoin || s == RealtimeState.Unauthorized) } } }
            launch { realtime.events.collect { onEvent(handle, it) } }
            // Push local edits shortly after they happen.
            launch {
                features.observeDirtyCount(projectId)
                    .onEach { n -> _status.update { it.copy(pending = n) } }
                    .debounce(DIRTY_DEBOUNCE_MS)
                    .filter { it > 0 }
                    .collect { syncLock.withLock { syncOnce(handle) } }
            }
            realtime.start()
            syncLock.withLock { syncOnce(handle) }
            // Refresh cached workspace name and role; failures are not fatal.
            runCatching { team.workspace(handle) }
            awaitCancellation()
        } finally {
            realtime.stop()
            if (active === a) active = null
        }
    }

    private suspend fun onEvent(handle: TeamHandle, e: RealtimeEvent) {
        when (e) {
            is RealtimeEvent.Hello -> {
                _status.update { it.copy(online = e.online.toSet()) }
                _positions.value = e.positions.filter { it.memberId != handle.link.memberId }.associateBy { it.memberId }
            }
            is RealtimeEvent.Presence -> _status.update {
                it.copy(online = if (e.online) it.online + e.memberId else it.online - e.memberId)
            }
            is RealtimeEvent.Position -> if (e.position.memberId != handle.link.memberId) {
                _positions.update { it + (e.position.memberId to e.position) }
            }
            is RealtimeEvent.FeatureChanged -> {
                val known = projects.get(handle.project.id)?.team?.lastSeq ?: 0L
                if (e.seq > known) syncLock.withLock { syncOnce(handle) }
            }
            RealtimeEvent.MembersChanged -> runCatching { team.workspace(handle) }
            is RealtimeEvent.Error -> _status.update { it.copy(error = e.message.ifBlank { e.code }) }
        }
    }

    private suspend fun syncOnce(handle: TeamHandle): SyncReport? {
        _status.update { it.copy(syncing = true) }
        return try {
            val report = SyncEngine(
                api = handle.api,
                store = store,
                files = attachments,
                workspaceId = handle.link.workspaceId,
                projectId = handle.project.id,
                memberId = handle.link.memberId,
            ).run()
            _status.update { it.copy(syncing = false, lastSync = clock.instant(), lastReport = report, error = null) }
            report
        } catch (e: ApiException) {
            val rejected = e.status == 401
            _status.update { it.copy(syncing = false, needsRejoin = rejected || it.needsRejoin, error = describe(e)) }
            null
        } catch (e: java.io.IOException) {
            _status.update { it.copy(syncing = false, error = "网络不可用：${e.message ?: e.javaClass.simpleName}") }
            null
        } catch (e: CancellationException) {
            _status.update { it.copy(syncing = false) }
            throw e
        } catch (e: Exception) {
            // Malformed data from the server must not crash the app.
            _status.update { it.copy(syncing = false, error = "同步出错：${e.message ?: e.javaClass.simpleName}") }
            null
        }
    }

    private fun describe(e: ApiException) = when (e.status) {
        401 -> "身份已失效（可能已被移出工作组），请重新加入"
        403 -> "当前角色没有修改权限，本地改动未上传"
        404 -> "服务器上找不到该工作组"
        else -> e.message ?: "同步失败（${e.status}）"
    }

    private companion object {
        const val DIRTY_DEBOUNCE_MS = 3_000L
    }
}
