package org.geocrew.core.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.geocrew.core.model.LngLat
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One position fix from the platform. */
data class Fix(
    val position: LngLat,
    /** Horizontal accuracy radius in metres (68% confidence). */
    val accuracy: Float?,
    val bearing: Float?,
    val speed: Float?,
    val time: Instant,
    val provider: String,
)

/**
 * Positions straight from Android's LocationManager. Deliberately avoids
 * Google Play services so the app works on phones sold without them.
 */
@Singleton
class LocationSource @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(): Boolean = manager?.isLocationEnabled == true

    /**
     * Emits fixes while collected. GPS is preferred; network/fused fixes
     * fill in until GPS gets a lock and are dropped once GPS is fresher.
     */
    @SuppressLint("MissingPermission")
    fun fixes(intervalMillis: Long = 1000, minDistanceMeters: Float = 0f): Flow<Fix> = callbackFlow {
        val lm = manager
        if (lm == null || !hasPermission()) {
            close()
            return@callbackFlow
        }
        var lastGps = 0L
        val listener = LocationListener { loc ->
            val fix = loc.toFix()
            if (loc.provider == LocationManager.GPS_PROVIDER) {
                lastGps = loc.elapsedRealtimeNanos
            } else if (lastGps != 0L && loc.elapsedRealtimeNanos - lastGps < 10_000_000_000L) {
                return@LocationListener
            }
            trySend(fix)
        }
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let { trySend(it.toFix()) }

        for (p in providers) {
            runCatching { lm.requestLocationUpdates(p, intervalMillis, minDistanceMeters, listener, Looper.getMainLooper()) }
        }
        awaitClose { lm.removeUpdates(listener) }
    }
}

internal fun Location.toFix() = Fix(
    position = LngLat(longitude, latitude, if (hasAltitude()) altitude else null),
    accuracy = if (hasAccuracy()) accuracy else null,
    bearing = if (hasBearing()) bearing else null,
    speed = if (hasSpeed()) speed else null,
    time = Instant.ofEpochMilli(time),
    provider = provider ?: "unknown",
)
