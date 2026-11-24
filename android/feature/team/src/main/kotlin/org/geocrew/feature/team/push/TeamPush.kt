package org.geocrew.feature.team.push

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.team.TeamRepository
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.network.ApiException
import org.geocrew.core.network.PushKeys
import org.geocrew.core.network.PushNotice
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background notices for linked projects through UnifiedPush: the user's
 * own distributor app (ntfy and the like) wakes us, no Google services
 * needed. Each project is one UnifiedPush instance, named by its id, so
 * several workspaces can be followed side by side.
 *
 * Notices carry only ids (protocol v0 §9); what they announce is fetched
 * over the normal authenticated sync.
 */
@Singleton
class TeamPush @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projects: ProjectRepository,
    private val team: TeamRepository,
    private val session: TeamSession,
) {
    /** True when at least one UnifiedPush distributor is installed. */
    fun hasDistributor(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /**
     * Picks the distributor (asking the user if there are several) and
     * registers [projectId]; the endpoint arrives later in [onNewEndpoint].
     */
    fun enable(activity: Activity, projectId: String, done: (Boolean) -> Unit) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(context, projectId, messageForDistributor = "同勘工作组通知")
            done(ok)
        }
    }

    /** Stops notices for [projectId] on the server and with the distributor. */
    suspend fun disable(projectId: String) {
        runCatching { UnifiedPush.unregister(context, projectId) }
        team.handle(projectId)?.let { h -> runCatching { h.api.deletePush(h.link.workspaceId) } }
        projects.updateConfig(projectId) { it.copy(pushEnabled = false) }
    }

    /** The distributor gave [projectId] an endpoint: hand it to the workspace server. */
    suspend fun onNewEndpoint(projectId: String, url: String, keys: PushKeys?) {
        val h = team.handle(projectId) ?: return
        try {
            h.api.setPush(h.link.workspaceId, url, keys)
            projects.updateConfig(projectId) { it.copy(pushEnabled = true) }
        } catch (e: ApiException) {
            // Older or push-less server, or an endpoint it refuses: leave notices off.
            projects.updateConfig(projectId) { it.copy(pushEnabled = false) }
            notify(projectId, "后台通知没有开启", explain(e), id = projectId.hashCode() xor 0x5050)
        }
    }

    fun explain(e: ApiException): String = when {
        e.status == 404 && e.code == "push_disabled" -> "这台服务器没有开启推送"
        e.status == 404 -> "服务器版本较旧，不支持推送"
        e.code == "invalid_endpoint" -> "服务器不接受这个推送地址：${e.message}"
        else -> "登记推送地址失败：${e.message}"
    }

    /** The distributor dropped the registration (app removed, user turned it off...). */
    suspend fun onUnregistered(projectId: String) {
        projects.updateConfig(projectId) { it.copy(pushEnabled = false) }
    }

    suspend fun onMessage(projectId: String, content: ByteArray) {
        val p = projects.get(projectId) ?: return
        val link = p.team ?: return
        val n = PushNotice.parse(content) ?: return
        if (n.workspace != link.workspaceId || n.from == link.memberId) return
        val text = when (n.type) {
            "message" -> "有新的组内消息"
            "comment" -> "记录下有新的讨论"
            "task" -> "有一条记录指派给你"
            else -> return
        }
        // Fetch what the notice announces when it concerns the open project.
        if (projects.currentId.first() == projectId) runCatching { session.syncNow() }
        notify(projectId, link.workspaceName, text, id = (projectId + n.type).hashCode())
    }

    private fun notify(projectId: String, title: String, text: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "工作组通知", NotificationManager.IMPORTANCE_DEFAULT))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, projectId.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }

    private companion object {
        const val CHANNEL = "team"
    }
}
