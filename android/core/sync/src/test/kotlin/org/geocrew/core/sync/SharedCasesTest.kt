package org.geocrew.core.sync

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

// The merge and permission rules against the cases protocol/testdata shares with the Go server (ADR 0008, 0009).
class SharedCasesTest {
    // protocol/testdata, found from wherever the tests run in the repository.
    private val testdata: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "protocol/testdata") }.first { it.isDirectory }

    private fun read(name: String): JsonObject = Json.parseToJsonElement(File(testdata, name).readText()).jsonObject

    private fun field(o: JsonObject) =
        FieldValue(o.getValue("value"), o.getValue("by").jsonPrimitive.content, Instant.parse(o.getValue("at").jsonPrimitive.content), o.getValue("seq").jsonPrimitive.long)

    private fun json(f: FieldValue) = buildJsonObject {
        put("value", f.value)
        put("by", f.by)
        put("at", f.at.toString())
        put("seq", f.seq)
    }

    private fun edit(o: JsonObject) = FieldEdit(o.getValue("value"), Instant.parse(o.getValue("edited_at").jsonPrimitive.content))

    @Test
    fun theMergeRulesGiveWhatTheServerGives() {
        val file = read("merge/cases.json")
        val defaults = file.getValue("defaults").jsonObject
        val cases = file.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 15, "every case is read")
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            fun setting(key: String) = case[key] ?: defaults.getValue(key)
            val row = (case["row"] as? JsonObject)?.let { r ->
                SyncRow(
                    r.getValue("fields").jsonObject.mapValues { field(it.value.jsonObject) },
                    (r["deleted"] as? JsonObject)?.let(::field),
                )
            }
            val c = case.getValue("change").jsonObject
            val change = RowChange(
                by = c.getValue("by").jsonPrimitive.content,
                baseSeq = c.getValue("base_seq").jsonPrimitive.long,
                clientNow = Instant.parse(c.getValue("client_now").jsonPrimitive.content),
                set = (c["set"] as? JsonObject)?.mapValues { edit(it.value.jsonObject) }.orEmpty(),
                delete = (c["delete"] as? JsonObject)?.let(::edit),
            )
            val live = setting("live").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val (merged, conflicts) = Merge.apply(row, change, live, setting("seq").jsonPrimitive.long, Instant.parse(setting("server_now").jsonPrimitive.content))

            val got = buildJsonObject {
                put(
                    "row",
                    buildJsonObject {
                        put("fields", JsonObject(merged.fields.mapValues { json(it.value) }))
                        merged.deleted?.let { put("deleted", json(it)) }
                    },
                )
                put(
                    "conflicts",
                    buildJsonArray {
                        for (k in conflicts) add(
                            buildJsonObject {
                                put("kind", k.kind)
                                k.field?.let { put("field", it) }
                                k.kept?.let { put("kept", json(it)) }
                                k.lost?.let { put("lost", json(it)) }
                            },
                        )
                    },
                )
            }
            val expect = case.getValue("expect").jsonObject
            assertTrue(sameAsExpected(expect, got), "$name\nexpected $expect\n     got $got")
        }
    }

    // [expect] and [got] alike: the merge's own notion of the same value, and times written either way (12:00:00Z, 12:00Z).
    private fun sameAsExpected(expect: JsonElement, got: JsonElement): Boolean = when {
        expect is JsonObject && got is JsonObject ->
            expect.keys.filter { expect[it] !is JsonNull } .toSet() == got.keys.filter { got[it] !is JsonNull }.toSet() &&
                expect.keys.filter { expect[it] !is JsonNull }.all { sameAsExpected(expect.getValue(it), got.getValue(it)) }
        expect is JsonPrimitive && got is JsonPrimitive && expect.isString && got.isString ->
            expect.content == got.content || runCatching { Instant.parse(expect.content) == Instant.parse(got.content) }.getOrDefault(false)
        expect is kotlinx.serialization.json.JsonArray && got is kotlinx.serialization.json.JsonArray ->
            expect.size == got.size && expect.indices.all { sameAsExpected(expect[it], got[it]) }
        else -> Merge.same(expect, got)
    }

    @Test
    fun thePermissionRulesGiveWhatTheServerGives() {
        val file = read("permissions/cases.json")
        val ws = file.getValue("workspace").jsonObject
        fun levels(o: JsonObject) = o.mapValues { Level.of(it.value) }
        val workspace = Workspace(
            leader = ws.getValue("leader").jsonPrimitive.content,
            roles = ws.getValue("roles").jsonObject.mapValues { (_, r) ->
                val role = r.jsonObject
                Role(role.getValue("position").jsonPrimitive.int, levels(role.getValue("permissions").jsonObject))
            },
            members = ws.getValue("members").jsonObject.mapValues { (_, roles) -> roles.jsonArray.map { it.jsonPrimitive.content } },
            layers = ws.getValue("layers").jsonObject.mapValues { (_, byRole) -> byRole.jsonObject.mapValues { (_, set) -> levels(set.jsonObject) } },
        )
        val cases = file.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 38, "every case is read")
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val who = case.getValue("who").jsonPrimitive.content
            val got = when {
                "row" in case -> case.getValue("row").jsonObject.let { r ->
                    workspace.canTouchRow(who, r.getValue("perm").jsonPrimitive.content, r.getValue("layer").jsonPrimitive.content, r.getValue("owner").jsonPrimitive.content)
                }
                "manage_role" in case -> case.getValue("manage_role").jsonObject.let { m ->
                    workspace.canManageRole(who, m.getValue("role").jsonPrimitive.content, m.getValue("via").jsonPrimitive.content)
                }
                "grant" in case -> case.getValue("grant").jsonObject.let { g ->
                    workspace.canGrant(who, g.getValue("role").jsonPrimitive.content, g.getValue("perm").jsonPrimitive.content, Level.of(g.getValue("level")))
                }
                "override" in case -> case.getValue("override").jsonObject.let { o ->
                    workspace.canSetOverride(who, o.getValue("role").jsonPrimitive.content, levels(o.getValue("set").jsonObject))
                }
                "manage_member" in case -> workspace.canManageMember(who, case.getValue("manage_member").jsonPrimitive.content)
                "layer" in case -> workspace.level(who, case.getValue("perm").jsonPrimitive.content, case.getValue("layer").jsonPrimitive.content) != Level.NONE
                else -> workspace.has(who, case.getValue("perm").jsonPrimitive.content)
            }
            assertEquals(case.getValue("expect").jsonPrimitive.content.toBoolean(), got, name)
        }
    }

    @Test
    fun editTimesGoOntoTheServersClock() {
        val now = Instant.parse("2026-11-25T12:00:00Z")
        // A phone an hour fast: its edit ten minutes ago is ten minutes ago on the server too.
        assertEquals(Instant.parse("2026-11-25T11:50:00Z"), Merge.editTime(Instant.parse("2026-11-25T12:50:00Z"), Instant.parse("2026-11-25T13:00:00Z"), now))
        assertEquals(now, Merge.editTime(Instant.parse("2026-11-25T14:00:00Z"), Instant.parse("2026-11-25T13:00:00Z"), now), "never later than now")
    }
}
