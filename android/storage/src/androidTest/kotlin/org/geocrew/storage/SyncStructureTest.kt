package org.geocrew.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.core.edit.Editing
import org.geocrew.core.sync.Reply
import org.geocrew.core.sync.Transport
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// A shared project's structure and the team's (protocol v1 7.1-7.4): layers and fields made offline go before their
// values, the team's changes of fields come in, and two fields of one name become one, or keep two names.
@RunWith(AndroidJUnit4::class)
class SyncStructureTest {
    // A layer as the server keeps it: its fields, its version, the seq it last changed at, and the ids it no longer has.
    private class Layer(val id: String, val geometryType: String, val crs: String, val fields: MutableList<JsonObject>) {
        var version = 1L
        var seq = 0L
        var deleted = false
        val gone = mutableSetOf<String>()
    }

    // A server as far as structure goes (protocol v1 5.6, 7.3): layers made, fields added, rows taken; a pull gives the
    // layers changed since, and the rows set for it.
    private class FakeServer : Transport {
        val layers = linkedMapOf<String, Layer>()
        val events = mutableListOf<String>()
        val pushes = mutableListOf<JsonObject>()
        val rows = mutableListOf<Pair<Long, String>>()
        var seq = 0L
        var refuse = false // as for a member without edit_schema

        fun json(l: Layer) = buildJsonObject {
            put("id", l.id)
            put("geometry_type", l.geometryType)
            put("crs", l.crs)
            put("fields", JsonArray(l.fields))
            put("schema_version", l.version)
            put("seq", l.seq)
            if (l.deleted) put("deleted", buildJsonObject { put("value", true); put("by", "m1"); put("at", "2026-11-28T10:00:00Z"); put("seq", l.seq) }) else put("deleted", JsonNull)
        }

        // Another member's change of layer [id]'s fields, online.
        fun change(id: String, block: MutableList<JsonObject>.() -> Unit) {
            val l = layers.getValue(id)
            val before = l.fields.map { it.text("id") }
            l.fields.block()
            l.gone += before - l.fields.map { it.text("id") }.toSet()
            l.version++
            l.seq = ++seq
        }

        override fun send(method: String, path: String, token: String?, body: String?): Reply {
            val o = body?.let { Json.parseToJsonElement(it).jsonObject }
            return when {
                path == "/api/v1/info" -> Reply(200, """{"protocol": 1, "capabilities": ["sync", "create"], "limits": {"max_push_batch": 100, "max_pull_batch": 1000, "max_file_size": 1}}""")
                path == "/api/v1/workspaces" -> Reply(201, """{"workspace": {"id": "w1", "name": "组", "leader": "m1", "invite_code": "K7QX2MPA"}, "member": {"id": "m1"}, "token": "t1"}""")
                path.endsWith("/schema") -> schema(path.substringAfter("/layers/").substringBefore("/schema"), o!!)
                method == "DELETE" && path.contains("/layers/") -> {
                    if (refuse) return Reply(403, """{"error": "forbidden", "message": "manage_layers is required"}""")
                    val l = layers.getValue(path.substringAfterLast("/"))
                    l.deleted = true
                    l.seq = ++seq
                    events += "delete layer"
                    Reply(200, json(l).toString())
                }
                path.endsWith("/layers") -> create(o!!)
                path.endsWith("/rows") -> push(o!!)
                path.contains("/changes?since=") -> pull(path.substringAfter("since=").substringBefore("&").toLong())
                path.endsWith("/members") -> Reply(200, """[{"id": "m1", "nickname": "组长"}, {"id": "m2", "nickname": "李四"}]""")
                else -> Reply(404, """{"error": "not_found", "message": "$path"}""")
            }
        }

        private fun create(o: JsonObject): Reply {
            if (refuse) return Reply(403, """{"error": "forbidden", "message": "edit_schema is required"}""")
            val l = Layer(o.text("id"), o.text("geometry_type"), o.text("crs"), o.getValue("fields").jsonArray.map { it.jsonObject }.toMutableList())
            l.seq = ++seq
            layers[l.id] = l
            events += "layer"
            return Reply(200, json(l).toString())
        }

