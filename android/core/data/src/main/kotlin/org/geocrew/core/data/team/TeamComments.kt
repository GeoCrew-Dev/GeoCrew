package org.geocrew.core.data.team

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** A call for help or to gather; shown and notified loudly. */
    val urgent: Boolean = false,
    /** A leader asking everyone to report in (protocol v0 §8.2). */
    val rollCall: Boolean = false,
    /** The roll call this message reports in to. */
    val answerTo: String? = null,
    /** A check-out: when the author expects to be back (protocol v0 §8.3). */
    val backBy: Instant? = null,
    /** The check-out this message closes. */
    val backFrom: String? = null,
) {
    /** An answer saying the sender needs help rather than that all is well. */
    val needsHelp: Boolean get() = answerTo != null && urgent

    fun overdue(now: Instant): Boolean = backBy != null && backBy.isBefore(now)
}

/**
 * The current roll call and, per member, their latest answer to it. Who has
 * not answered yet comes from the member list, which only the server knows.
 */
data class RollCall(val call: CommentItem, val answers: List<CommentItem>) {
    fun answerOf(memberId: String): CommentItem? = answers.firstOrNull { it.authorId == memberId }
}

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

    /** The newest roll call from the last [hours] hours with everyone's latest answer, or null. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun rollCall(projectId: String, me: String?, hours: Long = ROLL_CALL_HOURS): Flow<RollCall?> =
        dao.observeLatestRollCall(projectId, clock.millis() - hours * 3_600_000)
            .distinctUntilChanged()
            .flatMapLatest { call ->
                if (call == null) {
                    flowOf(null)
                } else {
                    dao.observeAnswers(projectId, call.id).map { list ->
                        val latest = list.map { it.toItem(me) }.groupBy { it.authorId }.map { (_, a) -> a.last() }
                        RollCall(call.toItem(me), latest.sortedBy { it.createdAt })
                    }
                }
            }

    /** Teammates (and this user) out on their own and not checked back in, soonest due first. */
    fun outings(projectId: String, me: String?): Flow<List<CommentItem>> =
        dao.observeOutings(projectId, clock.millis() - OUTING_DAYS * 86_400_000).map { list -> openOutings(list.map { it.toItem(me) }) }

    /** The same, once, for a notification. */
    suspend fun openOutings(projectId: String, me: String?): List<CommentItem> =
        openOutings(dao.outings(projectId, clock.millis() - OUTING_DAYS * 86_400_000).map { it.toItem(me) })

    /** The newest roll call, for a notification. */
    suspend fun latestRollCall(projectId: String, me: String?): CommentItem? =
        dao.latestRollCall(projectId, clock.millis() - ROLL_CALL_HOURS * 3_600_000)?.toItem(me)

    /** The newest answer to a roll call, for the leader's notification. */
    suspend fun latestAnswer(projectId: String, me: String?): CommentItem? = dao.latestAnswer(projectId)?.toItem(me)

    /** Teammates' urgent messages from the last [hours] hours, newest first. */
    fun alerts(projectId: String, me: String?, hours: Long = ALERT_HOURS): Flow<List<CommentItem>> =
        dao.observeUrgent(projectId, clock.millis() - hours * 3_600_000).map { list ->
            list.map { it.toItem(me) }.filter { !it.mine }
        }

    /** The newest urgent message from someone else, if any, for a notification. */
    suspend fun latestAlert(projectId: String, me: String?): CommentItem? =
        dao.latestUrgent(projectId, clock.millis() - ALERT_HOURS * 3_600_000)?.toItem(me)?.takeIf { !it.mine }

    /** Teammates' comments the user has not seen in the team chat yet. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun unread(projectId: String): Flow<Int> = projects.observe(projectId)
        .map { p -> p?.team?.memberId to (p?.config?.chatReadSeq ?: 0L) }
        .distinctUntilChanged()
        .flatMapLatest { (me, seen) -> if (me == null) flowOf(0) else dao.observeUnread(projectId, seen, me) }

    /** Marks everything received so far as seen. */
    suspend fun markRead(projectId: String) {
        val max = dao.maxSeq(projectId)
        projects.updateConfig(projectId) { if (it.chatReadSeq >= max) it else it.copy(chatReadSeq = max) }
    }

    /**
     * Stores a new comment for sending. Returns false when the project has no
     * team or the text is empty. Long text is cut to the protocol limit.
     */
    suspend fun post(
        projectId: String,
        body: String,
        featureId: String? = null,
        at: LngLat? = null,
        urgent: Boolean = false,
        rollCall: Boolean = false,
        answerTo: String? = null,
        backBy: Instant? = null,
        backFrom: String? = null,
    ): Boolean {
        val link = projects.get(projectId)?.team ?: return false
        val text = body.trim().let { if (it.codePointCount(0, it.length) > MAX_CHARS) it.take(it.offsetByCodePoints(0, MAX_CHARS)) else it }
        if (text.isEmpty()) return false
        dao.upsert(
            CommentEntity(
                projectId = projectId, id = UUID.randomUUID().toString(), featureId = featureId, body = text,
                atLng = at?.lng, atLat = at?.lat, authorId = link.memberId, nickname = link.nickname,
                createdAt = clock.millis(), seq = 0, urgent = urgent && featureId == null,
                rollCall = rollCall && featureId == null && answerTo == null,
                answerTo = answerTo?.takeIf { featureId == null },
                backBy = backBy?.takeIf { featureId == null && backFrom == null }?.toEpochMilli()?.let { it - it % 1000 },
                backFrom = backFrom?.takeIf { featureId == null },
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
        Comment(id = it.id, featureId = it.featureId, body = it.body, at = it.atLng?.let { lng -> listOf(lng, it.atLat ?: 0.0) }, urgent = it.urgent,
            rollCall = it.rollCall, answerTo = it.answerTo,
            backBy = it.backBy?.let { ms -> Instant.ofEpochMilli(ms).toString() }, backFrom = it.backFrom,
        )
    }

    override suspend fun save(projectId: String, c: Comment) {
        val at = c.at?.takeIf { it.size == 2 }
        dao.upsert(
            CommentEntity(
                projectId = projectId, id = c.id, featureId = c.featureId?.ifEmpty { null }, body = c.body,
                atLng = at?.get(0), atLat = at?.get(1), authorId = c.authorId, nickname = c.nickname,
                createdAt = runCatching { Instant.parse(c.createdAt).toEpochMilli() }.getOrDefault(clock.millis()),
                seq = c.seq, urgent = c.urgent, rollCall = c.rollCall, answerTo = c.answerTo?.ifEmpty { null },
                backBy = c.backBy?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                backFrom = c.backFrom?.ifEmpty { null },
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
        pending = seq == 0L, mine = me != null && authorId == me, urgent = urgent,
        rollCall = rollCall, answerTo = answerTo,
        backBy = backBy?.let(Instant::ofEpochMilli), backFrom = backFrom,
    )

    companion object {
        /** Check-outs without a message closing them, soonest due first. */
        fun openOutings(items: List<CommentItem>): List<CommentItem> {
            val closed = items.mapNotNullTo(HashSet()) { it.backFrom }
            return items.filter { it.backBy != null && it.id !in closed }.sortedBy { it.backBy }
        }

        const val OUTING_DAYS = 7L
        const val MAX_CHARS = 2000
        const val ALERT_HOURS = 6L
        const val ROLL_CALL_HOURS = 24L
    }
}
