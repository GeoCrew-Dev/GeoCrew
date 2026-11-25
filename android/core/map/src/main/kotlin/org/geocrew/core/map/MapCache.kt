package org.geocrew.core.map

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.offline.OfflineManager
import kotlin.coroutines.resume

/**
 * MapLibre keeps the tiles it has shown in an on-device cache and uses
 * them without a network. A bigger cache means areas the user looked at
 * while online (at the zoom levels they looked at) stay usable in the
 * field. This is ordinary browsing cache, not bulk download, so it stays
 * within what tile providers allow.
 */
object MapCache {
    /** Choices offered in settings, in MB. */
    val SIZES_MB = listOf(50, 200, 500, 1000)
    const val DEFAULT_MB = 200

    suspend fun setSize(context: Context, megabytes: Int): Boolean = call { cb ->
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(megabytes.toLong() * 1024 * 1024, cb)
    }

    /** Drops every cached tile; offline MBTiles base maps are not affected. */
    suspend fun clear(context: Context): Boolean = call { cb ->
        OfflineManager.getInstance(context).clearAmbientCache(cb)
    }

    private suspend fun call(start: (OfflineManager.FileSourceCallback) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            start(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
}
