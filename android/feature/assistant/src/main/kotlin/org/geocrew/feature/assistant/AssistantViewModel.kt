package org.geocrew.feature.assistant

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.actions.ActionHost
import org.geocrew.core.actions.ActionRunner
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.Props
import org.geocrew.core.model.RecordRules
import org.geocrew.core.model.SpeechText
import org.geocrew.core.model.action.ActionCall
import org.geocrew.core.model.action.ActionCatalog
import org.geocrew.core.model.action.ActionRateLimiter
import org.geocrew.core.model.action.ActionResult
import org.geocrew.core.model.action.ActionRisk
import org.geocrew.core.model.action.ActionSource
import org.geocrew.core.model.action.GuidedPoint
import org.geocrew.core.model.action.OpenAiWire
import org.geocrew.core.model.action.PermissionPolicy
import org.geocrew.core.model.action.Pending
import org.geocrew.core.model.action.QuickCommand
import org.geocrew.core.model.action.TeamAiLimits
import org.geocrew.core.model.nextName
import org.geocrew.core.model.string
import org.geocrew.core.speech.OfflineSpeech
import org.geocrew.core.speech.Pinyin
import java.util.UUID
import javax.inject.Inject

/** One line in the conversation. */
data class ChatLine(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    /** Set on a line listing what was done, so it can be undone as a whole. */
    val requestId: String? = null,
    val undone: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, DONE, NOTE }
}

/** What waits for the user's single tap before running. */
data class Confirmation(val requestId: String, val items: List<Pending>)