        private fun schema(id: String, o: JsonObject): Reply {
            if (refuse) return Reply(403, """{"error": "forbidden", "message": "edit_schema is required"}""")
            val l = layers.getValue(id)
            val fields = l.fields.toMutableList()
            val gone = mutableSetOf<String>()
            val ops = o.getValue("ops").jsonArray.map { it.jsonObject }
            // Renames and deletions go from the version the phone has, online only (protocol v1 7.3); adds from any.
            if (ops.any { it.text("op") != "add_field" } && o.getValue("base_version").jsonPrimitive.content.toLong() != l.version) {
                return Reply(409, buildJsonObject { put("error", "stale"); put("message", ""); put("layer", json(l)) }.toString())
            }
            fun taken(name: String, besides: String) = fields.firstOrNull { it.text("id") != besides && it.text("name").trim().equals(name.trim(), ignoreCase = true) }
            for (op in ops) {
                when (op.text("op")) {
                    "rename_field" -> {
                        taken(op.text("name"), op.text("id"))?.let { clash ->
                            return Reply(409, buildJsonObject { put("error", "name_taken"); put("message", ""); put("field", clash) }.toString())
                        }
                        val i = fields.indexOfFirst { it.text("id") == op.text("id") }
                        fields[i] = JsonObject(fields[i] + ("name" to JsonPrimitive(op.text("name"))))
                    }
                    "delete_field" -> {
                        fields.removeAll { it.text("id") == op.text("id") }
                        gone += op.text("id")
                    }
                    else -> {
                        val f = op.getValue("field").jsonObject
                        val had = fields.firstOrNull { it.text("id") == f.text("id") }
                        if (had == f) continue
                        if (had != null || f.text("id") in l.gone) return Reply(409, """{"error": "id_taken", "message": ""}""")
                        taken(f.text("name"), f.text("id"))?.let { clash ->
                            return Reply(409, buildJsonObject { put("error", "name_taken"); put("message", ""); put("field", clash) }.toString())
                        }
                        fields += f
                    }
                }
            }
            events += "schema " + ops.joinToString { op -> (op["field"] as? JsonObject)?.text("name") ?: "${op.text("op")} ${op["name"]?.jsonPrimitive?.content ?: op.text("id")}" }
            l.gone += gone
            if (fields != l.fields) {
                l.fields.clear()
                l.fields += fields
                l.version++
                l.seq = ++seq
            }
            return Reply(200, json(l).toString())
        }

        private fun push(o: JsonObject): Reply {
            val changes = o.getValue("changes").jsonArray.map { it.jsonObject }
            pushes += changes
            events += "rows"
            val results = buildJsonArray {
                for (c in changes) add(buildJsonObject { put("id", c.getValue("id")); put("status", "applied"); put("seq", ++seq); put("conflicts", JsonArray(emptyList())) })
            }
            return Reply(200, buildJsonObject { put("results", results); put("seq", seq) }.toString())
        }

        private fun pull(since: Long) = Reply(
            200,
            buildJsonObject {
                put("layers", JsonArray(layers.values.filter { it.seq > since }.map(::json)))
                put("rows", JsonArray(rows.filter { it.first > since }.map { Json.parseToJsonElement(it.second) }))
                put("conflicts", JsonArray(emptyList()))
                put("seq", seq)
                put("more", false)
            }.toString(),
        )
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "GeoCrew-structure")
    private val server = FakeServer()
    private lateinit var registry: ActionRegistry
    private lateinit var workspace: Workspace

    @Before
    fun setUp() {
        NativeData.setup(context)
        root.deleteRecursively()
        workspace = Workspace(Projects(root, File(context.noBackupFilesDir, "journals-structure").apply { deleteRecursively() }))
        val accounts = File(context.noBackupFilesDir, "sync-structure").apply { deleteRecursively() }
        registry = ActionRegistry().apply {
            workspace.actions.forEach(::register)
            SyncActions(workspace, accounts) { _, _ -> server }.actions.forEach(::register)
            ImportActions(workspace, root) { Probe.inspect(context, it.path) }.actions.forEach(::register)
        }
    }

    private fun run(action: String, params: String = "{}"): Outcome =
        runBlocking { registry.invoke(Caller.USER, action, Json.parseToJsonElement(params).jsonObject) }

    private fun output(outcome: Outcome): JsonObject {
        assertTrue(outcome is Outcome.Done, outcome.toString())
        return outcome.output.jsonObject
    }

