package org.geocrew.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant

class ParseTest {
    @Test
    fun parsesRealtimeMessages() {
        val hello = RealtimeClient.parse("""{"type":"hello","member_id":"m","online":["a"],"positions":[{"type":"position","member_id":"a","nickname":"甲","lon":1,"lat":2,"at":"2026-11-24T08:00:00Z"}]}""")
        assertTrue(hello is RealtimeEvent.Hello && hello.positions.single().nickname == "甲")
        assertEquals(RealtimeEvent.FeatureChanged(7), RealtimeClient.parse("""{"type":"feature_changed","seq":7}"""))
        assertEquals(RealtimeEvent.MembersChanged, RealtimeClient.parse("""{"type":"members_changed"}"""))
        assertEquals(null, RealtimeClient.parse("""{"type":"from_the_future"}"""))
        assertEquals(null, RealtimeClient.parse("not json"))
    }

    @Test
    fun normalizesServerUrls() {
        assertEquals("https://example.org/", GeoCrewApi.normalize("example.org").toString())
        assertEquals("http://10.0.0.2:8080/geo/", GeoCrewApi.normalize("http://10.0.0.2:8080/geo").toString())
    }
}

/** In-memory stand-in for the Room-backed store, same rules as FeatureRepository. */
class MemoryStore : SyncStore, AttachmentStore {
    val features = linkedMapOf<String, GeoFeature>()
    var seq = 0L
    val dir: File = Files.createTempDirectory("att").toFile()

    override suspend fun dirty(projectId: String, limit: Int) = features.values.filter { it.sync.dirty }.take(limit)
    override suspend fun markSynced(f: GeoFeature, seq: Long, by: String?) {
        val cur = features[f.id] ?: return
        if (cur.sync.version == f.sync.version) features[f.id] = cur.copy(sync = cur.sync.copy(dirty = false, seq = seq, updatedBy = by))
    }
    override suspend fun mergeRemote(f: GeoFeature): Boolean {
        val local = features[f.id]
        if (local != null && local.sync.dirty && local.sync.isNewerThan(f.sync)) return false
        features[f.id] = f.copy(sync = f.sync.copy(dirty = false))
        return true
    }
    override suspend fun lastSeq(projectId: String) = seq
    override suspend fun setLastSeq(projectId: String, seq: Long) { this.seq = seq }
    override fun file(sha256: String) = File(dir, sha256)
    override fun exists(sha256: String) = file(sha256).exists()
}

/**
 * End-to-end against a real server. Run with GEOCREW_TEST_SERVER=http://127.0.0.1:8080;
 * skipped otherwise (e.g. in Android CI).
 */
class ServerIntegrationTest {
    private val server = System.getenv("GEOCREW_TEST_SERVER")?.takeIf { it.isNotBlank() }
    private val http = OkHttpClient()

    private fun feature(id: String, store: MemoryStore, title: String, photo: String? = null): GeoFeature {
        val now = Instant.now()
        return GeoFeature(
            id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(116.4, 39.9, 50.0)),
            buildProperties {
                this[Props.TITLE] = title
                this[Props.DIP] = 30
                this[Props.DIP_DIRECTION] = 120
                if (photo != null) strings(Props.PHOTOS, listOf(photo))
            },
            now, SyncInfo(updatedAt = now),
        ).also { store.features[id] = it }
    }

    @Test
    fun twoMembersSyncFeaturesPhotosAndPositions() = runBlocking {
        assumeTrue("GEOCREW_TEST_SERVER not set", server != null)
        val anon = GeoCrewApi(server!!, http)
        assertEquals("0", anon.info().protocol)

        val leaderJoin = anon.createWorkspace("联调组", "组长")
        val leader = anon.withToken(leaderJoin.token)
        val memberJoin = anon.join(leaderJoin.inviteCode!!.lowercase(), "组员")
        val member = anon.withToken(memberJoin.token)
        val ws = leaderJoin.workspaceId
        assertEquals(2, leader.members(ws).size)

        // Leader records a point with a photo.
        val a = MemoryStore()
        val bytes = "jpeg".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        a.file(sha).writeBytes(bytes)
        feature("f1", a, "D001", sha)
        val r1 = SyncEngine(leader, a, a, ws, "p", leaderJoin.memberId).run()
        assertEquals(1, r1.pushed)
        assertEquals(1, r1.uploaded)
        assertTrue(a.features.values.none { it.sync.dirty })

        // Member pulls it, including the photo.
        val b = MemoryStore()
        val r2 = SyncEngine(member, b, b, ws, "p", memberJoin.memberId).run()
        assertEquals(1, r2.pulled)
        assertEquals(1, r2.downloaded)
        assertEquals("D001", b.features["f1"]!!.title)
        assertTrue(b.exists(sha))

        // Both edit; the later edit with the higher version wins on both sides.
        val now = Instant.now()
        b.features["f1"] = b.features["f1"]!!.edited(now) { this[Props.TITLE] = "D001-组员" }
        a.features["f1"] = a.features["f1"]!!.edited(now.minusSeconds(5)) { this[Props.TITLE] = "D001-组长" }
        SyncEngine(member, b, b, ws, "p", memberJoin.memberId).run()
        val r3 = SyncEngine(leader, a, a, ws, "p", leaderJoin.memberId).run()
        assertEquals(1, r3.overwritten)
        assertEquals("D001-组员", a.features["f1"]!!.title)

        // Realtime: the member sees the leader's position.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val rtA = RealtimeClient(leader, ws, http, scope)
            val rtB = RealtimeClient(member, ws, http, scope)
            rtB.start()
            withTimeout(5000) { rtB.state.first { it == RealtimeState.Connected } }
            rtA.start()
            withTimeout(5000) { rtA.state.first { it == RealtimeState.Connected } }
            val pos = scope.async { withTimeout(5000) { rtB.events.first { it is RealtimeEvent.Position } } }
            while (!pos.isCompleted) {
                rtA.sendPosition(116.41, 39.91, accuracy = 5.0)
                kotlinx.coroutines.delay(300)
            }
            val p = (pos.await() as RealtimeEvent.Position).position
            assertEquals(leaderJoin.memberId, p.memberId)
            assertEquals("组长", p.nickname)

            // Revoking the member closes its channel for good.
            leader.revoke(ws, memberJoin.memberId)
            withTimeout(10000) { rtB.state.first { it == RealtimeState.Unauthorized } }
        } finally {
            scope.cancel()
        }
        Unit
    }
}
