package org.geocrew.core.sync

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

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
