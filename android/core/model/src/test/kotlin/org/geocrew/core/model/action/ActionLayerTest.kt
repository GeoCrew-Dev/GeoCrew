package org.geocrew.core.model.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class ActionLayerTest {
    private fun eq(expected: Double, actual: Double?) = assertEquals(expected, actual!!, 1e-9)

    private fun call(name: String, vararg ids: String) = ActionCall(
        name, name,
        if (ids.isEmpty()) buildJsonObject {} else buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))); put("key", "x"); put("value", "y") },
    )

    @Test
    fun policyRunsEditsAsksForRiskyAndRespectsLimits() {
        val p = PermissionPolicy()
        val plan = p.plan(
            listOf(call(ActionCatalog.PLACE_POINT), call(ActionCatalog.SET_ATTRIBUTE, "a"), call(ActionCatalog.SET_ATTRIBUTE, "a", "b"), call(ActionCatalog.DELETE_RECORDS, "a"), call("nope")),
            ActionCatalog::byName, ActionSource.AI,
        )
        assertEquals(listOf(ActionCatalog.PLACE_POINT, ActionCatalog.SET_ATTRIBUTE), plan.run.map { it.first.name })
        assertEquals(listOf(ActionRisk.BULK, ActionRisk.DELETE), plan.confirm.map { it.risk })
        assertEquals(1, plan.refused.size)

        // The user's own quick command is never held.
        assertTrue(p.plan(listOf(call(ActionCatalog.DELETE_RECORDS, "a")), ActionCatalog::byName, ActionSource.USER).confirm.isEmpty())

        // Leader forbids sending; user forbids deleting; user allows bulk for this conversation.
        val q = PermissionPolicy(
            AiPermissions().with(ActionRisk.DELETE, ActionMode.DENY),
            TeamAiLimits(setOf(ActionRisk.SEND)),
            sessionAllowed = setOf(ActionRisk.BULK),
        )
        val send = ActionCall("s", ActionCatalog.SEND_TEAM_MESSAGE, buildJsonObject { put("text", "hi") })
        assertTrue(q.decide(ActionCatalog.sendTeamMessage, send, ActionSource.AI) is Decision.Deny)
        assertTrue(q.decide(ActionCatalog.deleteRecords, call(ActionCatalog.DELETE_RECORDS, "a"), ActionSource.AI) is Decision.Deny)
        assertEquals(Decision.Run, q.decide(ActionCatalog.setAttribute, call(ActionCatalog.SET_ATTRIBUTE, "a", "b"), ActionSource.AI))

        // After reading record text, even an edit waits; reading still runs.
        assertTrue(p.decide(ActionCatalog.placePoint, call(ActionCatalog.PLACE_POINT), ActionSource.AI, tainted = true) is Decision.Confirm)
        assertEquals(Decision.Run, p.decide(ActionCatalog.findRecords, call(ActionCatalog.FIND_RECORDS), ActionSource.AI, tainted = true))

        // Cap per request.
        val many = p.plan(List(15) { call(ActionCatalog.PLACE_POINT) }, ActionCatalog::byName, ActionSource.AI)
        assertEquals(PermissionPolicy.MAX_PER_REQUEST, many.run.size)
        assertEquals(3, many.refused.size)

        val lim = ActionRateLimiter(max = 5, windowMillis = 1000)
        assertTrue(lim.tryAcquire(4, 0)); assertFalse(lim.tryAcquire(2, 10)); assertTrue(lim.tryAcquire(5, 1000))

        // Settings round trip.
        val perms = AiPermissions().with(ActionRisk.SEND, ActionMode.DENY)
        assertEquals(perms, AiPermissions.fromJson(perms.toJson()))
        assertEquals(setOf(ActionRisk.SEND), TeamAiLimits.fromJson(TeamAiLimits(setOf(ActionRisk.SEND, ActionRisk.READ)).toJson()).denied)
    }

    @Test
    fun chineseNumbers() {
        eq(120.0, ChineseNumber.parse("一百二十"))
        eq(35.0, ChineseNumber.parse("三十五"))
        eq(105.0, ChineseNumber.parse("一百零五"))
        eq(10.0, ChineseNumber.parse("十"))
        eq(45.5, ChineseNumber.parse("四十五点五"))
        eq(120.0, ChineseNumber.parse("一二零"))
        assertEquals("倾向120度倾角35度，一条石英脉", ChineseNumber.normalize("倾向一百二十度倾角三十五度，一条石英脉"))
    }

    @Test
    fun quickCommands() {
        assertEquals(ActionCatalog.START_TRACK, QuickCommand.parse("开始记录轨迹")!!.single().name)
        assertEquals(ActionCatalog.STOP_TRACK, QuickCommand.parse("停止轨迹。")!!.single().name)
        assertEquals(ActionCatalog.TAKE_PHOTO, QuickCommand.parse("拍照")!!.single().name)

        val p = QuickCommand.parse("打点 花岗岩 120∠35 描述 灰白色中粒结构")!!.single()
        assertEquals(ActionCatalog.PLACE_POINT, p.name)
        assertEquals("花岗岩", p.args["lithology"]!!.jsonPrimitive.content)
        eq(120.0, p.args["dip_direction"]!!.jsonPrimitive.double)
        eq(35.0, p.args["dip"]!!.jsonPrimitive.double)
        assertEquals("灰白色中粒结构", p.args["note"]!!.jsonPrimitive.content)

        val spoken = QuickCommand.parse("打一个点，倾向一百二十度倾角三十五度")!!.single()
        eq(120.0, spoken.args["dip_direction"]!!.jsonPrimitive.double)

        val att = QuickCommand.parse("产状 210/48")!!.single()
        assertEquals(ActionCatalog.UPDATE_RECORD, att.name)
        assertEquals(ActionCatalog.CURRENT, att.args["id"]!!.jsonPrimitive.content)
        eq(48.0, att.args["dip"]!!.jsonPrimitive.double)

        // More than one step, or not understood: the assistant handles it.
        assertNull(QuickCommand.parse("打点然后发给全组"))
        assertNull(QuickCommand.parse("这一带的花岗岩都改成二长花岗岩"))
        assertNull(QuickCommand.parse("120∠35 是哪个点的"))
    }

    @Test
    fun quickCommandsOnTheRecordAtHand() {
        val lith = QuickCommand.parse("岩性改成二长花岗岩")!!.single()
        assertEquals(ActionCatalog.UPDATE_RECORD, lith.name)
        assertEquals("二长花岗岩", lith.args["lithology"]!!.jsonPrimitive.content)
        assertEquals(ActionCatalog.CURRENT, lith.args["id"]!!.jsonPrimitive.content)
        assertEquals("见石英脉穿插", QuickCommand.parse("补充一下，见石英脉穿插")!!.single().args["note_append"]!!.jsonPrimitive.content)

        val s = QuickCommand.parse("采个薄片样 s12")!!.single()
        assertEquals(ActionCatalog.ADD_SAMPLE, s.name)
        assertEquals("S12", s.args["no"]!!.jsonPrimitive.content)
        assertEquals("薄片", s.args["kind"]!!.jsonPrimitive.content)
        val auto = QuickCommand.parse("采样")!!.single()
        assertNull(auto.args["no"])
        assertNull(QuickCommand.parse("岩性120"))
        for (t in listOf("这里是什么地层", "我在哪个图斑里", "这是什么岩体", "查一下参考图层")) {
            assertEquals(t, ActionCatalog.REFERENCE_HERE, QuickCommand.parse(t)?.single()?.name)
        }
        for (t in listOf("今天做了多少", "今日小结", "今天进度", "日报")) {
            assertEquals(t, ActionCatalog.DAY_SUMMARY, QuickCommand.parse(t)?.single()?.name)
        }
    }

    @Test
    fun wireFormat() {
        val req = OpenAiWire.request("m", listOf(OpenAiWire.user("hi")), listOf(ActionCatalog.placePoint))
        assertTrue(req.toString().contains("\"name\":\"place_point\""))
        val reply = OpenAiWire.parse(
            """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"c1","type":"function","function":{"name":"place_point","arguments":"{\"lithology\":\"花岗岩\"}"}},
              {"id":"c2","type":"function","function":{"name":"start_track","arguments":"oops"}}]}}]}""",
        )
        assertNull(reply.text)
        assertEquals(listOf("c1", "c2"), reply.calls.map { it.id })
        assertEquals("花岗岩", reply.calls[0].args["lithology"]!!.jsonPrimitive.content)
        assertEquals(listOf("c2"), reply.badArguments)
        val err = runCatching { OpenAiWire.parse("""{"error":{"message":"invalid key"}}""") }.exceptionOrNull()
        assertEquals("invalid key", err?.message)
        assertEquals(listOf("a", "b"), OpenAiWire.models("""{"data":[{"id":"a"},{"id":"b"}]}"""))
    }
}

