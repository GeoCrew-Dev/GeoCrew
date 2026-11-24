package org.geocrew.core.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.geocrew.core.data.db.ProjectDao
import org.geocrew.core.data.db.toEntity
import org.geocrew.core.data.db.toModel
import org.geocrew.core.model.Project
import org.geocrew.core.model.TeamLink
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: ProjectDao,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    val projects: Flow<List<Project>> = dao.observeAll().map { list -> list.map { it.toModel() } }

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

    suspend fun create(name: String): Project {
        val p = Project(UUID.randomUUID().toString(), name.trim().ifEmpty { "新工程" }, clock.instant())
        dao.upsert(p.toEntity())
        return p
    }

    suspend fun select(id: String) = settings.setCurrentProject(id)

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
        dao.upsert(p.copy(team = team).toEntity())
    }

    suspend fun setLastSeq(id: String, seq: Long) = dao.setLastSeq(id, seq)
}
