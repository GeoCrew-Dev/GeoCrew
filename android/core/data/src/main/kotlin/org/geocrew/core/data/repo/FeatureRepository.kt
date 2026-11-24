package org.geocrew.core.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.geocrew.core.data.db.FeatureDao
import org.geocrew.core.data.db.toEntity
import org.geocrew.core.data.db.toModel
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.PropertiesBuilder
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeatureRepository @Inject constructor(
    private val dao: FeatureDao,
    private val clock: Clock,
) {
    fun observeProject(projectId: String): Flow<List<GeoFeature>> =
        dao.observeLive(projectId).map { list -> list.map { it.toModel() } }

    /** Live feature count per project id. */
    fun observeCounts(): Flow<Map<String, Int>> = dao.observeCounts().map { l -> l.associate { it.projectId to it.count } }

    fun observe(id: String): Flow<GeoFeature?> = dao.observe(id).map { it?.toModel() }

    suspend fun get(id: String): GeoFeature? = dao.get(id)?.toModel()

    suspend fun all(projectId: String): List<GeoFeature> = dao.live(projectId).map { it.toModel() }

    suspend fun create(
        projectId: String,
        kind: FeatureKind,
        geometry: Geometry?,
        properties: PropertiesBuilder.() -> Unit = {},
    ): GeoFeature {
        val now = clock.instant()
        val f = GeoFeature(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            kind = kind,
            geometry = geometry,
            properties = buildProperties(properties),
            createdAt = now,
            sync = SyncInfo(updatedAt = now),
        )
        dao.upsert(f.toEntity())
        return f
    }

    /** Applies a local edit and bumps the revision so it syncs. */
    suspend fun edit(id: String, geometry: Geometry? = null, block: PropertiesBuilder.() -> Unit = {}): GeoFeature? {
        val cur = get(id) ?: return null
        val next = cur.edited(clock.instant(), geometry ?: cur.geometry, block)
        dao.upsert(next.toEntity())
        return next
    }

    /** Re-classifies a feature, e.g. a plain point that turned out to be a geological point. */
    suspend fun changeKind(id: String, kind: FeatureKind): GeoFeature? {
        val cur = get(id) ?: return null
        if (cur.kind == kind) return cur
        val next = cur.copy(kind = kind).edited(clock.instant())
        dao.upsert(next.toEntity())
        return next
    }

    suspend fun delete(id: String) {
        val cur = get(id) ?: return
        dao.upsert(cur.markDeleted(clock.instant()).toEntity())
    }

    /** Imports features (e.g. from a file) into a project as new local data. */
    suspend fun importAll(features: List<GeoFeature>) {
        val now = clock.instant()
        dao.upsertAll(features.map { it.copy(sync = it.sync.copy(updatedAt = now, dirty = true)).toEntity() })
    }

    // --- sync support -----------------------------------------------------

    suspend fun dirty(projectId: String, limit: Int): List<GeoFeature> = dao.dirty(projectId, limit).map { it.toModel() }

    fun observeDirtyCount(projectId: String): Flow<Int> = dao.observeDirtyCount(projectId)

    suspend fun markSynced(f: GeoFeature, seq: Long, by: String?) = dao.markSynced(f.id, f.sync.version, seq, by)

    /**
     * Merges a revision received from the server. Local unsynced edits win
     * only if they are newer under the protocol's conflict rule.
     */
    suspend fun mergeRemote(remote: GeoFeature): Boolean {
        val local = get(remote.id)
        if (local != null && local.sync.dirty && local.sync.isNewerThan(remote.sync)) return false
        dao.upsert(remote.copy(createdAt = local?.createdAt ?: remote.createdAt, sync = remote.sync.copy(dirty = false)).toEntity())
        return true
    }

    suspend fun purgeDeleted(projectId: String) = dao.purgeDeleted(projectId)
}
