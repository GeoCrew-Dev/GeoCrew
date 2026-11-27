package org.geocrew.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import java.io.File
import java.io.IOException

/**
 * The files another app hands GeoCrew: one opened with it (ACTION_VIEW) or
 * shared to it (ACTION_SEND, ACTION_SEND_MULTIPLE); none for any other intent.
 * GeoCrew reads them with its own rights, so only what another app may hand
 * it is taken: content from other apps' providers, and files in shared
 * storage, never GeoCrew's own (which would leak into the GeoCrew folder).
 */
fun receivedUris(context: Context, intent: Intent?): List<Uri> = when (intent?.action) {
    Intent.ACTION_VIEW -> listOfNotNull(intent.data)
    Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else -> emptyList()
}.filter { uri ->
    when (uri.scheme) {
        ContentResolver.SCHEME_CONTENT -> uri.authority?.startsWith(context.packageName) == false
        ContentResolver.SCHEME_FILE -> uri.path?.let { inShared(File(it)) } == true
        else -> false
    }
}

// Whether [file] is in shared storage, its links followed, and not in an app's own folder there.
@Suppress("DEPRECATION") // with all-files access shared storage is read by path (ADR 0002)
private fun inShared(file: File): Boolean = try {
    val path = file.canonicalPath
    val shared = Environment.getExternalStorageDirectory().canonicalPath
    path.startsWith("$shared/") && !path.startsWith("$shared/Android/")
} catch (e: IOException) {
    false
}

/** What came of receiving files: those copied in, and how many could not be read. */
data class Received(val files: List<File>, val failed: Int)

/**
 * Copies [uris] into [folder] under the names their apps give them, so GDAL
 * can read them by path (a content address it cannot). A batch whose names
 * are taken there is numbered as a whole, "roads (2).shp" with
 * "roads (2).dbf", so a Shapefile's parts still go together. Blocks: call it
 * off the main thread.
 */
fun receive(context: Context, uris: List<Uri>, folder: File): Received {
    folder.mkdirs()
    val names = uris.map { safeName(displayName(context, it)) }
    // The first numbering under which none of the batch's names is taken.
    val n = generateSequence(1) { it + 1 }.first { k -> names.none { File(folder, numbered(it, k)).exists() } }
    val files = ArrayList<File>()
    var failed = 0
    for ((uri, name) in uris.zip(names)) {
        val file = File(folder, numbered(name, n))
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream for $uri")
            input.use { from -> file.outputStream().use { from.copyTo(it, BUFFER) } }
            files += file
        } catch (e: Exception) {
            // Unreadable (gone, or not ours to read): nothing half written is left behind.
            file.delete()
            failed++
        }
    }
    return Received(files, failed)
}

/** [name] numbered [n] before its extension, "roads (2).shp"; as it is for 1. */
internal fun numbered(name: String, n: Int): String {
    if (n == 1) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return "${name.substring(0, dot)} ($n)${name.substring(dot)}"
}

/** [name] fit for a file in a folder: no path in it, nothing a file system refuses; "file" when nothing is left. */
internal fun safeName(name: String?): String {
    val last = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val clean = last.map { if (it in FORBIDDEN || it.code < 0x20) '_' else it }.joinToString("").trim().trimStart('.')
    return clean.ifEmpty { "file" }.take(MAX_NAME)
}

// The name the sending app gives the file, else the last part of its address.
private fun displayName(context: Context, uri: Uri): String? {
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (e: Exception) {
            // A provider that will not say: the address is used instead.
        }
    }
    return uri.lastPathSegment
}

private const val FORBIDDEN = "/\\:*?\"<>|"
private const val MAX_NAME = 120
private const val BUFFER = 64 * 1024
