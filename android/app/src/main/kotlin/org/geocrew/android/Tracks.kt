package org.geocrew.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.text.format.DateUtils
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
 * along the ellipsoid, and whether GNSS is recording now.
 */
data class Track(
    val projectId: String,
    val projectName: String,
    val started: Long,
    val points: List<TrackPoint>,
    val length: Double,
    val recording: Boolean,
)

/**
 * Records a GPS track, one at a time, through [TrackService]: a foreground
 * service of the location kind, so it goes on with the screen off and the app
 * in the background, with no "allow all the time" (docs/compliance.md). Each
 * point is written to a file as it comes, so a recording the system ends, or
 * a phone turned off, keeps its points: the track comes back paused.
 */
class TrackRecorder(private val context: Context) {
    private val file = File(context.noBackupFilesDir, "track.csv")
    private val state = MutableStateFlow(read())

    /** The track there is, recording or paused; null when there is none. */
    val track: StateFlow<Track?> = state.asStateFlow()

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
        state.value = state.value?.copy(recording = false)
    }

    /** Goes on recording a paused track; false when it cannot start. */
    @Synchronized
    fun resume(): Boolean {
        val track = state.value ?: return false
        if (track.recording) return true
        state.value = track.copy(recording = true)
        if (startService()) return true
        state.value = track
        return false
    }

    /** Ends the track without keeping it: no more points, and its file is gone. */
    @Synchronized
    fun discard() {
        context.stopService(Intent(context, TrackService::class.java))
        file.delete()
        state.value = null
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
        state.value = state.value?.copy(recording = false)
    }

    private fun startService(): Boolean =
        runCatching { context.startForegroundService(Intent(context, TrackService::class.java)) }.isSuccess

    // The track the file holds, paused: the service does not outlive the process (it is not sticky).
    private fun read(): Track? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val head = lines.firstOrNull()?.split(",", limit = 4) ?: return null
        if (head.size < 4 || head[0] != HEADER) return null
        val started = head[2].toLongOrNull() ?: return null
        // A line cut short as the process died is left out.
        val points = lines.drop(1).mapNotNull(::point)
        val length = points.zipWithNext { a, b -> distance(a.latitude, a.longitude, b.latitude, b.longitude) }.sum()
        return Track(head[1], head[3], started, points, length, recording = false)
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
 * Keeps GNSS on for [TrackRecorder] while a track records, with a notification
 * that says so and counts the time; tapping it goes back to the app.
 */
class TrackService : Service() {
    private val tracks get() = (application as GeoCrewApplication).graph.tracks
    private val executor = Executors.newSingleThreadExecutor()
    private var listener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // the recorder starts only with location granted; without it startForeground throws
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val track = tracks.track.value
        // The service was started to be in the foreground: it is, before anything else, or the app is ended.
        val shown = runCatching {
            startForeground(NOTIFICATION_ID, notification(track?.started ?: System.currentTimeMillis()), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.isSuccess
        val manager = getSystemService(LocationManager::class.java)
        if (!shown || track?.recording != true || manager == null) {
            tracks.stopped()
            stopSelf()
            return START_NOT_STICKY
        }
        if (listener == null) {
            val l = LocationListener { tracks.record(it) }
            val asked = runCatching { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, executor, l) }.isSuccess
            if (!asked) {
                tracks.stopped()
                stopSelf()
                return START_NOT_STICKY
            }
            listener = l
        }
        return START_NOT_STICKY
    }

    // Stopped by the recorder, which has paused or ended the track already.
    override fun onDestroy() {
        listener?.let { l -> getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        listener = null
        executor.shutdown()
        super.onDestroy()
    }

    private fun notification(started: Long): Notification {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.track_channel), NotificationManager.IMPORTANCE_LOW))
        // Back to the app as it was left, as its icon does.
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_track)
            .setContentTitle(getString(R.string.track_recording))
            .setWhen(started)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val CHANNEL = "track"
        const val NOTIFICATION_ID = 1

        // How often GNSS gives a position while recording.
        const val INTERVAL_MS = 2_000L
    }
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
            if (track.recording) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            } else {
                Icon(Icons.Rounded.Pause, null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (track.recording) R.string.track_recording else R.string.track_paused), style = MaterialTheme.typography.titleMedium)
                val detail = if (track.recording && track.points.isEmpty()) {
                    stringResource(R.string.track_waiting)
                } else {
                    distanceText(track.length) + " · " + DateUtils.formatElapsedTime(((until - track.started) / 1_000).coerceAtLeast(0))
                }
                Text(detail, style = MaterialTheme.typography.bodySmall)
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