    // A project with layer 野外点 (点名, 描述 and [fields]) and point D001 in it, shared with a team.
    private fun shared(name: String, vararg fields: String) {
        run(Workspace.CREATE, """{"name": "$name", "language": "zh"}""")
        run(Workspace.LAYER_CREATE, """{"name": "野外点", "language": "zh"}""")
        for (f in fields) addField(f)
        edit("野外点") { add("野外点", """{"点名": "D001"}""") }
        output(run(SyncActions.SHARE, """{"server": "http://fake", "nickname": "组长", "device": "test"}"""))
    }

    private fun addField(name: String) = output(run(Workspace.FIELD_ADD, """{"layer": "野外点", "name": "$name", "type": "string"}"""))

    // [change] made to [layer] and saved; editing starts first unless it goes on.
    private fun edit(layer: String, change: () -> Unit) {
        run(Editing.START, """{"layer": "$layer"}""")
        change()
        output(run(Editing.SAVE, """{"layer": "$layer"}"""))
    }

    private fun add(layer: String, properties: String) =
        output(run(Editing.ADD, """{"layer": "$layer", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": $properties}"""))

    private fun setValues(layer: String, point: String, values: String) =
        output(run(Editing.VALUES, """{"layer": "$layer", "fid": ${feature(layer, point).fid}, "values": $values}"""))

    private fun feature(layer: String, point: String) =
        workspace.current!!.target(layer).store.features().first { it.properties["点名"]?.jsonPrimitive?.contentOrNull == point }

    private val layerId get() = workspace.current!!.layers.single { it.name == "野外点" }.id

    private fun ids(layer: String = "野外点") = workspace.current!!.fieldIds(layer)

    private fun structure() = output(run(SyncActions.STATUS)).getValue("structure").jsonPrimitive.int