/**
 * The conversation. Each message goes, in order, to: an open guided
 * question (定点 → 点号 → 岩性…), the local quick commands, and only then
 * the model. Everything that changes data runs through [ActionRunner];
 * the [PermissionPolicy] decides what runs at once and what waits for one
 * confirmation card.
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: ActionRunner,
    private val host: ActionHost,
    private val source: ModelSource,
    private val config: AssistantConfig,
    private val speaker: Speaker,
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    private val offline: OfflineSpeech,
) : ViewModel() {
    val settings = config.values

    private val _lines = MutableStateFlow(listOf(ChatLine(role = ChatLine.Role.ASSISTANT, text = HELLO)))
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _confirm = MutableStateFlow<Confirmation?>(null)
    val confirm: StateFlow<Confirmation?> = _confirm.asStateFlow()

    /** Fires when hands-free mode wants the microphone again. */
    private val _listen = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val listen: SharedFlow<Unit> = _listen.asSharedFlow()

    val offlineReady: Boolean get() = offline.ready
    fun offlineListen() = offline.listen()

    // Conversation sent to the model: user, assistant and tool messages in order.
    private val history = mutableListOf<JsonObject>()
    private val sessionAllowed = mutableSetOf<ActionRisk>()
    private val limiter = ActionRateLimiter()

    // The guided point entry, when one is open.
    private var guide: GuidedPoint? = null
    private var guideState: GuidedPoint.State? = null
    private var guideSource = ActionSource.USER
    private var guideRequest: String? = null
    /** Record waiting for a yes to "open the camera?". */
    private var photoFor: String? = null

    // A model turn held at a confirmation card.
    private var heldTainted = false
    private var heldResults = LinkedHashMap<String, JsonObject>()
    private var heldOrder: List<String> = emptyList()

    private fun add(line: ChatLine) { _lines.value = _lines.value + line }

    /** The assistant speaks: shown, and said aloud when speech is on; hands-free listens again after. */
    private fun say(text: String, expectAnswer: Boolean = false) {
        add(ChatLine(role = ChatLine.Role.ASSISTANT, text = text))
        val s = config.values.value
        if (s.speak) {
            speaker.say(text, s.rate) { if (s.handsFree && (expectAnswer || guideState != null || photoFor != null)) _listen.tryEmit(Unit) }
        } else if (s.handsFree && expectAnswer) {
            _listen.tryEmit(Unit)
        }
    }

    /** Reads [text] aloud without adding a line, for results already shown. */
    private fun speak(text: String) {
        val s = config.values.value
        if (s.speak) speaker.say(text, s.rate) {}
    }

    private fun done(results: List<ActionResult>, requestId: String) {
        val ok = results.filter { it.ok }
        if (ok.isEmpty()) return
        add(ChatLine(role = ChatLine.Role.DONE, text = ok.joinToString("\n") { "✓ " + it.summary }, requestId = requestId))
    }

    fun clear() {
        history.clear(); sessionAllowed.clear(); guide = null; guideState = null; photoFor = null; _confirm.value = null
        _lines.value = listOf(ChatLine(role = ChatLine.Role.ASSISTANT, text = HELLO))
    }

    fun stopSpeaking() = speaker.stop()

    private suspend fun rules(): RecordRules = projects.current.first().config.recordRules

    private suspend fun lithologies(): List<String> {
        val p = projects.current.first()
        return features.all(p.id).mapNotNull { it.properties.string(Props.LITHOLOGY) }.distinct()
    }

    /** Recognised speech gets the same term correction as dictation. */
    suspend fun fixSpeech(raw: String): String {
        val p = projects.current.first()
        val vocab = SpeechText.vocabulary(p.config.phrases, lithologies())
        return SpeechText.fix(SpeechText.clean(raw), vocab, Pinyin::of).text
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _busy.value) return
        add(ChatLine(role = ChatLine.Role.USER, text = text))
        viewModelScope.launch {
            _busy.value = true
            try {
                handle(text)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                say("出错了：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun handle(text: String) {
        if (_confirm.value != null) { say("请先在上面的卡片里确认或取消。"); return }
        photoFor?.let { id ->
            photoFor = null
            if (YES.matches(text.replace(Regex("[\\s，。！？,.!?]"), ""))) {
                val r = runner.run(ActionCall("photo", ActionCatalog.TAKE_PHOTO, buildJsonObject { put("id", id) }), ActionSource.USER, newRequest())
                if (!r.ok) say(r.summary)
                return
            }
            if (text.length <= 4) { say("好的，先不拍。"); return }
        }
        if (guideState != null) { answerGuide(text); return }

        val quick = QuickCommand.parse(text, lithologies())
        if (quick != null) {
            val req = newRequest()
            val results = mutableListOf<ActionResult>()
            for (c in quick) {
                if (c.name == ActionCatalog.PLACE_POINT) { startGuide(c.args, ActionSource.USER, req); return }
                results += runner.run(c, ActionSource.USER, req)
            }
            val failed = results.firstOrNull { !it.ok }
            if (results.any { it.ok && it.touched.isNotEmpty() }) {
                // Changes show once, as an undoable ✓ line, and are read out.
                done(results.filter { it.touched.isNotEmpty() }, req)
                if (failed != null) say(failed.summary) else speak(results.joinToString("；") { it.summary })
            } else {
                say(failed?.summary ?: results.joinToString("；") { it.summary })
            }
            return
        }
        if (!source.available) {
            say("这句我在手机上没听懂。可以说：定点、打点 花岗岩 120∠35、产状 120∠35、岩性 花岗岩、补充 见石英脉、采样、开始轨迹、停止轨迹、拍照、撤销、今天做了多少、D012 附近有哪些点、这里是什么地层。在右上角设置里填上自己的 AI 接口后，可以随便说。")
            return
        }
        history += OpenAiWire.user(text)
        modelTurn(newRequest(), tainted = false)
    }

    private fun newRequest() = UUID.randomUUID().toString()

    // --- guided point -------------------------------------------------------------

    private suspend fun startGuide(args: JsonObject, from: ActionSource, requestId: String) {
        val p = projects.current.first()
        val rules = p.config.recordRules
        val suggested = if (GuidedPoint.named(args)) null else p.config.nextName(org.geocrew.core.model.FeatureKind.GEO_POINT, features.all(p.id).map { it.title })
        val g = GuidedPoint(rules, suggested, lithologies())
        guide = g; guideSource = from; guideRequest = requestId
        val st = g.start(args)
        guideState = st
        if (st.done) finishGuide() else say(g.question(st)!!, expectAnswer = true)
    }

    private suspend fun answerGuide(text: String) {
        val g = guide ?: return
        val st = g.answer(guideState ?: return, text)
        guideState = st
        when {
            st.cancelled -> { guide = null; guideState = null; say("好的，这个点不打了。") }
            st.done -> finishGuide()
            else -> say(listOfNotNull(st.remark, g.question(st)).joinToString("。"), expectAnswer = true)
        }
    }

    private suspend fun finishGuide() {
        val g = guide ?: return
        val st = guideState ?: return
        guide = null; guideState = null
        val req = guideRequest ?: newRequest()
        val r = runner.run(g.call(st), guideSource, req)
        done(listOf(r), req)
        if (!r.ok) { say(r.summary); return }
        val id = r.touched.firstOrNull()
        val remind = g.photoReminder()
        if (id != null && remind != null) {
            photoFor = id
            say(listOfNotNull(st.remark, r.summary + "。", remind).joinToString(""), expectAnswer = true)
        } else {
            say(listOfNotNull(st.remark, r.summary).joinToString("。"))
        }
    }

    // --- model --------------------------------------------------------------------

    private suspend fun systemPrompt(): JsonObject {
        val guideText = runCatching { context.assets.open("assistant_guide.md").bufferedReader().use { it.readText() } }.getOrDefault("")
        val p = projects.current.first()
        val rules = p.config.recordRules
        val extra = buildList {
            addAll(runner.stateLines())
            if (rules.active) {
                add("本工程记录规则：必填 " + rules.items.joinToString("、") { it.label }.ifEmpty { "无" } +
                    (if (rules.minPhotos > 0) "，每个点至少 ${rules.minPhotos} 张照片" else "") +
                    (rules.pointPrefix?.let { "，点号前缀 $it" } ?: ""))
            }
            val limits = TeamAiLimits(p.config.teamAiDeny.mapNotNull { ActionRisk.of(it) }.toSet())
            if (limits.denied.isNotEmpty()) add("组长不允许 AI：" + limits.denied.joinToString("、") { it.label })
        }
        return OpenAiWire.system(guideText + "\n\n" + OpenAiWire.stateBlock(extra))
    }

    private suspend fun policy(): PermissionPolicy {
        val p = projects.current.first()
        return PermissionPolicy(
            config.values.value.permissions,
            TeamAiLimits(p.config.teamAiDeny.mapNotNull { ActionRisk.of(it) }.toSet()),
            sessionAllowed.toSet(),
        )
    }

    private fun offered() = ActionCatalog.all.filter { it.offeredOn(host.page.value) }

    /** Runs model rounds until it answers in words, waits for a confirmation, or hands over to guided entry. */
    private suspend fun modelTurn(requestId: String, tainted: Boolean, rounds: Int = 0) {
        var taint = tainted
        var round = rounds
        while (round < MAX_ROUNDS) {
            round++
            val reply = source.chat(OpenAiWire.request(source.model, listOf(systemPrompt()) + history.takeLast(MAX_HISTORY).dropWhileOrphanTools(), offered()))
            history += reply.message
            if (reply.calls.isEmpty()) {
                say(reply.text ?: "好的。")
                return
            }
            reply.text?.let { add(ChatLine(role = ChatLine.Role.ASSISTANT, text = it)) }
            if (!limiter.tryAcquire(reply.calls.size, System.currentTimeMillis())) {
                reply.calls.forEach { history += OpenAiWire.toolResult(it.id, ActionResult.fail("操作太频繁，已暂停").toJson()) }
                say("一分钟内的操作太多，先停一下。")
                return
            }
            val plan = policy().plan(reply.calls, ActionCatalog::byName, ActionSource.AI, taint)
            val results = LinkedHashMap<String, JsonObject>()
            reply.calls.filter { it.id in reply.badArguments }.forEach { results[it.id] = ActionResult.fail("参数不是合法的 JSON").toJson() }
            plan.refused.forEach { results[it.call.id] = ActionResult.fail(it.reason).toJson() }
            val ran = mutableListOf<ActionResult>()
            var handedOver = false
            for ((call, _) in plan.run) {
                if (call.id in results) continue
                if (call.name == ActionCatalog.PLACE_POINT && !handedOver && needsGuide(call.args)) {
                    // Missing items are asked one by one on the phone, then the point is placed.
                    results[call.id] = ActionResult(true, "已开始在手机上逐项询问用户缺的项，点会在问完后打下").toJson()
                    handedOver = true
                    startGuideLater = call.args
                    continue
                }
                val r = runner.run(call, ActionSource.AI, requestId)
                ran += r
                results[call.id] = r.toJson()
                if (call.name in ActionCatalog.returnsUserText) taint = true
            }
            done(ran, requestId)
            if (plan.confirm.isNotEmpty()) {
                heldResults = results; heldOrder = reply.calls.map { it.id }; heldTainted = taint
                _confirm.value = Confirmation(requestId, plan.confirm)
                val s = config.values.value
                if (s.speak) speaker.say("有 ${plan.confirm.size} 个操作要你确认。", s.rate)
                return
            }
            reply.calls.forEach { c -> history += OpenAiWire.toolResult(c.id, results[c.id] ?: ActionResult.fail("没有执行").toJson()) }
            startGuideLater?.let { args -> startGuideLater = null; startGuide(args, ActionSource.AI, requestId); return }
        }
        say("步骤太多了，先做到这里。")
    }

    private var startGuideLater: JsonObject? = null

    private suspend fun needsGuide(args: JsonObject): Boolean {
        val rules = rules()
        fun has(k: String) = args[k] != null
        return (rules.requires(RecordRules.Item.LITHOLOGY) && !has("lithology")) ||
            (rules.requires(RecordRules.Item.ATTITUDE) && !(has("dip") && has("dip_direction"))) ||
            (rules.requires(RecordRules.Item.NOTE) && !has("note"))
    }

    /** One tap runs every held action; [always] allows those categories for the rest of the conversation. */
    fun approve(always: Boolean) {
        val c = _confirm.value ?: return
        _confirm.value = null
        if (always) sessionAllowed += c.items.map { it.risk }
        viewModelScope.launch {
            _busy.value = true
            try {
                val ran = mutableListOf<ActionResult>()
                for (item in c.items) {
                    val r = runner.run(item.call, ActionSource.AI, c.requestId)
                    ran += r
                    heldResults[item.call.id] = r.toJson()
                }
                done(ran, c.requestId)
                resume(c.requestId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                say("出错了：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                _busy.value = false
            }
        }
    }

    fun reject() {
        val c = _confirm.value ?: return
        _confirm.value = null
        c.items.forEach { heldResults[it.call.id] = ActionResult.fail("用户没有同意这个操作").toJson() }
        viewModelScope.launch {
            _busy.value = true
            try { resume(c.requestId) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                say("出错了：" + (e.message ?: ""))
            } finally { _busy.value = false }
        }
    }

    private suspend fun resume(requestId: String) {
        heldOrder.forEach { id -> history += OpenAiWire.toolResult(id, heldResults[id] ?: ActionResult.fail("没有执行").toJson()) }
        heldOrder = emptyList(); heldResults = LinkedHashMap()
        startGuideLater?.let { args -> startGuideLater = null; startGuide(args, ActionSource.AI, requestId); return }
        modelTurn(requestId, heldTainted)
    }

    fun undo(line: ChatLine) {
        val req = line.requestId ?: return
        viewModelScope.launch {
            val note = runner.undoRequest(req)
            _lines.value = _lines.value.map { if (it.id == line.id) it.copy(undone = true) else it }
            say(note ?: "已撤销。")
        }
    }

    /** Keeps the history valid: a window must not start with tool results whose call was cut off. */
    private fun List<JsonObject>.dropWhileOrphanTools(): List<JsonObject> =
        dropWhile { (it["role"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "tool" }

    override fun onCleared() {
        speaker.stop()
    }

    companion object {
        const val MAX_ROUNDS = 6
        const val MAX_HISTORY = 40
        private val YES = Regex("^(好|好的|是|是的|对|拍|拍吧|拍照|打开|打开相机|可以|行|嗯|要)$")
        const val HELLO = "可以说「定点」，我会一项项问你点号、岩性、产状和描述；也可以说「开始轨迹」「产状 120∠35」「岩性 花岗岩」「补充 见石英脉」「采样」「拍照」「撤销」「今天做了多少」「D012 附近有哪些点」，导入了参考图层还可以问「这里是什么地层」。"
    }
}
