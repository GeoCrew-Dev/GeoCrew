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
    /** Most recently opened first, then newest. */
    @Query("SELECT * FROM projects ORDER BY openedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects")
    suspend fun all(): List<ProjectEntity>

    @Query("SELECT * FROM projects ORDER BY openedAt DESC, createdAt DESC LIMIT 1")
    suspend fun first(): ProjectEntity?

    @Upsert
    suspend fun upsert(p: ProjectEntity)

    @Query("UPDATE projects SET openedAt = :at WHERE id = :id")
    suspend fun setOpened(id: String, at: Long)

    @Query("UPDATE projects SET config = :config WHERE id = :id")
    suspend fun setConfig(id: String, config: String)

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

    /** Every feature's properties JSON, for finding which photo files are still referenced. */
    @Query("SELECT properties FROM features")
    suspend fun allProperties(): List<String>

    @Query("DELETE FROM features WHERE projectId = :projectId AND deleted = 1 AND dirty = 0")
    suspend fun purgeDeleted(projectId: String)
}

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: TrackPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ps: List<TrackPointEntity>)

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

@Dao
interface CommentDao {
    /** Comments on one record, in the order the server stored them; unsent ones last. */
    @Query("SELECT * FROM comments WHERE projectId = :projectId AND featureId = :featureId ORDER BY seq = 0, seq, createdAt")
    fun observeFor(projectId: String, featureId: String): Flow<List<CommentEntity>>

    /** Everything said in the project's team, newest last. */
    @Query("SELECT * FROM (SELECT * FROM comments WHERE projectId = :projectId ORDER BY seq = 0 DESC, seq DESC, createdAt DESC LIMIT :limit) ORDER BY seq = 0, seq, createdAt")
    fun observeRecent(projectId: String, limit: Int): Flow<List<CommentEntity>>

    @Query("SELECT featureId, COUNT(*) AS count FROM comments WHERE projectId = :projectId AND featureId IS NOT NULL GROUP BY featureId")
    fun observeCounts(projectId: String): Flow<List<CommentCount>>

    /** Teammates' comments newer than [seen]. */
    @Query("SELECT COUNT(*) FROM comments WHERE projectId = :projectId AND seq > :seen AND authorId != :me")
    fun observeUnread(projectId: String, seen: Long, me: String): Flow<Int>

    /** The newest roll call written since [sinceMillis]. */
    @Query("SELECT * FROM comments WHERE projectId = :projectId AND rollCall = 1 AND createdAt >= :sinceMillis ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestRollCall(projectId: String, sinceMillis: Long): Flow<CommentEntity?>

    @Query("SELECT * FROM comments WHERE projectId = :projectId AND rollCall = 1 AND createdAt >= :sinceMillis ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestRollCall(projectId: String, sinceMillis: Long): CommentEntity?

    /** Answers to a roll call, oldest first. */
    @Query("SELECT * FROM comments WHERE projectId = :projectId AND answerTo = :callId ORDER BY createdAt")
    fun observeAnswers(projectId: String, callId: String): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comments WHERE projectId = :projectId AND answerTo IS NOT NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestAnswer(projectId: String): CommentEntity?

    /** Check-outs written since [sinceMillis] and every message closing one, oldest first (protocol v0 §8.3). */
    @Query("SELECT * FROM comments WHERE projectId = :projectId AND ((backBy IS NOT NULL AND createdAt >= :sinceMillis) OR backFrom IS NOT NULL) ORDER BY createdAt")
    fun observeOutings(projectId: String, sinceMillis: Long): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comments WHERE projectId = :projectId AND ((backBy IS NOT NULL AND createdAt >= :sinceMillis) OR backFrom IS NOT NULL) ORDER BY createdAt")
    suspend fun outings(projectId: String, sinceMillis: Long): List<CommentEntity>

    /** Urgent messages written since [sinceMillis], newest first. */
    @Query("SELECT * FROM comments WHERE projectId = :projectId AND urgent = 1 AND createdAt >= :sinceMillis ORDER BY createdAt DESC")
    fun observeUrgent(projectId: String, sinceMillis: Long): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comments WHERE projectId = :projectId AND urgent = 1 AND createdAt >= :sinceMillis ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestUrgent(projectId: String, sinceMillis: Long): CommentEntity?

    @Query("SELECT COALESCE(MAX(seq), 0) FROM comments WHERE projectId = :projectId")
    suspend fun maxSeq(projectId: String): Long

    @Query("SELECT * FROM comments WHERE projectId = :projectId AND seq = 0 ORDER BY createdAt")
    suspend fun pending(projectId: String): List<CommentEntity>

    @Query("SELECT COUNT(*) FROM comments WHERE projectId = :projectId AND seq = 0")
    fun observePendingCount(projectId: String): Flow<Int>

    @Query("SELECT * FROM comments WHERE projectId = :projectId AND id = :id")
    suspend fun get(projectId: String, id: String): CommentEntity?

    @Upsert
    suspend fun upsert(c: CommentEntity)

    @Query("DELETE FROM comments WHERE projectId = :projectId AND id = :id")
    suspend fun delete(projectId: String, id: String)

    @Query("DELETE FROM comments WHERE projectId = :projectId")
    suspend fun clear(projectId: String)
}

/** Number of comments on one record. */
data class CommentCount(val featureId: String, val count: Int)

/** Number of live features in a project. */
data class ProjectCount(val projectId: String, val count: Int)
