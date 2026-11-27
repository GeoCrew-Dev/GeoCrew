package org.geocrew.storage

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Size
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * A project's attachment files (ADR 0004): each in the attachment folder named
 * by its SHA-256, with the extension its type usually has so that other apps
 * open it, and the same file kept once. Photos and videos get a thumbnail, a
 * JPEG whose long side is at most 640 pixels (protocol v1 9.3), kept the same way.
 */
internal object AttachmentFiles {
    /** A stored file: its hash, its thumbnail's if it has one, its type and its size. */
    data class Stored(val file: String, val thumb: String?, val type: String, val size: Long)

    private const val THUMB_SIDE = 640

    // The extensions of the types attachments mostly have; a file of another type keeps none.
    private val EXTENSIONS = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/heic" to "heic", "image/heif" to "heif", "image/gif" to "gif",
        "video/mp4" to "mp4", "video/3gpp" to "3gp", "video/webm" to "webm",
        "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/mpeg" to "mp3", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/amr" to "amr",
        "application/pdf" to "pdf", "text/plain" to "txt",
    )

    /** The type a file called [name] most likely has, by its extension. */
    fun typeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension == "jpeg") return "image/jpeg"
        return EXTENSIONS.entries.firstOrNull { it.value == extension }?.key ?: "application/octet-stream"
    }

    /** Where the file with hash [hash] and type [type] is kept in [folder]. */
    fun fileOf(folder: File, hash: String, type: String): File = File(folder, hash + (EXTENSIONS[type]?.let { ".$it" } ?: ""))

    /** Copies [source] into [folder] under its hash, with a thumbnail for a photo or a video. */
    fun store(folder: File, source: File, type: String): Stored {
        val hash = source.inputStream().use { keep(folder, it, type) }
        val file = fileOf(folder, hash, type)
        return Stored(hash, thumbnail(folder, file, type), type, file.length())
    }

    // Writes [input] to the folder under its hash, by way of another name, so a copy cut short never looks whole.
    private fun keep(folder: File, input: InputStream, type: String): String {
        check(folder.isDirectory || folder.mkdirs()) { "cannot make the folder $folder" }
        val part = File(folder, ".part-" + UUID.randomUUID())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(part).use { out ->
                DigestOutputStream(out, digest).let { input.copyTo(it); it.flush() }
                out.fd.sync() // a photo is kept for good once it is here
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = fileOf(folder, hash, type)
            if (!target.exists()) check(part.renameTo(target)) { "cannot keep the file as ${target.name}" }
            return hash
        } finally {
            part.delete()
        }
    }

    // The thumbnail's hash; null for a file that is no photo or video, or that cannot be read as one.
    private fun thumbnail(folder: File, file: File, type: String): String? {
        val bounds = Size(THUMB_SIDE, THUMB_SIDE)
        val picture = runCatching {
            when {
                type.startsWith("image/") -> ThumbnailUtils.createImageThumbnail(file, bounds, null)
                type.startsWith("video/") -> ThumbnailUtils.createVideoThumbnail(file, bounds, null)
                else -> null
            }
        }.getOrNull() ?: return null
        val longest = maxOf(picture.width, picture.height)
        val fitted = if (longest <= THUMB_SIDE) {
            picture
        } else {
            val scale = THUMB_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(picture, maxOf(1, (picture.width * scale).toInt()), maxOf(1, (picture.height * scale).toInt()), true)
        }
        val bytes = java.io.ByteArrayOutputStream().also { fitted.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        return ByteArrayInputStream(bytes).use { keep(folder, it, "image/jpeg") }
    }
}
