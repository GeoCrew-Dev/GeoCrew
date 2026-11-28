package org.geocrew.core.sync

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The protocol v1 calls a phone makes (protocol-v1.md 3.3, 3.4, 5.6, 8.1-8.3): what is sent, and what the answers become.
class ClientTest {
    private class Fake(private val replies: List<Reply>) : Transport {
        val sent = mutableListOf<Triple<String, String, String?>>()
        val tokens = mutableListOf<String?>()

        override fun send(method: String, path: String, token: String?, body: String?): Reply {
            sent += Triple(method, path, body)
            tokens += token
            return replies[sent.size - 1]
        }
    }

    @Test
    fun infoAndJoining() {
        val fake = Fake(
            listOf(
                Reply(200, """{"protocol": 1, "server": "geocrew-server", "version": "0.3.0", "capabilities": ["sync", "create"], "limits": {"max_push_batch": 500, "max_pull_batch": 1000, "max_file_size": 1073741824}}"""),
                Reply(200, """{"workspace": {"id": "w1", "name": "秋季填图", "leader": "m1", "created_at": "2026-11-28T00:00:00Z", "access_version": 1}, "member": {"id": "m2", "nickname": "李四", "roles": [], "joined_at": "2026-11-28T00:00:00Z"}, "token": "t2"}"""),
                Reply(404, """{"error": "bad_invite", "message": "invalid invite code"}"""),
            ),
        )
        val client = SyncClient(fake)
        val info = client.info()
        assertEquals(1, info.protocol)
        assertEquals(setOf("sync", "create"), info.capabilities)
        assertEquals(500, info.maxPushBatch)

        val joined = client.join("k7qx2mpa", "李四", "Mate 60")
        assertEquals(Joined("w1", "秋季填图", "m2", "m1", null, "t2"), joined)
        val body = Json.parseToJsonElement(fake.sent[1].third!!).jsonObject
        assertEquals("k7qx2mpa", body.getValue("invite_code").jsonPrimitive.content)
        assertEquals("POST" to "/api/v1/join", fake.sent[1].first to fake.sent[1].second)

        val refused = assertFailsWith<ServerError> { client.join("wrong", "x", "y") }
        assertEquals(404 to "bad_invite", refused.status to refused.code)
    }

    @Test
    fun fieldsAddedAndWhatARefusalBrings() {
        val layer = """{"id": "L1", "geometry_type": "Point", "crs": "EPSG:4490", "schema_version": 3, "seq": 9,
            "fields": [{"id": "f1", "name": "点名", "type": "string"}, {"id": "f2", "name": "岩性", "type": "string"}]}"""
        val fake = Fake(
            listOf(
                Reply(200, layer),
                Reply(409, """{"error": "name_taken", "message": "", "field": {"id": "f2", "name": "岩性", "type": "string", "options": ["花岗岩"]}}"""),
                Reply(409, """{"error": "stale", "message": "", "layer": $layer}"""),
            ),
        )
        val client = SyncClient(fake)
        val made = client.addFields("w1", "t1", "L1", 1, listOf(FieldDef("f2", "岩性", "string")))
        assertEquals(3L to listOf("f1", "f2"), made.schemaVersion to made.fields.map { it.id })
        val sent = Json.parseToJsonElement(fake.sent[0].third!!).jsonObject
        assertEquals("POST" to "/api/v1/workspaces/w1/layers/L1/schema", fake.sent[0].first to fake.sent[0].second)
        assertEquals(1L, sent.getValue("base_version").jsonPrimitive.content.toLong())
        assertEquals("add_field", sent.getValue("ops").jsonArray.single().jsonObject.getValue("op").jsonPrimitive.content)
        val taken = assertFailsWith<ServerError> { client.addFields("w1", "t1", "L1", 3, listOf(FieldDef("f9", "岩性", "integer"))) }
        assertEquals(FieldDef("f2", "岩性", "string", Json.parseToJsonElement("""["花岗岩"]""").jsonArray), client.fieldIn(taken))
        val stale = assertFailsWith<ServerError> { client.addFields("w1", "t1", "L1", 3, emptyList()) }
        assertEquals(3L, client.layerIn(stale)!!.schemaVersion)
        assertEquals(null, client.layerIn(taken))
    }

    @Test
    fun theMembersAreListedByIdAndNickname() {
        val fake = Fake(
            listOf(
                Reply(200, """[{"id": "m1", "nickname": "张三", "roles": ["leader"], "joined_at": "2026-11-28T00:00:00Z"}, {"id": "m2", "nickname": "李四", "roles": [], "joined_at": "2026-11-28T00:00:00Z"}]"""),
                Reply(200, """{"members": []}"""),
            ),
        )
        val client = SyncClient(fake)
        assertEquals(listOf(Member("m1", "张三"), Member("m2", "李四")), client.members("w 1", "t1"))
        assertEquals("GET" to "/api/v1/workspaces/w%201/members", fake.sent[0].first to fake.sent[0].second)
        assertEquals("t1", fake.tokens[0])
        assertFailsWith<java.io.IOException>("an answer of another shape") { client.members("w1", "t1") }
    }

