package org.geocrew.storage

import android.content.Context
import java.io.File
import org.geocrew.nativelib.GeoCrewNative

/**
 * Gets GeoCrew-Native ready: unpacks its data files (proj.db and GDAL's) from
 * the AAR's assets and points PROJ and GDAL at them. They are unpacked again
 * after each update of the app, and kept out of backups.
 */
object NativeData {
    private const val ASSETS = "geocrew-native"
    private var ready = false

    @Synchronized
    fun setup(context: Context) {
        if (ready) return
        val dir = File(context.noBackupFilesDir, ASSETS)
        val stamp = File(dir, ".unpacked")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!stamp.exists() || stamp.readText() != installed) {
            dir.deleteRecursively()
            dir.mkdirs()
            for (name in context.assets.list(ASSETS).orEmpty()) {
                context.assets.open("$ASSETS/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            }
            stamp.writeText(installed) // last, so a cut-short unpacking is done again
        }
        GeoCrewNative.setup(dir.path)
        ready = true
    }
}
