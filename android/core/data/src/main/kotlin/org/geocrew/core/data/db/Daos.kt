package org.geocrew.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY createdAt LIMIT 1")
    suspend fun first(): ProjectEntity?

    @Upsert
    suspend fun upsert(p: ProjectEntity)

    @Query("UPDATE projects SET lastSeq = :seq WHERE id = :id")
    suspend fun setLastSeq(id: String, seq: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FeatureDao {
    @Query("SELECT * FROM features WHERE projectId = :projectId AND deleted = 0 ORDER BY createdAt DESC")
    fun observeLive(projectId: String): Flow<List<FeatureEntity>>

    @Query("SELECT * FROM features WHERE id = :id")
    fun observe(id: String): Flow<FeatureEntity?>

    @Query("SELECT * FROM features WHERE id = :id")
    suspend fun get(id: String): FeatureEntity?

    @Query("SELECT * FROM features WHERE projectId = :projectId AND deleted = 0")
    suspend fun live(projectId: String): List<FeatureEntity>

    @Query("SELECT * FROM features WHERE projectId = :projectId AND dirty = 1 LIMIT :limit")
    suspend fun dirty(projectId: String, limit: Int): List<FeatureEntity>

    @Query("SELECT COUNT(*) FROM features WHERE projectId = :projectId AND dirty = 1")
    fun observeDirtyCount(projectId: String): Flow<Int>

    @Upsert
    suspend fun upsert(f: FeatureEntity)

    @Upsert
    suspend fun upsertAll(fs: List<FeatureEntity>)

    /** Marks a revision as accepted by the server, unless it was edited again meanwhile. */
    @Query("UPDATE features SET dirty = 0, seq = :seq, updatedBy = :by WHERE id = :id AND version = :version")
    suspend fun markSynced(id: String, version: Long, seq: Long, by: String?)

    @Query("SELECT projectId, COUNT(*) AS count FROM features WHERE deleted = 0 GROUP BY projectId")
    fun observeCounts(): Flow<List<ProjectCount>>

    @Query("DELETE FROM features WHERE projectId = :projectId AND deleted = 1 AND dirty = 0")
    suspend fun purgeDeleted(projectId: String)
}

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: TrackPointEntity)

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY idx")
    suspend fun points(trackId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY idx")
    fun observePoints(trackId: String): Flow<List<TrackPointEntity>>

    @Query("SELECT COALESCE(MAX(idx) + 1, 0) FROM track_points WHERE trackId = :trackId")
    suspend fun nextIndex(trackId: String): Int

    @Transaction
    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    suspend fun clear(trackId: String)
}

/** Number of live features in a project. */
data class ProjectCount(val projectId: String, val count: Int)
