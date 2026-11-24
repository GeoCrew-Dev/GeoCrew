package org.geocrew.core.data.repo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photos live in app storage named by the SHA-256 of their bytes, the same
 * id the server uses for attachments, so syncing never renames anything.
 */
@Singleton
class PhotoStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.filesDir, "photos").apply { mkdirs() }
    private val pending: File get() = File(context.cacheDir, "capture").apply { mkdirs() }

    /** A fresh file + content URI for the camera app to write into. */
    fun newCaptureTarget(): Pair<File, Uri> {
        val f = File.createTempFile("cap_", ".jpg", pending)
        return f to FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    /** Moves a captured file into the store and returns its hash, or null if empty. */
    suspend fun commit(captured: File): String? = withContext(Dispatchers.IO) {
        if (!captured.exists() || captured.length() == 0L) {
            captured.delete()
            return@withContext null
        }
        val sha = sha256(captured)
        val dst = file(sha)
        if (!dst.exists()) captured.copyTo(dst)
        captured.delete()
        sha
    }

    /** Copies an external image (gallery pick) into the store. */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("imp_", ".jpg", pending)
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        commit(tmp)
    }

    fun file(sha: String): File = File(dir, "$sha.jpg")

    fun exists(sha: String) = file(sha).exists()

    /** A content URI other apps (viewers, share targets) can read. */
    fun uri(sha: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file(sha))

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
}
