package org.geocrew.core.sync

import java.io.File
import java.io.OutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.geocrew.core.sync.UploadsTest.Companion.hexOf

// Fetching a file (protocol v1 9.2): whole, or the rest of one cut short; checked by its hash before it is kept.
class DownloadsTest {
    private class Server(val files: Map<String, ByteArray>) : Transport {
        var takesRanges = true
        val asked = mutableListOf<Long>()

        override fun send(method: String, path: String, token: String?, body: String?): Reply = error("not here")

        override fun fetch(path: String, token: String?, from: Long, open: (whole: Boolean) -> OutputStream): Reply {
            asked += from
            val sha = path.substringAfterLast("/")
            val bytes = files[sha] ?: return Reply(404, """{"error": "not_uploaded", "message": "not up yet"}""")
            if (from >= bytes.size && takesRanges) return Reply(416, "")
            val ranged = from > 0 && takesRanges
            open(!ranged).use { it.write(if (ranged) bytes.copyOfRange(from.toInt(), bytes.size) else bytes) }
            return Reply(if (ranged) 206 else 200, "")
        }
    }

    private val dir = createTempDirectory("downloads").toFile()

    @Test
    fun aFileComesWholeOrGoesOnFromWhereItStopped() {
        val bytes = "0123456789".toByteArray()
        val sha = hexOf(bytes)
        val server = Server(mapOf(sha to bytes))
        val client = SyncClient(server)
        val target = File(dir, "$sha.jpg")
        assertEquals(Received.DONE, client.receiveFile("w1", "t1", sha, target))
        assertContentEquals(bytes, target.readBytes())
        assertEquals(Received.DONE, client.receiveFile("w1", "t1", sha, target), "here already")
        assertEquals(listOf(0L), server.asked, "not fetched again")

        // Cut short after four bytes: the rest only.
        val again = File(dir, "again.jpg")
        File(again.path + ".part").writeBytes(bytes.copyOf(4))
        assertEquals(Received.DONE, client.receiveFile("w1", "t1", sha, again))
        assertContentEquals(bytes, again.readBytes())
        assertEquals(4L, server.asked.last())
        assertFalse(File(again.path + ".part").exists())

        // All there but not yet in place: nothing past it, then kept.
        val whole = File(dir, "whole.jpg")
        File(whole.path + ".part").writeBytes(bytes)
        assertEquals(Received.DONE, client.receiveFile("w1", "t1", sha, whole))
        assertContentEquals(bytes, whole.readBytes())

        // A server that takes no ranges sends it whole: what was there is written over.
        server.takesRanges = false
        val over = File(dir, "over.jpg")
        File(over.path + ".part").writeBytes("xxxx".toByteArray())
        assertEquals(Received.DONE, client.receiveFile("w1", "t1", sha, over))
        assertContentEquals(bytes, over.readBytes())
    }

    @Test
    fun aFileNotUpYetOrNotTheOneNamedIsNotKept() {
        val bytes = "0123456789".toByteArray()
        val named = hexOf("something else".toByteArray())
        val client = SyncClient(Server(mapOf(named to bytes)))
        val target = File(dir, "broken.jpg")
        assertEquals(Received.BROKEN, client.receiveFile("w1", "t1", named, target))
        assertFalse(target.exists() || File(target.path + ".part").exists(), "nothing kept")
        assertEquals(Received.NOT_UPLOADED, SyncClient(Server(emptyMap())).receiveFile("w1", "t1", hexOf(bytes), File(dir, "later.jpg")))
    }
}
