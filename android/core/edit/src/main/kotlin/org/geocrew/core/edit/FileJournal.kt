package org.geocrew.core.edit

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.json.Json

/**
 * A journal in a file, one JSON line per entry (ADR 0005). Each append is
 * synced to the disk before it returns, and a rewrite goes to a new file that
 * then replaces the old one, so a crash at any point leaves a whole journal. A
 * line cut short by a crash is ignored when reading, and cut off before the
 * next append.
 */
class FileJournal(private val file: File) : Journal {
    private var tailChecked = false

    override fun append(entry: JournalEntry) {
        if (!tailChecked) {
            cutShortLine()
            tailChecked = true
        }
        FileOutputStream(file, true).use { out ->
            out.write(line(entry))
            out.fd.sync()
        }
    }

    override fun entries(): List<JournalEntry> {
        if (!file.exists()) return emptyList()
        val lines = file.readText().split('\n')
        // Every line ends with a newline, so the last piece is empty or the one a crash cut short.
        return lines.dropLast(1).filter { it.isNotEmpty() }.map { Json.decodeFromString(JournalEntry.serializer(), it) }
    }

    override fun rewrite(entries: List<JournalEntry>) {
        if (entries.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("cannot delete $file")
            return
        }
        val next = File(file.path + ".new")
        FileOutputStream(next).use { out ->
            for (entry in entries) out.write(line(entry))
            out.fd.sync()
        }
        Files.move(next.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun cutShortLine() {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { f ->
            var end = f.length()
            while (end > 0) {
                f.seek(end - 1)
                if (f.read() == NEWLINE) break
                end--
            }
            if (end < f.length()) {
                f.setLength(end)
                f.fd.sync()
            }
        }
    }

    private fun line(entry: JournalEntry): ByteArray = (Json.encodeToString(JournalEntry.serializer(), entry) + "\n").toByteArray()

    private companion object {
        const val NEWLINE = 10
    }
}
