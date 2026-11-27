package org.geocrew.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.geocrew.core.project.Templates
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * A position recorded on a track: WGS84 degrees, taken as CGCS2000 (ADR 0015),
 * the height above the ellipsoid in metres and how far off it may be, when
 * known, and when it was taken.
 */
data class TrackPoint(val longitude: Double, val latitude: Double, val altitude: Double?, val accuracy: Float?, val time: Long)

/**
 * A track being recorded, or paused: the project it goes into (its id, and its
 * name for saying so), when it started, its points and their length in metres
 * along the ellipsoid, and whether GNSS is recording now. A track the system
 * cut off while it recorded (the app stopped, or its service ended) is paused
 * and [interrupted] until the user goes on with it or ends it.
 */
data class Track(
    val projectId: String,
    val projectName: String,
    val started: Long,
    val points: List<TrackPoint>,
    val length: Double,
    val recording: Boolean,
    val interrupted: Boolean = false,
)

/** The launch intent's extra that the notification's "end" sets: the map then asks where the track goes. */
const val EXTRA_FINISH_TRACK = "org.geocrew.android.FINISH_TRACK"

/** Whether the phone leaves the app out of battery optimization (Doze), as a long recording with the screen off wants. */
fun unrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/**
 * Asks the phone in its own dialog to leave the app out of battery
 * optimization; with that done already, opens the list where it can be undone.
 */
