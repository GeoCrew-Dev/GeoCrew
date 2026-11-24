package org.geocrew.core.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.geocrew.core.data.db.CommentDao
import org.geocrew.core.data.db.ProjectConfigJson
import org.geocrew.core.data.db.ProjectDao
import org.geocrew.core.data.db.toEntity
import org.geocrew.core.data.db.toModel
import org.geocrew.core.model.Project
import org.geocrew.core.model.ProjectConfig
import org.geocrew.core.model.TeamLink
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: ProjectDao,
    private val comments: CommentDao,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    val projects: Flow<List<Project>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    /** Id of the open project, without creating one. */
    val currentId: Flow<String?> = settings.settings.map { it.currentProjectId }.distinctUntilChanged()

    /** The project the map is showing; a default one is created on first use. */
    val current: Flow<Project> = settings.settings
        .map { it.currentProjectId }
        .distinctUntilChanged()
        .onStart { ensureCurrent() }
        .flatMapLatest { id -> dao.observe(id ?: ensureCurrent().id) }
        .filterNotNull()
        .map { it.toModel() }

    suspend fun ensureCurrent(): Project {
        val id = settings.current().currentProjectId
        id?.let { dao.get(it) }?.let { return it.toModel() }
        val existing = dao.first()?.toModel() ?: create("默认工程")
        settings.setCurrentProject(existing.id)
        return existing
    }

    suspend fun get(id: String): Project? = dao.get(id)?.toModel()

    fun observe(id: String): Flow<Project?> = dao.observe(id).map { it?.toModel() }

    suspend fun all(): List<Project> = dao.all().map { it.toModel() }

    suspend fun create(name: String, id: String = UUID.randomUUID().toString(), config: ProjectConfig = ProjectConfig()): Project {
        val p = Project(id, name.trim().ifEmpty { "新工程" }, clock.instant(), config = config)
        dao.upsert(p.toEntity())
        return p
    }

    suspend fun select(id: String) = settings.setCurrentProject(id)

    /** Makes [id] the current project and records it as recently opened. */
    suspend fun open(id: String) {
        dao.setOpened(id, clock.millis())
        settings.setCurrentProject(id)
    }

    /** Serialises read-modify-write of the config, which several syncs touch. */
    private val configLock = Mutex()

    /** Changes a project's own settings (map, description...). */
    suspend fun updateConfig(id: String, change: (ProjectConfig) -> ProjectConfig) = configLock.withLock {
        val p = dao.get(id)?.toModel() ?: return@withLock
        val next = change(p.config)
        if (next != p.config) dao.setConfig(id, ProjectConfigJson.encode(next))
    }

    suspend fun rename(id: String, name: String) {
        val p = dao.get(id) ?: return
        dao.upsert(p.copy(name = name.trim().ifEmpty { p.name }))
    }

    /**
     * Deletes a project and its features from this device. Callers unlink
     * it from its workspace first; the team's copy is not touched.
     */
    suspend fun delete(id: String) {
        dao.delete(id)
        if (settings.current().currentProjectId == id) {
            val next = dao.first()?.toModel() ?: create("默认工程")
            settings.setCurrentProject(next.id)
        }
    }

    suspend fun linkTeam(id: String, team: TeamLink?) {
        val p = dao.get(id)?.toModel() ?: return
        val sameWorkspace = p.team?.serverUrl == team?.serverUrl && p.team?.workspaceId == team?.workspaceId
        // Form versions and the comment cursor belong to one workspace; a new
        // link starts in step with nothing, and the old team's discussion goes.
        val config = if (sameWorkspace) p.config else p.config.copy(teamFormVersion = 0, teamFormPending = false, teamCommentSeq = 0)
        if (!sameWorkspace) comments.clear(id)
        dao.upsert(p.copy(team = team, config = config).toEntity())
    }

    suspend fun setLastSeq(id: String, seq: Long) = dao.setLastSeq(id, seq)
}
