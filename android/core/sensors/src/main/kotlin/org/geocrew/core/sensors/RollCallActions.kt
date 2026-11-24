package org.geocrew.core.sensors

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.geocrew.core.data.team.TeamComments
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.model.LngLat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starting, answering and ending a roll call (protocol v0 §8.2), shared by
 * the team chat and the map. Answers carry this phone's position; asking
 * for help also makes the answer urgent, so the whole team is alerted.
 * Everything is stored first and sent when there is signal.
 */
@Singleton
class RollCallActions @Inject constructor(
    private val comments: TeamComments,
    private val session: TeamSession,
    private val location: LocationSource,
) {
    /** Starts a roll call; returns a Chinese reason when it can't. */
    suspend fun start(projectId: String, text: String): String? {
        val body = text.trim().ifEmpty { DEFAULT_CALL }
        if (!comments.post(projectId, body, rollCall = true)) return "当前工程没有加入工作组"
        runCatching { session.syncNow() }
        return null
    }

    /**
     * Answers roll call [callId], all well when [ok] or asking for help.
     * Returns a note for the user, e.g. that the map centre stood in for a
     * position, or null.
     */
    suspend fun answer(projectId: String, callId: String, ok: Boolean, fallback: LngLat?): String? {
        val fix = currentPosition()
        val at = fix ?: fallback
        val body = if (ok) ANSWER_OK else ANSWER_HELP
        if (!comments.post(projectId, body, at = at, urgent = !ok, answerTo = callId)) return "当前工程没有加入工作组"
        runCatching { session.syncNow() }
        return when {
            fix != null -> null
            at != null -> "没有拿到定位，回应附的是地图中心位置。没有信号时会在联网后发出。"
            else -> "没有拿到定位，回应没有附位置。没有信号时会在联网后发出。"
        }
    }

    /** Ends a roll call by deleting it (its author or a leader); needs signal. */
    suspend fun end(callId: String) = session.deleteComment(callId)

    private suspend fun currentPosition(): LngLat? {
        if (!location.hasPermission() || !location.isEnabled()) return null
        return withTimeoutOrNull(FIX_TIMEOUT_MILLIS) { location.fixes(intervalMillis = 1000).first() }?.position
    }

    companion object {
        const val DEFAULT_CALL = "收工了，请大家报平安"
        const val ANSWER_OK = "平安"
        const val ANSWER_HELP = "需要帮助"
        private const val FIX_TIMEOUT_MILLIS = 15_000L
    }
}
