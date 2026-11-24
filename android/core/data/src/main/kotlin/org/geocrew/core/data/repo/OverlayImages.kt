package org.geocrew.core.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.geocrew.core.model.ImageOverlay
import org.geocrew.core.model.WorldFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** An image copied into the overlay folder, ready to be placed on the map. */
data class ImportedOverlay(
    val name: String,
    val file: String,
    val width: Int,
    val height: Int,
    /** Size of the picture as picked, which a world file refers to. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    val worldFile: WorldFile.Affine?,
)

/**
 * Images laid over the map. They are scaled down so the longest side is at
 * most [MAX_SIDE] pixels (what phones can draw as one texture), stored once
 * under the SHA-256 of their bytes and listed in each project's
 * [org.geocrew.core.model.ProjectConfig.overlays].
 */
@Singleton
class OverlayImages @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.filesDir, "overlays").apply { mkdirs() }

    fun file(name: String): File? = name.takeIf(ImageOverlay::isFileName)?.let { File(dir, it) }

    /**
     * Copies the picked image and reads a world file picked with it. [uris]
     * may hold both in any order; anything else is ignored. Throws
     * [IllegalArgumentException] with a Chinese message when no usable image is there.
     */
    suspend fun import(uris: List<Uri>): ImportedOverlay = withContext(Dispatchers.IO) {
        val named = uris.map { it to displayName(it) }
        val (image, imageName) = named.firstOrNull { (_, n) -> n.substringAfterLast('.', "").lowercase() in IMAGE_EXT }
            ?: named.firstOrNull { (u, _) -> context.contentResolver.getType(u)?.startsWith("image/") == true }
            ?: throw IllegalArgumentException(
                if (named.any { (_, n) -> n.endsWith(".tif", true) || n.endsWith(".tiff", true) }) "暂不支持 TIFF，请先转成 JPG 或 PNG"
                else "没有选到图片（JPG 或 PNG）",
            )
        val world = named.firstOrNull { (_, n) -> n.substringAfterLast('.', "").lowercase() in WORLD_EXT }?.let { (u, _) ->
            context.contentResolver.openInputStream(u)?.use { WorldFile.parse(it.readBytes().decodeToString()) }
                ?: throw IllegalArgumentException("world 文件读不懂：应当是 6 行数字")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(image)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw IllegalArgumentException("图片打不开")
        var sample = 1
        while (maxOf(w, h) / sample > MAX_SIDE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(image)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("图片打不开")
        val png = bounds.outMimeType == "image/png"
        val stored = try {
            store(bitmap, png)
        } finally {
            bitmap.recycle()
        }
        ImportedOverlay(
            name = imageName.substringBeforeLast('.').ifBlank { "叠加图片" },
            file = stored.first,
            width = stored.second,
            height = stored.third,
            sourceWidth = w,
            sourceHeight = h,
            worldFile = world,
        )
    }

    private fun store(bitmap: Bitmap, png: Boolean): Triple<String, Int, Int> {
        val tmp = File.createTempFile("ovl_", ".tmp", context.cacheDir)
        try {
            tmp.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
            val name = sha256(tmp) + if (png) ".png" else ".jpg"
            val dst = File(dir, name)
            if (!dst.exists()) tmp.copyTo(dst)
            return Triple(name, bitmap.width, bitmap.height)
        } finally {
            tmp.delete()
        }
    }

    /** Decodes a stored image for drawing (blocking; call off the main thread). Null when it is missing. */
    fun load(name: String): Bitmap? = file(name)?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /** Stores an image read from a project package under [name] when the name is valid (blocking). */
    fun putBlocking(name: String, input: InputStream): Boolean {
        val dst = file(name) ?: return false
        if (dst.exists()) return true
        val tmp = File.createTempFile("ovl_", ".tmp", context.cacheDir)
        try {
            tmp.outputStream().use { input.copyTo(it) }
            // Only keep it if it really is the file the name promises.
            if (sha256(tmp) != name.substringBefore('.')) return false
            tmp.copyTo(dst)
            return true
        } finally {
            tmp.delete()
        }
    }

    /** Deletes stored images no project uses any more, leaving ones younger than [graceMillis] (maybe being added). */
    suspend fun sweep(keep: Set<String>, graceMillis: Long = 24 * 60 * 60 * 1000L): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - graceMillis
        dir.listFiles().orEmpty().count { f -> f.name !in keep && f.lastModified() < cutoff && f.delete() }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_SIDE = 4096
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png")
        private val WORLD_EXT = setOf("jgw", "jpgw", "pgw", "pngw", "wld", "tfw")
    }
}
