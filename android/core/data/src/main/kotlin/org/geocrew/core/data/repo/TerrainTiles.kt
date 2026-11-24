package org.geocrew.core.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.geocrew.core.model.DemSampler
import org.geocrew.core.model.Elevation
import org.geocrew.core.model.Terrarium
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ground heights from public Terrarium elevation tiles (AWS Terrain Tiles),
 * for terrain profiles. Tiles are kept in the cache folder, so a section
 * worked out once draws again offline. Every call is blocking.
 */
@Singleton
class TerrainTiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val defaults: BuildDefaults,
) {
    private val root get() = File(context.cacheDir, "dem")

    /** A fresh sampler; reuse it within one job so each tile is read once. */
    fun elevation(zoom: Int = ZOOM, urlTemplate: String = Terrarium.DEFAULT_URL): Elevation =
        DemSampler(zoom) { z, x, y -> pixels(urlTemplate, z, x, y) }

    private fun pixels(template: String, z: Int, x: Int, y: Int): IntArray? {
        val file = File(root, "$z/$x/$y.png")
        val bytes = if (file.isFile && file.length() > 0) file.readBytes() else fetch(template, z, x, y)?.also { b ->
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.part")
            tmp.writeBytes(b)
            if (!tmp.renameTo(file)) tmp.delete()
        } ?: return null
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: run {
            file.delete()
            return null
        }
        try {
            if (bmp.width != Terrarium.SIZE || bmp.height != Terrarium.SIZE) return null
            val out = IntArray(Terrarium.SIZE * Terrarium.SIZE)
            bmp.getPixels(out, 0, Terrarium.SIZE, 0, 0, Terrarium.SIZE, Terrarium.SIZE)
            return out
        } finally {
            bmp.recycle()
        }
    }

    private fun fetch(template: String, z: Int, x: Int, y: Int): ByteArray? {
        val req = Request.Builder().url(Terrarium.url(template, z, x, y)).header("User-Agent", defaults.userAgent).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.bytes()
        }
    }

    companion object {
        /** About 30 m per pixel at Chinese latitudes, the resolution of the SRTM data behind it. */
        const val ZOOM = 12
    }
}
