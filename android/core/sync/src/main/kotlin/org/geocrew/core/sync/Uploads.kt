package org.geocrew.core.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What became of a file fetched (protocol v1 9.2). */
enum class Received {
    /** The file is here, whole and the one its hash names. */
    DONE,

    /** It is not uploaded yet: a thumbnail stands in for it till then (protocol v1 9.3). */
    NOT_UPLOADED,

    /** What came is not the file its hash names; nothing is kept. */
    BROKEN,
}

/**
 * Fetches file [sha256] of [workspace] into [target] (protocol v1 9.2), by way
 * of a part file next to it, so a download cut short goes on from where it
 * stopped the next time; the file takes its place only once its hash is
 * right. A file already there is not fetched again. Network failures and
 * other refusals are thrown.
 */
fun SyncClient.receiveFile(workspace: String, token: String, sha256: String, target: File): Received {
    if (target.isFile) return Received.DONE
    target.parentFile?.mkdirs()
    val part = File(target.path + ".part")
    try {
        fetchFile(workspace, token, sha256, if (part.isFile) part.length() else 0) { whole -> FileOutputStream(part, !whole) }
    } catch (e: ServerError) {
        if (e.code == "not_uploaded") return Received.NOT_UPLOADED
        throw e
    }
    val digest = MessageDigest.getInstance("SHA-256")
    part.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    if (digest.digest().joinToString("") { "%02x".format(it) } != sha256) {
        part.delete()
        return Received.BROKEN
    }
    if (!part.renameTo(target) && !target.isFile) throw IOException("cannot keep ${target.name}")
    return Received.DONE
}

/** What became of a file sent (protocol v1 9.2). */
enum class Sent {
    /** The workspace has the file. */
    DONE,

    /** Time ran out first; the next try goes on from where this one stopped, which the server keeps. */
    LATER,

    /** The file is not the one its hash names: it can never go. */
    BROKEN,
}

/**
 * Sends [file], whose SHA-256 is [sha256] and type [type], to [workspace]
 * by chunks (protocol v1 9.2), from wherever an earlier try stopped, until it
 * is there or the clock ([now]) passes [until]. A chunk sent where the upload
 * does not stand goes again from where it does; an upload that ended meanwhile
 * (its last answer lost, or dropped after a week) is begun again, which finds
 * a file already there. Other refusals and network failures are thrown.
 */
fun SyncClient.sendFile(
    workspace: String,
    token: String,
    file: File,
    sha256: String,
    type: String,
    until: Long,
    now: () -> Long = System::currentTimeMillis,
): Sent {
    val size = file.length()
    var start = startUpload(workspace, token, sha256, size, type)
    var begun = 1
    RandomAccessFile(file, "r").use { data ->
        while (true) {
            if (start.exists) return Sent.DONE
            if (start.chunkSize <= 0) throw IOException("the server gave no chunk size")
            var offset = start.offset
            try {
                // At least one chunk, an empty one for an empty file.
                do {
                    if (now() > until) return Sent.LATER
                    val bytes = ByteArray(minOf(start.chunkSize, size - offset).toInt())
                    data.seek(offset)
                    data.readFully(bytes)
                    offset = putChunk(workspace, token, start.upload, offset, bytes) ?: return Sent.DONE
                } while (true)
            } catch (e: ServerError) {
                when {
                    e.code == "offset_mismatch" ->
                        start = start.copy(offset = (e.body?.get("offset") as? JsonPrimitive)?.longOrNull ?: throw e)
                    e.code == "hash_mismatch" -> return Sent.BROKEN
                    e.status == 404 && begun++ < 3 -> start = startUpload(workspace, token, sha256, size, type)
                    else -> throw e
                }
            }
        }
    }
}
