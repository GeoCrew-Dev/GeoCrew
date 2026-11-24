package org.geocrew.core.data.team

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.geocrew.core.data.db.CommentDao
import org.geocrew.core.data.db.CommentEntity
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.LngLat
import org.geocrew.core.network.Comment
import org.geocrew.core.network.CommentStore
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One comment as the UI shows it. */
data class CommentItem(
    val id: String,
    /** The record discussed; null for a message to the whole team. */
    val featureId: String?,
    val body: String,
    /** Map position a team message points at. */
    val at: LngLat?,
    val authorId: String,
    val nickname: String,
    val createdAt: Instant,
    /** Written here but not yet received by the server. */
    val pending: Boolean,
    val mine: Boolean,
)

/**
 * The team's discussion (protocol v0 §8), kept in the local database so it
 * can be read and written without signal. New comments are stored as
 * pending and sent by [TeamSession], which also pulls teammates' comments.
 */
@Singleton
class TeamComments @Inject constructor(
    private val dao: CommentDao,
    private val projects: ProjectRepository,
    private val clock: Clock,
) : CommentStore {

    fun forFeature(projectId: String, featureId: String, me: String?): Flow<List<CommentItem>> =
        dao.observeFor(projectId, featureId).map { list -> list.map { it.toItem(me) } }

    fun recent(projectId: String, me: String?, limit: Int = 300): Flow<List<CommentItem>> =
        dao.observeRecent(projectId, limit).map { list -> list.map { it.toItem(me) } }

    /** Comment counts by record id, for badges. */
    fun counts(projectId: String): Flow<Map<String, Int>> =
        dao.observeCounts(projectId).map { list -> list.associate { it.featureId to it.count } }

    fun pendingCount(projectId: String): Flow<Int> = dao.observePendingCount(projectId)

    /**
     * Stores a new comment for sending. Returns false when the project has no
     * team or the text is empty. Long text is cut to the protocol limit.
     */
    suspend fun post(projectId: String, body: String, featureId: String? = null, at: LngLat? = null): Boolean {
        val link = projects.get(projectId)?.team ?: return false
        val text = body.trim().let { if (it.codePointCount(0, it.length) > MAX_CHARS) it.take(it.offsetByCodePoints(0, MAX_CHARS)) else it }
        if (text.isEmpty()) return false
        dao.upsert(
            CommentEntity(
                projectId = projectId, id = UUID.randomUUID().toString(), featureId = featureId, body = text,
                atLng = at?.lng, atLat = at?.lat, authorId = link.memberId, nickname = link.nickname,
                createdAt = clock.millis(), seq = 0,
            ),
        )
        return true
    }

    /** Drops a comment that was never sent. Sent ones are deleted through the server. */
    suspend fun discard(projectId: String, id: String) {
        if (dao.get(projectId, id)?.seq == 0L) dao.delete(projectId, id)
    }

    // --- CommentStore, for CommentSync -----------------------------------------

    override suspend fun pending(projectId: String): List<Comment> = dao.pending(projectId).map {
        Comment(id = it.id, featureId = it.featureId, body = it.body, at = it.atLng?.let { lng -> listOf(lng, it.atLat ?: 0.0) })
    }

    override suspend fun save(projectId: String, c: Comment) {
        val at = c.at?.takeIf { it.size == 2 }
        dao.upsert(
            CommentEntity(
                projectId = projectId, id = c.id, featureId = c.featureId?.ifEmpty { null }, body = c.body,
                atLng = at?.get(0), atLat = at?.get(1), authorId = c.authorId, nickname = c.nickname,
                createdAt = runCatching { Instant.parse(c.createdAt).toEpochMilli() }.getOrDefault(clock.millis()),
                seq = c.seq,
            ),
        )
    }

    override suspend fun remove(projectId: String, id: String) = dao.delete(projectId, id)

    override suspend fun commentSeq(projectId: String): Long = projects.get(projectId)?.config?.teamCommentSeq ?: 0L

    override suspend fun setCommentSeq(projectId: String, seq: Long) =
        projects.updateConfig(projectId) { it.copy(teamCommentSeq = seq) }

    private fun CommentEntity.toItem(me: String?) = CommentItem(
        id = id, featureId = featureId, body = body,
        at = if (atLng != null && atLat != null) LngLat(atLng, atLat) else null,
        authorId = authorId, nickname = nickname, createdAt = Instant.ofEpochMilli(createdAt),
        pending = seq == 0L, mine = me != null && authorId == me,
    )

    companion object {
        const val MAX_CHARS = 2000
    }
}
