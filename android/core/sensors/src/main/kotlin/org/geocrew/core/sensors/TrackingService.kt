package org.geocrew.core.sensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.data.repo.TrackRepository
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.TrackPoint
import javax.inject.Inject
import javax.inject.Singleton

/** What the track recorder is doing; observed by the map screen. */
sealed interface TrackingState {
    data object Idle : TrackingState
    data class Recording(val trackId: String, val points: Int, val lengthMeters: Double, val last: LngLat?) : TrackingState
}

/** Entry point for UI code: start/stop recording and observe progress. */
@Singleton
class TrackingController @Inject constructor() {
    private val _state = MutableStateFlow<TrackingState>(TrackingState.Idle)
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    internal fun update(s: TrackingState) {
        _state.value = s
    }

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
    }
}

/**
 * Foreground service that keeps recording while the screen is off or the
 * app is in the background. Accepts only fixes better than [MAX_ACCURACY_M].
 */
@AndroidEntryPoint
class TrackingService : LifecycleService() {
    @Inject lateinit var controller: TrackingController
    @Inject lateinit var tracks: TrackRepository
    @Inject lateinit var projects: ProjectRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var location: LocationSource

    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> startRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (job != null) return
        if (!location.hasPermission()) {
            stopSelf()
            return
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("正在准备…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeoCrew:track")
            ?.apply { acquire(12 * 60 * 60 * 1000L) }
        job = lifecycleScope.launch {
            val cfg = settings.current()
            val project = projects.ensureCurrent()
            val track = tracks.start(project.id)
            var count = 0
            var length = 0.0
            var last: LngLat? = null
            controller.update(TrackingState.Recording(track.id, 0, 0.0, null))
            location.fixes(cfg.trackIntervalSeconds * 1000L, cfg.trackMinDistanceMeters.toFloat()).collect { fix ->
                if ((fix.accuracy ?: 0f) > MAX_ACCURACY_M) return@collect
                last?.let { prev ->
                    val d = Geodesy.distance(prev, fix.position)
                    if (d < cfg.trackMinDistanceMeters) return@collect
                    length += d
                }
                tracks.append(track.id, TrackPoint(fix.position, fix.time, fix.accuracy, fix.speed, fix.bearing))
                last = fix.position
                count++
                if (count % COMMIT_EVERY == 0) tracks.commit(track.id, finished = false)
                controller.update(TrackingState.Recording(track.id, count, length, last))
                notify("已记录 $count 点 · ${CoordinateFormatter.distance(length)}")
            }
        }
    }

    private fun stopRecording() {
        val s = controller.state.value
        job?.cancel()
        job = null
        lifecycleScope.launch {
            if (s is TrackingState.Recording) tracks.commit(s.trackId, finished = true)
            controller.update(TrackingState.Idle)
            wakeLock?.takeIf { it.isHeld }?.release()
            ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "轨迹记录", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("同勘 · 轨迹记录中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "结束", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "org.geocrew.track.START"
        const val ACTION_STOP = "org.geocrew.track.STOP"
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 42
        private const val MAX_ACCURACY_M = 50f
        private const val COMMIT_EVERY = 10
    }
}