@SuppressLint("BatteryLife") // a GPS tracker, outside Google Play: the use the request is meant for
fun askUnrestricted(context: Context) {
    val ask = if (unrestricted(context)) {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    } else {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    }
    runCatching { context.startActivity(ask) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

/**
 * Records a GPS track, one at a time, through [TrackService]: a foreground
 * service of the location kind, so it goes on with the screen off and the app
 * in the background, with no "allow all the time" (docs/compliance.md). Each
 * point is written to a file as it comes, so a recording the system ends, or
 * a phone turned off, keeps its points: the track comes back paused, and said
 * to be cut off ([Track.interrupted]).
 */
class TrackRecorder(private val context: Context) {
    private val file = File(context.noBackupFilesDir, "track.csv")

    // There while the user means the track to record: one there when the app starts was cut off. Made before the track is read.
    private val marker = File(context.noBackupFilesDir, "track.recording")
    private val state = MutableStateFlow(read())
    private val finish = MutableStateFlow(false)

    /** The track there is, recording or paused; null when there is none. */
    val track: StateFlow<Track?> = state.asStateFlow()

    /** The notification asked to end the track: the map asks where it goes, then takes the request ([finishTaken]). */
    val finishing: StateFlow<Boolean> = finish.asStateFlow()

    /** Starts recording a track that goes into project [projectId]; false when one is there already or it cannot start. */
    @Synchronized
    fun start(projectId: String, projectName: String): Boolean {
        if (state.value != null) return false
        val now = System.currentTimeMillis()
        val written = runCatching {
            file.parentFile?.mkdirs()
            file.writeText("$HEADER,$projectId,$now,$projectName\n")
        }
        if (written.isFailure) return false
        mark(true)
        state.value = Track(projectId, projectName, now, emptyList(), 0.0, recording = true)
        if (!startService()) {
            discard()
            return false
        }
        return true
    }

    /** Stops recording; the track stays, to go on with or to save. */
    @Synchronized
    fun pause() {
        context.stopService(Intent(context, TrackService::class.java))
        mark(false)
        state.value = state.value?.copy(recording = false, interrupted = false)
    }

    /** Goes on recording a paused track; false when it cannot start. */
    @Synchronized
    fun resume(): Boolean {
        val track = state.value ?: return false
        if (track.recording) return true
        mark(true)
        state.value = track.copy(recording = true, interrupted = false)
        if (startService()) return true
        // As it was: cut off, or paused by the user.
        mark(track.interrupted)
        state.value = track
        return false
    }

    /** Ends the track without keeping it: no more points, and its file is gone. */
    @Synchronized
    fun discard() {
        context.stopService(Intent(context, TrackService::class.java))
        file.delete()
        mark(false)
        state.value = null
        finish.value = false
    }

    /** From the notification: the track stops recording, and the map asks where it goes ([finishing]). */
    @Synchronized
    fun requestFinish() {
        if (state.value == null) return
        pause()
        finish.value = true
    }

    /** The map has taken the request to end the track. */
    fun finishTaken() {
        finish.value = false
    }

    /**
     * Adds [fix] to the track being recorded, unless it may be more than
     * [MAX_ACCURACY_M] off or is within [MIN_STEP_M] (or half its accuracy)
     * of the last point: standing still adds nothing.
     */
    @Synchronized
    internal fun record(fix: Location) {
        val track = state.value?.takeIf { it.recording } ?: return
        val accuracy = if (fix.hasAccuracy()) fix.accuracy else null
        if (accuracy != null && accuracy > MAX_ACCURACY_M) return
        val last = track.points.lastOrNull()
        val step = last?.let { distance(it.latitude, it.longitude, fix.latitude, fix.longitude) } ?: 0.0
        if (last != null && step < maxOf(MIN_STEP_M, (accuracy ?: 0f) / 2.0)) return
        val point = TrackPoint(fix.longitude, fix.latitude, if (fix.hasAltitude()) fix.altitude else null, accuracy, fix.time)
        // A point that cannot be written is still shown and saved with the track; only a crash would lose it.
        runCatching { file.appendText(line(point)) }
        state.value = track.copy(points = track.points + point, length = track.length + step)
    }

    /** The service could not record (no GNSS, or not let into the foreground): the track, if any, is paused. */
    @Synchronized
    internal fun stopped() {
        mark(false)
        state.value = state.value?.copy(recording = false)
    }

    /**
     * The service ended while the track recorded: not by the user, who pauses or ends a track first. The track is
     * paused, cut off, and still meant to record (its marker stays); true when there was one.
     */
    @Synchronized
    internal fun interrupted(): Boolean {
        val track = state.value?.takeIf { it.recording } ?: return false
        state.value = track.copy(recording = false, interrupted = true)
        return true
    }

    private fun mark(recording: Boolean) {
        runCatching { if (recording) marker.createNewFile() else marker.delete() }
    }

    private fun startService(): Boolean =
        runCatching { context.startForegroundService(Intent(context, TrackService::class.java)) }.isSuccess

    // The track the file holds, paused: the service does not outlive the process (it is not sticky), nor may a
    // service of the location kind start again from the background. Recording when the app stopped, it was cut off.
    private fun read(): Track? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val head = lines.firstOrNull()?.split(",", limit = 4) ?: return null
        if (head.size < 4 || head[0] != HEADER) return null
        val started = head[2].toLongOrNull() ?: return null
        // A line cut short as the process died is left out.
        val points = lines.drop(1).mapNotNull(::point)
        val length = points.zipWithNext { a, b -> distance(a.latitude, a.longitude, b.latitude, b.longitude) }.sum()
        return Track(head[1], head[3], started, points, length, recording = false, interrupted = marker.exists())
    }

    private fun line(p: TrackPoint) = "${p.time},${p.longitude},${p.latitude},${p.altitude ?: ""},${p.accuracy ?: ""}\n"

    private fun point(line: String): TrackPoint? {
        val parts = line.split(",")
        if (parts.size != 5) return null
        val time = parts[0].toLongOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
        val latitude = parts[2].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
        return TrackPoint(longitude, latitude, parts[3].toDoubleOrNull(), parts[4].toFloatOrNull(), time)
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private companion object {
        const val HEADER = "geocrew-track 1"

        // A fix that may be further off than this is left out: under trees GNSS gives 20 to 50 metres.
        const val MAX_ACCURACY_M = 50f

        // The least a point is from the last before it is recorded.
        const val MIN_STEP_M = 5.0
    }
}

/**
 * Keeps GNSS on for [TrackRecorder] while a track records: a foreground service
 * of the location kind, with the notification such a service must show. The
 * notification counts the time, gives the length, and pauses or ends the
 * track; tapping it goes back to the app. A partial wake lock keeps the CPU up
 * for the points and their file with the screen off (in Doze only when the app
 * is left out of battery optimization, see [askUnrestricted]).
 */
class TrackService : Service() {
    private val tracks get() = (application as GeoCrewApplication).graph.tracks
    private val executor = Executors.newSingleThreadExecutor()
    private var listener: LocationListener? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // When the notification's length last changed, and how many points it was made of: on the listener's thread only.
    private var notified = 0L
    private var shownPoints = -1

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // the recorder starts only with location granted; without it startForeground throws
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val track = tracks.track.value
        // The service was started to be in the foreground: it is, before anything else, or the app is ended.
        val shown = runCatching {
            startForeground(NOTIFICATION_ID, notification(track), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.isSuccess
        getSystemService(NotificationManager::class.java)?.cancel(INTERRUPTED_ID)
        val manager = getSystemService(LocationManager::class.java)
        if (!shown || track?.recording != true || manager == null) {
            tracks.stopped()
            stopSelf()
            return START_NOT_STICKY
        }
        if (listener == null) {
            val l = LocationListener { fix ->
                tracks.record(fix)
                update()
            }
            val asked = runCatching { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, executor, l) }.isSuccess
            if (!asked) {
                tracks.stopped()
                stopSelf()
                return START_NOT_STICKY
            }
            listener = l
            // Held while the track records, and let go when the service ends; the limit is only there if that never comes.
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeoCrew:track")?.apply { acquire(WAKE_LIMIT_MS) }
        }
        return START_NOT_STICKY
    }

    // The notification follows the track's length as points come, every few seconds at most: under the recorder's
    // lock, so that it never comes back for a track paused meanwhile, after stopping the service took it away.
    private fun update() {
        val now = System.currentTimeMillis()
        if (now - notified < UPDATE_MS) return
        val notifications = getSystemService(NotificationManager::class.java) ?: return
        synchronized(tracks) {
            val track = tracks.track.value?.takeIf { it.recording && it.points.size != shownPoints } ?: return
            notifications.notify(NOTIFICATION_ID, notification(track))
            shownPoints = track.points.size
            notified = now
        }
    }

    // Stopped by the recorder, which has paused or ended the track already; or by the system, while it records.
    override fun onDestroy() {
        listener?.let { l -> getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        listener = null
        executor.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        // Ended while the track records: by the system, as the user pauses or ends a track first. Its notification,
        // should a length have come after the system took it away, gives way to one that says so.
        if (tracks.interrupted()) {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications?.cancel(NOTIFICATION_ID)
            channel()
            notifications?.notify(
                INTERRUPTED_ID,
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_track)
                    .setContentTitle(getString(R.string.track_interrupted))
                    .setContentIntent(launch(0, finish = false))
                    .setAutoCancel(true)
                    .build(),
            )
        }
        super.onDestroy()
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.track_channel), NotificationManager.IMPORTANCE_LOW))
    }

    // The app as it was left, as its icon opens it; with [finish], asked to end the track.
    private fun launch(request: Int, finish: Boolean): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        if (finish) it.putExtra(EXTRA_FINISH_TRACK, true)
        PendingIntent.getActivity(this, request, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notification(track: Track?): Notification {
        channel()
        val pause = PendingIntent.getBroadcast(
            this, 1, Intent(this, TrackActionReceiver::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_track)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_track)
            .setContentTitle(getString(R.string.track_recording))
            .setContentText(if (track == null || track.points.isEmpty()) getString(R.string.track_waiting) else distanceText(track.length))
            .setWhen(track?.started ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch(0, finish = false))
            .addAction(Notification.Action.Builder(icon, getString(R.string.track_pause), pause).build())
            .addAction(Notification.Action.Builder(icon, getString(R.string.track_finish), launch(2, finish = true)).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    internal companion object {
        const val ACTION_PAUSE = "org.geocrew.android.PAUSE_TRACK"
        const val CHANNEL = "track"
        const val NOTIFICATION_ID = 1
        const val INTERRUPTED_ID = 2

        // How often the notification's length is brought up to date, at most.
        const val UPDATE_MS = 5_000L

        // The wake lock's limit, should the service never end: a long field day.
        const val WAKE_LIMIT_MS = 16 * 3_600_000L

        // How often GNSS gives a position while recording.
        const val INTERVAL_MS = 2_000L
    }
}

/** Opens the app's notification settings, where notifications (and the track's channel) are turned on. */
fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
}

/** The notification's pause: the track stops recording, as from the map. */
class TrackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TrackService.ACTION_PAUSE) (context.applicationContext as GeoCrewApplication).graph.tracks.pause()
    }
}

