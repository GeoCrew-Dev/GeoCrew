package org.geocrew.core.data.repo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps an offline base map download going while
 * the screen is off or another app is in front, with its progress and a
 * pause button in the notification. It only watches [BasemapDownloads],
 * which does the work, and stops itself once the download is no longer
 * running.
 */
@AndroidEntryPoint
class BasemapDownloadService : Service() {
    @Inject lateinit var downloads: BasemapDownloads

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            downloads.pause()
            if (watching == null) stopSelf()
            return START_NOT_STICKY
        }
        val running = downloads.current.value?.takeIf { !it.finished }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(running), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watching == null) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geocrew:basemap-download")
                    ?.apply { acquire(MAX_HOLD_MILLIS) }
            }
            watching = scope.launch {
                downloads.current
                    .map { d -> d?.takeIf { !it.finished }?.let { it.name to percent(it) } }
                    .distinctUntilChanged()
                    .collect { shown ->
                        if (shown == null) finish() else notify(downloads.current.value)
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        watching?.cancel()
        watching = null
    }

    override fun onDestroy() {
        scope.cancel()
        watching = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun percent(d: BasemapDownload) = if (d.total == 0L) 0 else (d.done * 100 / d.total).toInt()

    private fun notify(d: BasemapDownload?) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(d))
    }

    private fun notification(d: BasemapDownload?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "离线底图下载", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val pause = PendingIntent.getService(
            this, 2, Intent(this, BasemapDownloadService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(d?.let { "同勘 · 正在下载「${it.name}」" } ?: "同勘 · 正在下载离线底图")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "暂停", pause)
        if (d != null) {
            builder.setContentText("${d.done} / ${d.total}，暂停后可继续")
            builder.setProgress(100, percent(d), false)
        }
        return builder.build()
    }

    companion object {
        private const val ACTION_PAUSE = "org.geocrew.basemap.PAUSE"
        private const val CHANNEL = "basemap-download"
        private const val NOTIFICATION_ID = 43
        /** A download is capped at 20 000 requests; this is far more than that takes. */
        private const val MAX_HOLD_MILLIS = 6 * 60 * 60 * 1000L

        /** Called by [BasemapDownloads] when a download starts; quietly does nothing if the system won't allow it. */
        internal fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BasemapDownloadService::class.java))
            } catch (e: IllegalStateException) {
                // Started from the background on Android 12+: the download still runs while the app is open.
            } catch (e: SecurityException) {
                // Should not happen with the permissions declared; carry on without the notification.
            }
        }
    }
}