    // The values sent for field [id], in the order they went.
    private fun sent(id: String) =
        server.pushes.mapNotNull { c -> ((c["set"] as? JsonObject)?.get(id) as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull }

    private fun field(id: String, name: String, type: String) = buildJsonObject { put("id", id); put("name", name); put("type", type) }

    @Test
    fun aFieldAddedOfflineGoesBeforeItsValues() {
        shared("加字段")
        addField("岩性")
        assertEquals(1, structure())
        edit("野外点") { add("野外点", """{"点名": "D002", "岩性": "花岗岩"}""") }
        server.events.clear()
        val now = output(run(SyncActions.NOW))
        assertEquals(listOf("schema 岩性", "rows"), server.events, "the field before the rows with its values")
        val id = ids().getValue("岩性")
        assertEquals(field(id, "岩性", "string"), server.layers.getValue(layerId).fields.last())
        assertEquals(listOf("花岗岩"), sent(id))
        assertEquals(0, now.getValue("structure").jsonPrimitive.int)
        assertEquals(0, structure())
    }

    @Test
    fun whatTheServerRefusesWaitsHereWithItsValues() {
        shared("没权限")
        server.refuse = true
        addField("岩性")
        output(run(Workspace.LAYER_CREATE, """{"name": "泉点", "language": "zh"}"""))
        edit("野外点") { setValues("野外点", "D001", """{"描述": "露头", "岩性": "灰岩"}""") }
        edit("泉点") { add("泉点", """{"点名": "S001"}""") }
        val spring = workspace.current!!.layers.single { it.name == "泉点" }.id
        output(run(SyncActions.NOW))
        val rock = ids().getValue("岩性")
        assertEquals(listOf("露头"), sent(ids().getValue("描述")), "the rest goes")
        assertEquals(emptyList(), sent(rock))
        assertTrue(server.pushes.none { it.text("layer") == spring })
        assertEquals(2, structure())
        // Shown as not sent: the layer, and the field in a shared one.
        fun unsent(info: JsonObject) = info["unsent"] == JsonPrimitive(true)
        val point = output(run(Workspace.LAYER_INFO, """{"layer": "野外点"}"""))
        assertEquals(setOf("岩性"), point.getValue("fields").jsonArray.map { it.jsonObject }.filter(::unsent).map { it.text("name") }.toSet())
        assertTrue(!unsent(point) && unsent(output(run(Workspace.LAYER_INFO, """{"layer": "泉点"}"""))))

        // Once the server takes them, the layer and the field go, and then what waited in them.
        server.refuse = false
        server.events.clear()
        output(run(SyncActions.NOW))
        assertEquals(listOf("layer", "schema 岩性", "rows"), server.events)
        assertEquals(listOf("灰岩"), sent(rock))
        assertTrue(server.pushes.any { it.text("layer") == spring })
        assertEquals(0, structure())
        assertTrue(output(run(Workspace.LAYER_INFO, """{"layer": "野外点"}""")).getValue("fields").jsonArray.none { it.jsonObject.containsKey("unsent") })
    }

    @Test
    fun aLayerImportedOfflineGoesWithItsFeatures() {
        shared("导入")
        val file = File(context.cacheDir, "点位.geojson").apply {
            writeText(
                """{"type": "FeatureCollection", "features": [
                    {"type": "Feature", "geometry": {"type": "Point", "coordinates": [114.3, 30.6]}, "properties": {"名称": "A1"}},
                    {"type": "Feature", "geometry": {"type": "Point", "coordinates": [114.31, 30.61]}, "properties": {"名称": "A2"}}]}""",
            )
        }
        output(run(ImportActions.IMPORT, buildJsonObject { put("path", file.path) }.toString()))
        val imported = workspace.current!!.layers.single { it.name == "点位" }.id
        output(run(SyncActions.NOW))
        assertTrue(imported in server.layers)
        val name = ids("点位").getValue("名称")
        assertEquals(setOf("A1", "A2"), server.pushes.filter { it.text("layer") == imported }.mapNotNull { (it["set"] as? JsonObject)?.get(name)?.jsonObject?.text("value") }.toSet())
    }

    @Test
    fun theTeamsFieldsComeInAddedRenamedAndDeleted() {
        shared("团队改结构", "产状")
        val describe = ids().getValue("描述")
        server.change(layerId) {
            replaceAll { if (it.text("id") == describe) field(describe, "备注", "string") else it }
            removeAll { it.text("name") == "产状" }
            add(field("fA", "岩性", "string"))
            add(field("fB", " fid ", "real")) // a name this phone cannot give a field as it is
        }
        val at = """"by": "m2", "at": "2026-11-28T10:00:00Z", "seq": ${server.seq}"""
        server.rows += server.seq to """{"layer": "$layerId", "id": "r9", "conflicted": false, "deleted": null, "fields": {
            "fA": {"value": "砂岩", $at}, "geom": {"value": {"type": "Point", "coordinates": [114.4, 30.6]}, $at}}}"""
        output(run(SyncActions.NOW))
        assertEquals(mapOf("点名" to ids()["点名"], "备注" to describe, "岩性" to "fA", "fid 2" to "fB"), ids())
        assertEquals(setOf("点名", "备注", "岩性", "fid 2"), workspace.current!!.target("野外点").store.fields.map { it.name }.toSet())
        assertTrue(workspace.current!!.target("野外点").store.features().any { it.properties["岩性"]?.jsonPrimitive?.contentOrNull == "砂岩" }, "the value came with its field")
    }

    @Test
    fun aFieldMadeTwiceOfflineBecomesOne() {
        shared("同名同类")
        addField("岩性")
        edit("野外点") { setValues("野外点", "D001", """{"岩性": "花岗岩"}""") }
        server.change(layerId) { add(field("fB", "岩性 ", "string")) }
        val now = output(run(SyncActions.NOW))
        assertEquals("fB", ids()["岩性"], "the team's id, for this phone's values too")
        assertEquals(listOf("花岗岩"), sent("fB"))
        assertEquals(1, server.layers.getValue(layerId).fields.count { it.text("name").trim() == "岩性" })
        assertEquals(JsonArray(emptyList()), now.getValue("renamed"))
        assertEquals(0, structure())
    }

    @Test
    fun aFieldOfAnotherTypeGivesItsNameUp() {
        shared("同名异类")
        addField("岩性")
        edit("野外点") { setValues("野外点", "D001", """{"岩性": "花岗岩"}""") }
        server.change(layerId) { add(field("fC", "岩性", "integer")) }
        val now = output(run(SyncActions.NOW))
        val renamed = now.getValue("renamed").jsonArray.single().jsonObject
        assertEquals(listOf("野外点", "岩性", "岩性 2"), listOf("layer", "from", "to").map { renamed.text(it) })
        val mine = ids().getValue("岩性 2")
        assertEquals("fC", ids()["岩性"], "the team's came in")
        assertEquals(field(mine, "岩性 2", "string"), server.layers.getValue(layerId).fields.last())
        assertEquals(listOf("花岗岩"), sent(mine))
        assertEquals("花岗岩", feature("野外点", "D001").properties["岩性 2"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aNameTheTeamGaveAnotherFieldIsGivenUpHere() {
        shared("改名撞名")
        addField("岩性")
        val describe = ids().getValue("描述")
        val mine = ids().getValue("岩性")
        server.change(layerId) { replaceAll { if (it.text("id") == describe) field(describe, "岩性", "string") else it } }
        val now = output(run(SyncActions.NOW))
        assertEquals("岩性 2", now.getValue("renamed").jsonArray.single().jsonObject.text("to"))
        assertEquals(mapOf("岩性" to describe, "岩性 2" to mine), ids().filterKeys { it.startsWith("岩性") })
        assertTrue(server.layers.getValue(layerId).fields.any { it.text("id") == mine && it.text("name") == "岩性 2" })
    }

    @Test
    fun namesTheTeamSwappedComeInBoth() {
        shared("互换", "产状")
        val describe = ids().getValue("描述")
        val dip = ids().getValue("产状")
        server.change(layerId) {
            replaceAll {
                when (it.text("id")) {
                    describe -> field(describe, "产状", "string")
                    dip -> field(dip, "描述", "string")
                    else -> it
                }
            }
        }
        output(run(SyncActions.NOW))
        assertEquals(dip, ids()["描述"])
        assertEquals(describe, ids()["产状"])
    }

    @Test
    fun aSharedFieldIsRenamedAndDeletedWithTheTeamOnline() {
        shared("在线改", "产状")
        val describe = ids().getValue("描述")
        val dip = ids().getValue("产状")
        val rename = """{"layer": "野外点", "field": "描述", "to": "备注"}"""
        assertEquals(Workspace.ONLINE_ONLY, (run(Workspace.FIELD_RENAME, rename) as Outcome.Failed).code)
        output(run(SyncActions.FIELD_RENAME, rename))
        assertEquals(describe, ids()["备注"])
        assertTrue(server.layers.getValue(layerId).fields.any { it.text("id") == describe && it.text("name") == "备注" })

        // A value in it not sent yet goes no more: the field is gone, here and there.
        edit("野外点") { setValues("野外点", "D001", """{"产状": "120/35"}""") }
        output(run(SyncActions.FIELD_DELETE, """{"layer": "野外点", "field": "产状"}"""))
        assertNull(ids()["产状"])
        assertTrue(server.layers.getValue(layerId).fields.none { it.text("id") == dip })
        output(run(SyncActions.NOW))
        assertEquals(emptyList(), sent(dip))
        assertEquals(0, output(run(SyncActions.STATUS)).getValue("waiting").jsonPrimitive.int)
    }

    @Test
    fun aChangeTheTeamOvertookBringsTheLayerInToDoItAgain() {
        shared("过期")
        server.change(layerId) { add(field("fA", "岩性", "string")) }
        val rename = """{"layer": "野外点", "field": "描述", "to": "备注"}"""
        assertEquals(SyncActions.STALE, (run(SyncActions.FIELD_RENAME, rename) as Outcome.Failed).code)
        assertEquals("fA", ids()["岩性"], "the layer as the team has it now")
        assertNull(ids()["备注"])
        output(run(SyncActions.FIELD_RENAME, rename))
        assertTrue(server.layers.getValue(layerId).fields.any { it.text("name") == "备注" })

        // A name another field of the team's has, though not here yet.
        server.change(layerId) { add(field("fB", "样品编号", "string")) }
        server.layers.getValue(layerId).version-- // as if this phone had the version: only the name stands in the way
        assertEquals(SyncActions.NAME_TAKEN, (run(SyncActions.FIELD_RENAME, """{"layer": "野外点", "field": "备注", "to": "样品编号"}""") as Outcome.Failed).code)
    }

    @Test
    fun aSharedLayerIsDeletedThereAndHereWithWhatItHadToSend() {
        shared("删图层")
        output(run(Workspace.LAYER_CREATE, """{"name": "泉点", "language": "zh"}"""))
        output(run(SyncActions.NOW))
        val spring = workspace.current!!.layers.single { it.name == "泉点" }.id
        edit("泉点") { add("泉点", """{"点名": "S001"}""") }
        assertEquals(1, output(run(SyncActions.STATUS)).getValue("waiting").jsonPrimitive.int)
        val delete = """{"layer": "泉点"}"""
        assertEquals(Workspace.ONLINE_ONLY, (run(Workspace.LAYER_DELETE, delete) as Outcome.Failed).code)
        output(run(SyncActions.LAYER_DELETE, delete))
        assertTrue(server.layers.getValue(spring).deleted)
        assertTrue(workspace.current!!.layers.none { it.id == spring })
        assertEquals(0, output(run(SyncActions.STATUS)).getValue("waiting").jsonPrimitive.int, "its new point goes nowhere")
        val pushes = server.pushes.size
        output(run(SyncActions.NOW))
        assertTrue(server.pushes.drop(pushes).none { it.text("layer") == spring })
    }

    @Test
    fun aLayerTheTeamDeletedIsHiddenHereAndComesBackRestored() {
        shared("团队删图层")
        val point = layerId
        server.layers.getValue(point).apply { deleted = true; seq = ++server.seq }
        output(run(SyncActions.NOW))
        assertTrue(workspace.current!!.layers.none { it.id == point }, "hidden with its features")
        assertTrue(workspace.current!!.hiddenLayers() == setOf(point))

        server.layers.getValue(point).apply { deleted = false; seq = ++server.seq }
        output(run(SyncActions.NOW))
        assertEquals(listOf("D001"), workspace.current!!.target("野外点").store.features().map { it.properties["点名"]?.jsonPrimitive?.contentOrNull })
        assertTrue(workspace.current!!.hiddenLayers().isEmpty())
    }

    @Test
    fun eachFeatureKnowsWhoLastEditedIt() {
        shared("编辑者")
        // A teammate's point comes in.
        val describe = ids().getValue("描述")
        server.seq++
        val at = """"by": "m2", "at": "2026-11-28T10:00:00Z", "seq": ${server.seq}"""
        server.rows += server.seq to """{"layer": "$layerId", "id": "r9", "conflicted": false, "deleted": null, "fields": {
            "$describe": {"value": "李四的点", $at}, "geom": {"value": {"type": "Point", "coordinates": [114.4, 30.6]}, $at}}}"""
        output(run(SyncActions.NOW))
        fun editors() = output(run(SyncActions.EDITORS, """{"layer": "野外点"}""")).getValue("editors").jsonArray.map { it.jsonObject }
            .associate { e -> e.getValue("fid").jsonPrimitive.long to (e["nickname"]?.jsonPrimitive?.content ?: "mine") }
        val mine = feature("野外点", "D001").fid
        val theirs = workspace.current!!.target("野外点").store.features().single { it.fid != mine }.fid
        assertEquals(mapOf(mine to "mine", theirs to "李四"), editors())

        // Edited here, it is this phone's.
        edit("野外点") { output(run(Editing.VALUES, """{"layer": "野外点", "fid": $theirs, "values": {"描述": "改过"}}""")) }
        assertEquals(mapOf(mine to "mine", theirs to "mine"), editors())
    }

    @Test
    fun whatTheTeamHasChangesWithTheTeamOnly() {
        shared("拦截")
        val shared = listOf(
            Workspace.FIELD_RENAME to """{"layer": "野外点", "field": "描述", "to": "备注"}""",
            Workspace.FIELD_DELETE to """{"layer": "野外点", "field": "描述"}""",
            Workspace.LAYER_DELETE to """{"layer": "野外点"}""",
        )
        for ((action, params) in shared) assertEquals(Workspace.ONLINE_ONLY, (run(action, params) as Outcome.Failed).code, action)
        assertNull(ids()["备注"])

        // What the workspace has not got yet is this phone's own; deleted, what it had to send goes nowhere.
        addField("岩性")
        edit("野外点") { setValues("野外点", "D001", """{"岩性": "灰岩"}""") }
        val rock = ids().getValue("岩性")
        output(run(Workspace.FIELD_RENAME, """{"layer": "野外点", "field": "岩性", "to": "岩石"}"""))
        output(run(Workspace.FIELD_DELETE, """{"layer": "野外点", "field": "岩石"}"""))
        output(run(Workspace.LAYER_CREATE, """{"name": "泉点", "language": "zh"}"""))
        edit("泉点") { add("泉点", """{"点名": "S001"}""") }
        output(run(Workspace.LAYER_DELETE, """{"layer": "泉点"}"""))
        assertEquals(0, structure())
        assertEquals(0, output(run(SyncActions.STATUS)).getValue("waiting").jsonPrimitive.int)
        output(run(SyncActions.NOW))
        assertEquals(emptyList(), sent(rock))
    }
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