    @Test
    fun pushingAndPulling() {
        val fake = Fake(
            listOf(
                Reply(
                    200,
                    """{"results": [
                        {"id": "c1", "status": "applied", "seq": 18, "conflicts": [{"kind": "value", "field": "f1",
                          "kept": {"value": "D001", "by": "m1", "at": "2026-11-28T11:50:00Z", "seq": 17},
                          "lost": {"value": "D009", "by": "m2", "at": "2026-11-28T11:00:00Z", "seq": 18}}]},
                        {"id": "c2", "status": "rejected", "error": "forbidden", "message": "edit_rows is required"}], "seq": 18}""",
                ),
                Reply(
                    200,
                    """{"layers": [{"id": "L1", "geometry_type": "Point", "crs": "EPSG:4490", "fields": [{"id": "f1", "name": "点名", "type": "string"}],
                          "schema_version": 2, "name": {"value": "野外点", "by": "m1", "at": "2026-11-28T10:00:00Z", "seq": 3}, "deleted": null, "seq": 3}],
                        "rows": [{"layer": "L1", "id": "r1", "conflicted": true, "deleted": {"value": false, "by": "m1", "at": "2026-11-28T12:00:00Z", "seq": 18},
                          "fields": {"f1": {"value": "D001", "by": "m1", "at": "2026-11-28T19:50:00.5+08:00", "seq": 17}, "geom": {"value": {"type": "Point", "coordinates": [114.3, 30.6]}, "by": "m1", "at": "2026-11-28T11:50:00Z", "seq": 17}}}],
                        "conflicts": [{"id": "k1", "layer": "L1", "row": "r1", "seq": 18, "resolved": false, "kind": "value", "field": "f1"}],
                        "seq": 25, "more": true}""",
                ),
            ),
        )
        val client = SyncClient(fake)
        val now = Instant.parse("2026-11-28T12:00:00Z")
        val change = PushChange("c1", "L1", "r1", 5, mapOf("f1" to FieldEdit(JsonPrimitive("D009"), Instant.parse("2026-11-28T11:00:00Z"))))
        val deletion = PushChange("c2", "L1", "r2", 7, emptyMap(), delete = FieldEdit(JsonPrimitive(true), now))
        val (results, seq) = client.push("w 1", "t2", now, listOf(change, deletion))
        assertEquals("/api/v1/workspaces/w%201/rows", fake.sent[0].second, "a workspace id goes as one path segment")
        assertEquals("t2", fake.tokens[0])
        val sent = Json.parseToJsonElement(fake.sent[0].third!!).jsonObject
        assertEquals("2026-11-28T12:00:00Z", sent.getValue("client_now").jsonPrimitive.content)
        val changes = sent.getValue("changes").jsonArray
        assertEquals("2026-11-28T11:00:00Z", changes[0].jsonObject.getValue("set").jsonObject.getValue("f1").jsonObject.getValue("edited_at").jsonPrimitive.content)
        assertTrue("set" !in changes[1].jsonObject && changes[1].jsonObject.getValue("delete").jsonObject.getValue("value").jsonPrimitive.content == "true")
        assertEquals(18L, seq)
        assertEquals(PushResult.APPLIED, results[0].status)
        assertEquals("D009", (results[0].conflicts.single().lost!!.value as JsonPrimitive).content)
        assertEquals(PushResult.REJECTED to "forbidden", results[1].status to results[1].error)

        val page = client.pull("w1", "t2", 17, limit = 500)
        assertEquals("/api/v1/workspaces/w1/changes?since=17&limit=500", fake.sent[1].second)
        assertEquals(25L to true, page.seq to page.more)
        val layer = page.layers.single()
        assertEquals(listOf(FieldDef("f1", "点名", "string")), layer.fields)
        assertEquals("野外点" to 2L, layer.name to layer.schemaVersion)
        val row = page.rows.single()
        assertTrue(row.conflicted && !row.deleted, "restored, not deleted")
        assertEquals(17L, row.fields.getValue("geom").seq)
        assertEquals(Instant.parse("2026-11-28T11:50:00.5Z"), row.fields.getValue("f1").at, "an offset is read as well as Z")
        assertEquals("k1", page.conflicts.single().id)
    }

    @Test
    fun theHttpTransportSpeaksJsonWithTheToken() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seen = ""
        server.createContext("/api/v1/workspaces/w1/rows") { exchange ->
            seen = exchange.requestHeaders.getFirst("Authorization") + " " + exchange.requestHeaders.getFirst("Content-Type") + " " +
                exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val answer = """{"results": [], "seq": 3}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.createContext("/api/v1/workspaces/w1/changes") { exchange ->
            val answer = """{"error": "unauthorized", "message": "missing, invalid or revoked token"}""".toByteArray()
            exchange.sendResponseHeaders(401, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.start()
        try {
            val client = SyncClient(HttpTransport("http://127.0.0.1:${server.address.port}/"))
            val (results, seq) = client.push("w1", "secret", Instant.parse("2026-11-28T12:00:00Z"), emptyList())
            assertTrue(results.isEmpty() && seq == 3L)
            assertTrue(seen.startsWith("Bearer secret application/json; charset=utf-8 {\"client_now\""), seen)
            val refused = assertFailsWith<ServerError> { client.pull("w1", "stale", 0) }
            assertEquals(401 to "unauthorized", refused.status to refused.code)
        } finally {
            server.stop(0)
        }
    }
}
