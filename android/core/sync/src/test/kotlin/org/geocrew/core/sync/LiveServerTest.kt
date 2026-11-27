package org.geocrew.core.sync

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The client against a real GeoCrew server, set by GEOCREW_TEST_SERVER (for example http://127.0.0.1:8080, with
// `go run ./cmd/geocrew-server` in server/); skipped without it.
class LiveServerTest {
    private val server: String? = System.getenv("GEOCREW_TEST_SERVER")?.takeIf { it.isNotBlank() }

    @Test
    fun aPhoneJoinsPushesPullsAndResolves() {
        val base = server ?: return
        val client = SyncClient(HttpTransport(base))
        assertTrue("sync" in client.info().capabilities)
        val leader = client.create("客户端联调", "组长", "JVM")
        val member = client.join(leader.inviteCode!!, "组员", "JVM 2")
        val layer = UUID.randomUUID().toString()
        val made = client.createLayer(leader.workspace, leader.token, LayerSpec(layer, "野外点", "Point", "EPSG:4490", listOf(FieldDef("f1", "点名", "string"))))
        assertEquals(1L, made.schemaVersion)

        val row = UUID.randomUUID().toString()
        val now = Instant.now()
        val point = buildJsonObject {
            put("type", "Point")
            put("coordinates", buildJsonArray { add(JsonPrimitive(114.3)); add(JsonPrimitive(30.6)) })
        }
        val first = PushChange(UUID.randomUUID().toString(), layer, row, 0, mapOf("f1" to FieldEdit(JsonPrimitive("D001"), now.minusSeconds(60)), "geom" to FieldEdit(point, now.minusSeconds(60))))
        val (results, _) = client.push(leader.workspace, leader.token, now, listOf(first))
        assertEquals(PushResult.APPLIED, results.single().status)
        assertEquals(PushResult.DUPLICATE, client.push(leader.workspace, leader.token, now, listOf(first)).first.single().status, "a retry")

        // The member edited the same field earlier, offline: it loses into a conflict, which they put back.
        val late = PushChange(UUID.randomUUID().toString(), layer, row, 0, mapOf("f1" to FieldEdit(JsonPrimitive("D009"), now.minusSeconds(3600))))
        assertEquals(1, client.push(member.workspace, member.token, now, listOf(late)).first.single().conflicts.size)
        val page = client.pull(member.workspace, member.token, 0)
        val pulled = page.rows.single { it.id == row }
        assertEquals(JsonPrimitive("D001"), pulled.fields.getValue("f1").value)
        assertTrue(pulled.conflicted)
        val conflict = page.conflicts.single { it.row == row }
        assertTrue(client.resolve(member.workspace, member.token, conflict.id, SyncClient.LOST).resolved)
        val after = client.pull(member.workspace, member.token, page.seq).rows.single { it.id == row }
        assertEquals(JsonPrimitive("D009"), after.fields.getValue("f1").value)
    }
}
