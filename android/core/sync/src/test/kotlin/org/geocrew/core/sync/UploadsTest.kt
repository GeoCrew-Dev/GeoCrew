package org.geocrew.core.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// Sending a file by chunks (protocol v1 9.2), against a server that keeps uploads as the protocol says.
class UploadsTest {
    private class Upload(val sha: String, val size: Long, val got: ByteArrayOutputStream = ByteArrayOutputStream())

    private class Server(private val chunk: Int) : Transport {
        val files = mutableMapOf<String, ByteArray>()
        val uploads = mutableMapOf<String, Upload>()
        var puts = 0

        // The next chunk gets there but its answer is lost on the way back.
        var loseNextAnswer = false

        // The next chunk is one the server has already had (an earlier try's): it answers with where it stands.
        var hadNextAlready = false

        override fun send(method: String, path: String, token: String?, body: String?): Reply {
            check(method == "POST" && path.endsWith("/uploads"))
            val o = Json.parseToJsonElement(body!!).jsonObject
            val sha = o.getValue("sha256").jsonPrimitive.content
            if (sha in files) return Reply(200, """{"exists": true}""")
            val id = uploads.entries.firstOrNull { it.value.sha == sha }?.key
                ?: "u_${uploads.size + files.size + 1}".also { uploads[it] = Upload(sha, o.getValue("size").jsonPrimitive.long) }
            return Reply(200, """{"upload": "$id", "offset": ${uploads.getValue(id).got.size()}, "chunk_size": $chunk}""")
        }

        override fun sendBytes(method: String, path: String, token: String?, body: ByteArray): Reply {
            puts++
            val id = path.substringAfter("/uploads/").substringBefore("?")
            val u = uploads[id] ?: return Reply(404, """{"error": "not_found", "message": "no such upload"}""")
            val offset = path.substringAfter("offset=").toLong()
            if (offset != u.got.size().toLong()) return Reply(409, """{"error": "offset_mismatch", "message": "", "offset": ${u.got.size()}}""")
            if (body.size > chunk || offset + body.size > u.size) return Reply(400, """{"error": "invalid", "message": "too long"}""")
            u.got.write(body)
            if (hadNextAlready) {
                hadNextAlready = false
                return Reply(409, """{"error": "offset_mismatch", "message": "", "offset": ${u.got.size()}}""")
            }
            val answer = if (u.got.size() < u.size) {
                Reply(200, """{"offset": ${u.got.size()}}""")
            } else {
                uploads.remove(id)
                val whole = u.got.toByteArray()
                if (hexOf(whole) != u.sha) {
                    Reply(400, """{"error": "hash_mismatch", "message": ""}""")
                } else {
                    files[u.sha] = whole
                    Reply(201, """{"sha256": "${u.sha}"}""")
                }
            }
            if (loseNextAnswer) {
                loseNextAnswer = false
                throw IOException("the answer was lost")
            }
            return answer
        }
    }

    private fun file(bytes: ByteArray): File = createTempFile("upload").toFile().apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun aFileGoesByChunksAndIsThere() {
        val server = Server(chunk = 4)
        val bytes = "0123456789".toByteArray()
        val client = SyncClient(server)
        assertEquals(Sent.DONE, client.sendFile("w1", "t1", file(bytes), hexOf(bytes), "image/jpeg", Long.MAX_VALUE))
        assertContentEquals(bytes, server.files.getValue(hexOf(bytes)))
        assertEquals(3, server.puts, "4 + 4 + 2 bytes")
        assertEquals(Sent.DONE, client.sendFile("w1", "t1", file(bytes), hexOf(bytes), "image/jpeg", Long.MAX_VALUE), "there already")
        assertEquals(3, server.puts, "nothing sent again")

        val empty = ByteArray(0)
        assertEquals(Sent.DONE, client.sendFile("w1", "t1", file(empty), hexOf(empty), "text/plain", Long.MAX_VALUE), "an empty file, one empty chunk")
        assertContentEquals(empty, server.files.getValue(hexOf(empty)))
    }