/** Whether the track's notification shows: notifications are allowed, and its channel is not turned off. */
fun notificationsShown(context: Context): Boolean {
    val manager = NotificationManagerCompat.from(context)
    return manager.areNotificationsEnabled() &&
        manager.getNotificationChannelCompat(TrackService.CHANNEL)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
}

/** Adds the line of the track being recorded to [style], with a white edge so it shows on any imagery. */
fun addTrackLayers(style: Style) {
    style.addSource(GeoJsonSource(TRACK_SOURCE))
    style.addLayer(
        LineLayer("$TRACK_SOURCE-edge", TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor("#ffffff"),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineOpacity(0.8f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    style.addLayer(
        LineLayer(TRACK_SOURCE, TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(TRACK_COLOR),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
}

/** Shows [points] as the track's line on the map; none for null. */
fun showTrack(style: Style, points: List<TrackPoint>?) {
    style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(trackGeoJson(points))
}

// [points] as the track's source wants them: a line once there are two.
private fun trackGeoJson(points: List<TrackPoint>?): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        if (points != null && points.size >= 2) {
            add(
                buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") {}
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        putJsonArray("coordinates") { for (p in points) addJsonArray { add(p.longitude); add(p.latitude) } }
                    }
                },
            )
        }
    }
}.toString()

/**
 * The values a saved track starts with in layer [layer] (as project.current
 * lists it, its fields as [collection] has them): its name, when it started;
 * in a track layer also when it started and ended, and its length.
 */
fun trackValues(layer: JsonObject?, collection: JsonObject?, track: Track): JsonObject {
    val names = fieldNames(collection)
    val template = Templates.byId((layer?.get("template") as? JsonPrimitive)?.contentOrNull)
    val ended = track.points.lastOrNull()?.time ?: track.started
    return buildJsonObject {
        // Named when it started, in the phone's time zone.
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        nameField(collection)?.let { put(it, format.format(Instant.ofEpochMilli(track.started))) }
        if (template != Templates.TRACK) return@buildJsonObject
        template.column(Templates.STARTED, names)?.let { put(it, Instant.ofEpochMilli(track.started).truncatedTo(ChronoUnit.SECONDS).toString()) }
        template.column(Templates.ENDED, names)?.let { put(it, Instant.ofEpochMilli(ended).truncatedTo(ChronoUnit.SECONDS).toString()) }
        template.column(Templates.LENGTH, names)?.let { put(it, (track.length * 10).roundToLong() / 10.0) }
    }
}

/**
 * The track recording or paused, over the map: how long it is and how long it
 * has taken; pause, go on, end (and save), or throw a paused one away.
 */
@Composable
fun TrackPanel(track: Track, onPause: () -> Unit, onResume: () -> Unit, onFinish: () -> Unit, onDiscard: () -> Unit) {
    val context = LocalContext.current
    // The time goes on while it records.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(track.recording) {
        while (track.recording) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val until = if (track.recording) now else track.points.lastOrNull()?.time ?: track.started
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                track.recording -> Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                track.interrupted -> Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Rounded.Pause, null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = when {
                    track.recording -> R.string.track_recording
                    track.interrupted -> R.string.track_interrupted
                    else -> R.string.track_paused
                }
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                val detail = if (track.recording && track.points.isEmpty()) {
                    stringResource(R.string.track_waiting)
                } else {
                    distanceText(track.length) + " · " + DateUtils.formatElapsedTime(((until - track.started) / 1_000).coerceAtLeast(0))
                }
                Text(detail, style = MaterialTheme.typography.bodySmall)
                // Without notifications the recording goes on unseen in the notification shade: said, with the way to turn them on.
                // Read every second while it records, so turning them on shows at once.
                if (track.recording && !notificationsShown(context)) {
                    Text(
                        stringResource(R.string.track_no_notification),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { openNotificationSettings(context) },
                    )
                }
            }
            if (track.recording) {
                IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, stringResource(R.string.track_pause)) }
            } else {
                IconButton(onClick = onDiscard) { Icon(Icons.Rounded.Delete, stringResource(R.string.track_discard)) }
                IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.track_resume)) }
            }
            IconButton(onClick = onFinish) { Icon(Icons.Rounded.Stop, stringResource(R.string.track_finish)) }
        }
    }
}

// The track's source on the map, and its colour: none of the layers' own.
private const val TRACK_SOURCE = "geocrew-track"
private const val TRACK_COLOR = "#aa00ff"
