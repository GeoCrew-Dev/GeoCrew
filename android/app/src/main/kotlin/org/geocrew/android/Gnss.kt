package org.geocrew.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** A position from the phone: WGS84, taken as CGCS2000 (ADR 0015), with how far off it may be, in metres. */
data class Fix(val longitude: Double, val latitude: Double, val accuracy: Float?, val time: Long)

/** Whether the app may ask the phone where it is. */
fun mayLocate(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * One position from the phone through its own location service, so no Google
 * services are needed: GNSS first, else the fused or network provider. A fix
 * at most [freshMs] old is used at once; otherwise this waits up to [waitMs]
 * for a new one. Null without permission or when no provider gives one in time.
 */
@SuppressLint("MissingPermission") // mayLocate checks it
suspend fun currentFix(context: Context, freshMs: Long = 10_000, waitMs: Long = 30_000): Fix? {
    if (!mayLocate(context)) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) return null
    val now = System.currentTimeMillis()
    providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { now - it.time <= freshMs }
        .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        ?.let { return it.toFix() }
    return withTimeoutOrNull(waitMs) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            manager.getCurrentLocation(providers.first(), signal, context.mainExecutor) { location ->
                if (continuation.isActive) continuation.resume(location?.toFix())
            }
            continuation.invokeOnCancellation { signal.cancel() }
        }
    }
}

private fun Location.toFix() = Fix(longitude, latitude, if (hasAccuracy()) accuracy else null, time)