    @Test
    fun timeRunningOutStopsAndTheNextTryGoesOn() {
        val server = Server(chunk = 4)
        val bytes = "0123456789abcdef".toByteArray()
        val client = SyncClient(server)
        var clock = 0L
        assertEquals(Sent.LATER, client.sendFile("w1", "t1", file(bytes), hexOf(bytes), "image/jpeg", until = 2, now = { clock++ }))
        assertEquals(3, server.puts, "the chunks sent while there was time")
        assertEquals(Sent.DONE, client.sendFile("w1", "t1", file(bytes), hexOf(bytes), "image/jpeg", Long.MAX_VALUE))
        assertEquals(4, server.puts, "only what was left")
        assertContentEquals(bytes, server.files.getValue(hexOf(bytes)))
    }

    @Test
    fun aLostAnswerAndAChunkTheServerHadAreGotOver() {
        val server = Server(chunk = 4)
        val client = SyncClient(server)
        // The last chunk's answer lost: the next try finds the file there.
        val bytes = "0123456789".toByteArray()
        val lastAnswerLost = object {
            var n = 0
        }
        val lossy = object : Transport by server {
            override fun sendBytes(method: String, path: String, token: String?, body: ByteArray): Reply {
                if (++lastAnswerLost.n == 3) server.loseNextAnswer = true
                return server.sendBytes(method, path, token, body)
            }
        }
        assertFailsWith<IOException> { SyncClient(lossy).sendFile("w1", "t1", file(bytes), hexOf(bytes), "image/jpeg", Long.MAX_VALUE) }
        assertEquals(Sent.DONE, client.sendFile("w1", "t1", file(bytes), hexOf(bytes), "image/jpeg", Long.MAX_VALUE), "the server has it after all")

        // A chunk the server had already: it goes on from where the server stands.
        val other = "abcdefghij".toByteArray()
        server.hadNextAlready = true
        assertEquals(Sent.DONE, client.sendFile("w1", "t1", file(other), hexOf(other), "image/jpeg", Long.MAX_VALUE))
        assertContentEquals(other, server.files.getValue(hexOf(other)))
    }

    @Test
    fun bytesThatAreNotTheFileNamedAreBroken() {
        val server = Server(chunk = 4)
        val bytes = "0123456789".toByteArray()
        assertEquals(Sent.BROKEN, SyncClient(server).sendFile("w1", "t1", file(bytes), hexOf("something else".toByteArray()), "image/jpeg", Long.MAX_VALUE))
    }

    @Test
    fun theUploadCallsSayWhatTheyShould() {
        val sent = mutableListOf<String>()
        val client = SyncClient(
            object : Transport {
                override fun send(method: String, path: String, token: String?, body: String?): Reply {
                    sent += "$method $path $body"
                    return Reply(200, """{"upload": "u_7", "offset": 3, "chunk_size": 1048576}""")
                }

                override fun sendBytes(method: String, path: String, token: String?, body: ByteArray): Reply {
                    sent += "$method $path ${body.size}"
                    return if (body.size == 2) Reply(201, """{"sha256": "x"}""") else Reply(200, """{"offset": 5}""")
                }
            },
        )
        assertEquals(UploadStart(false, "u_7", 3, 1048576), client.startUpload("w 1", "t1", "ab", 9, "image/jpeg"))
        assertEquals(9L, Json.parseToJsonElement(sent[0].substringAfter("uploads ")).jsonObject.getValue("size").jsonPrimitive.long)
        assertEquals(5L, client.putChunk("w 1", "t1", "u_7", 3, ByteArray(1)))
        assertEquals(null, client.putChunk("w 1", "t1", "u_7", 5, ByteArray(2)), "whole and stored")
        assertEquals("PUT /api/v1/workspaces/w%201/uploads/u_7?offset=5 2", sent.last())
    }

    companion object {
        fun hexOf(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
