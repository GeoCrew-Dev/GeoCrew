package org.geocrew.core.sensors

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.geocrew.core.data.team.CommentItem
import org.geocrew.core.data.team.TeamComments
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.model.LngLat
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checking out before going off alone and back in on return (protocol v0
 * §8.3), shared by the team chat and the map. Both carry this phone's
 * position. Everything is stored first and sent when there is signal; when
 * the server sees a check-out pass its time with nobody checking back in,
 * it alerts the whole team.
 */
@Singleton
class OutingActions @Inject constructor(
    private val comments: TeamComments,
    private val session: TeamSession,
    private val location: LocationSource,
) {
    /** Checks out to [where], back by [backBy]. Returns a note for the user, or null. */
    suspend fun checkOut(projectId: String, where: String, backBy: Instant, fallback: LngLat?): String? {
        val fix = currentPosition()
        val body = where.trim().ifEmpty { DEFAULT_OUT }
        if (!comments.post(projectId, body, at = fix ?: fallback, backBy = backBy)) return "当前工程没有加入工作组"
        runCatching { session.syncNow() }
        return if (fix == null) "没有拿到定位，报备附的是地图中心位置。没有信号时会在联网后发出。" else null
    }

    /**
     * Checks [outing] back in; its author or a leader may. One that was
     * never sent is simply dropped, so nobody is alerted about it.
     */
    suspend fun checkIn(projectId: String, outing: CommentItem, fallback: LngLat?): String? {
        if (outing.pending) {
            comments.discard(projectId, outing.id)
            return null
        }
        val fix = currentPosition()
        val body = if (outing.mine) BACK else BACK_FOR + outing.nickname.ifBlank { "组员" }
        if (!comments.post(projectId, body, at = fix ?: fallback, backFrom = outing.id)) return "当前工程没有加入工作组"
        runCatching { session.syncNow() }
        return null
    }

    private suspend fun currentPosition(): LngLat? {
        if (!location.hasPermission() || !location.isEnabled()) return null
        return withTimeoutOrNull(FIX_TIMEOUT_MILLIS) { location.fixes(intervalMillis = 1000).first() }?.position
    }

    companion object {
        const val DEFAULT_OUT = "单独外出"
        const val BACK = "已返回"
        const val BACK_FOR = "已确认返回："
        private const val FIX_TIMEOUT_MILLIS = 15_000L
    }
}