class GuidedPointTest {
    private fun s(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.content

    @Test
    fun asksForWhatIsMissingAndUnderstandsAnswers() {
        val rules = org.geocrew.core.model.RecordRules(required = listOf("lithology", "attitude"), minPhotos = 2)
        val g = GuidedPoint(rules, "D012")
        var st = g.start()
        assertEquals(GuidedPoint.Field.NAME, st.next)
        st = g.answer(st, "好的")
        assertEquals("D012", s(st.args["title"]))
        assertEquals(GuidedPoint.Field.LITHOLOGY, st.next)
        // One answer gives the rock and the attitude.
        st = g.answer(st, "花岗岩，一百二十 三十五度")
        assertEquals("花岗岩", s(st.args["lithology"]))
        assertEquals(GuidedPoint.Field.NOTE, st.next)
        st = g.answer(st, "跳过")
        assertTrue(st.done)
        assertEquals(ActionCatalog.PLACE_POINT, g.call(st).name)
        assertTrue(g.photoReminder()!!.contains("2"))

        // Required skipped: noted. Not understood: asked again. Cancel stops.
        var t = GuidedPoint(rules, null).start(buildJsonObject { put("lithology", "砂岩") })
        assertEquals(GuidedPoint.Field.ATTITUDE, t.next)
        val again = GuidedPoint(rules, null).answer(t, "不清楚")
        assertTrue(again.retry)
        t = GuidedPoint(rules, null).answer(t, "跳过")
        assertTrue(t.remark!!.contains("必填"))
        assertTrue(GuidedPoint(rules, null).answer(GuidedPoint(rules, null).start(), "取消").cancelled)

        // Asking only what is required.
        val quiet = GuidedPoint(org.geocrew.core.model.RecordRules(required = listOf("note"), askAll = false, askName = false), "D1")
        val q = quiet.start(buildJsonObject { put("lithology", "灰岩") })
        assertEquals(GuidedPoint.Field.NOTE, q.next)
        val qq = quiet.answer(q, "灰色薄层状，见方解石脉")
        assertTrue(qq.done)
        assertEquals("灰色薄层状 见方解石脉", s(qq.args["note"]))
    }

    @Test
    fun rulesFlagMissingItems() {
        val rules = org.geocrew.core.model.RecordRules(required = listOf("lithology", "bogus"), minPhotos = 1, pointPrefix = " 25D ").clean()
        assertEquals(listOf("lithology"), rules.required)
        assertEquals("25D001", rules.nextName(org.geocrew.core.model.Numbering(), listOf("D001")))
        val f = org.geocrew.core.model.GeoFeature(
            id = "a", projectId = "p", kind = org.geocrew.core.model.FeatureKind.GEO_POINT, geometry = null,
            createdAt = java.time.Instant.EPOCH, sync = org.geocrew.core.model.SyncInfo(updatedAt = java.time.Instant.EPOCH),
        )
        assertEquals(listOf("岩性", "照片"), rules.missing(f))
        val issues = org.geocrew.core.model.RecordCheck.of(listOf(f), rules = rules)["a"].orEmpty()
        assertTrue(issues.any { it.code == org.geocrew.core.model.RecordCheck.Code.INCOMPLETE })
    }
}
