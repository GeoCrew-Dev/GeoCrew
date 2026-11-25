package org.geocrew.core.model.action

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.geocrew.core.model.RecordRules

/**
 * Placing a point by talking: after "定点" it asks for what is still
 * missing, one item at a time (name, rock, attitude, description), as the
 * project's [RecordRules] say, then places the point and reminds about
 * photos. One answer may give several items ("花岗岩，120∠35"), "跳过"
 * skips, "取消" stops. It runs on the phone, so it works offline and
 * costs no model calls; the assistant hands its place_point calls here too.
 */
class GuidedPoint(
    private val rules: RecordRules,
    /** The name the point would get, shown for confirming. */
    private val suggestedName: String?,
    private val lithologies: Collection<String> = emptyList(),
) {
    enum class Field(val label: String) { NAME("点号"), LITHOLOGY("岩性"), ATTITUDE("产状"), NOTE("描述") }

    data class State(
        val args: JsonObject,
        val asked: Set<Field> = emptySet(),
        val next: Field? = null,
        /** Set when the last answer could not be understood. */
        val retry: Boolean = false,
        val cancelled: Boolean = false,
        /** A note for the user about the last answer, e.g. a required item skipped. */
        val remark: String? = null,
    ) {
        val done: Boolean get() = next == null && !cancelled
    }

    private val skip = Regex("^(跳过|不用了?|没有|无|算了|下一个|不知道|不填|略|空)$")
    private val cancel = Regex("^(取消|不打了|不要了|停|停止|退出)$")
    private val yes = Regex("^(好|好的|对|对的|是|是的|可以|行|嗯|就这个|就用这个|没问题|确认|ok|OK)$")
    private val punct = Regex("[\\s，。、！？,.!?;；：:]+")

    private fun has(args: JsonObject, f: Field) = when (f) {
        Field.NAME -> !(args["title"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
        Field.LITHOLOGY -> !(args["lithology"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
        Field.ATTITUDE -> args["dip"] != null && args["dip_direction"] != null
        Field.NOTE -> !(args["note"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
    }

    private fun wanted(f: Field) = when (f) {
        Field.NAME -> rules.askName && suggestedName != null
        Field.LITHOLOGY -> rules.askAll || rules.requires(RecordRules.Item.LITHOLOGY)
        Field.ATTITUDE -> rules.askAll || rules.requires(RecordRules.Item.ATTITUDE)
        Field.NOTE -> rules.askAll || rules.requires(RecordRules.Item.NOTE)
    }

    fun required(f: Field) = when (f) {
        Field.NAME -> false
        Field.LITHOLOGY -> rules.requires(RecordRules.Item.LITHOLOGY)
        Field.ATTITUDE -> rules.requires(RecordRules.Item.ATTITUDE)
        Field.NOTE -> rules.requires(RecordRules.Item.NOTE)
    }

    private fun advance(args: JsonObject, asked: Set<Field>, retry: Boolean = false, remark: String? = null): State =
        State(args, asked, Field.entries.firstOrNull { it !in asked && wanted(it) && !has(args, it) }, retry, remark = remark)

    fun start(args: JsonObject = JsonObject(emptyMap())): State = advance(args, emptySet())

    /** What to ask (and say aloud) for [s]. */
    fun question(s: State): String? {
        val f = s.next ?: return null
        val must = if (required(f)) "（本工程必填）" else ""
        return when (f) {
            Field.NAME -> "点号用 $suggestedName 吗？也可以直接说别的点号。"
            Field.LITHOLOGY -> (if (s.retry) "没听清，" else "") + "岩性是什么？$must"
            Field.ATTITUDE -> (if (s.retry) "没听清产状，请按「倾向、倾角」说，比如 一百二十 三十五。" else "产状多少？比如 120∠35，没有就说跳过。") + must
            Field.NOTE -> "描述一下这个点？$must"
        }
    }

    /** Takes the user's answer to the current question. */
    fun answer(s: State, raw: String): State {
        val f = s.next ?: return s
        val text = punct.replace(raw.trim(), " ").trim()
        val bare = text.replace(" ", "")
        if (cancel.matches(bare)) return s.copy(cancelled = true, next = null)
        val asked = s.asked + f
        if (skip.matches(bare)) {
            val remark = if (required(f)) "${f.label}是本工程必填项，先空着，之后会出现在待核对里" else null
            return advance(s.args, asked, remark = remark)
        }
        val args = s.args.toMutableMap()
        // Anything recognisable in the answer counts, whichever question it answers.
        var rest = bare
        QuickCommand.attitude(text, loose = f == Field.ATTITUDE || f == Field.LITHOLOGY)?.let { (a, left) ->
            args["dip_direction"] = JsonPrimitive(a.dipDirection); args["dip"] = JsonPrimitive(a.dip); rest = left.replace(" ", "")
        }
        when (f) {
            Field.NAME -> {
                if (!yes.matches(bare)) {
                    val name = bare.replace(Regex("^(点号|编号|叫|就叫|改成|用)"), "").replace(Regex("(吧|号点)$"), "")
                    if (name.isNotEmpty()) args["title"] = JsonPrimitive(name.take(40))
                } else {
                    suggestedName?.let { args["title"] = JsonPrimitive(it) }
                }
                return advance(JsonObject(args), asked)
            }
            Field.ATTITUDE -> if (args["dip"] == null) return s.copy(retry = true, remark = null)
            Field.LITHOLOGY -> {
                val lith = QuickCommand.lithology(rest, lithologies)?.first
                    ?: rest.replace(Regex("^(岩性是|岩性|是)"), "").takeIf { it.isNotEmpty() && it.length <= 20 }
                if (lith == null) return s.copy(retry = true)
                args["lithology"] = JsonPrimitive(lith)
            }
            Field.NOTE -> {
                val note = text.replace(Regex("^(描述是|描述|备注)\\s*"), "").trim()
                if (note.isNotEmpty()) args["note"] = JsonPrimitive(note)
            }
        }
        if (f != Field.LITHOLOGY && !has(JsonObject(args), Field.LITHOLOGY)) {
            QuickCommand.lithology(rest, lithologies)?.takeIf { rest.indexOf(it.first) <= 2 && f != Field.NOTE }?.let { args["lithology"] = JsonPrimitive(it.first) }
        }
        return advance(JsonObject(args), asked)
    }

    /** The call that places the point, once [State.done]. */
    fun call(s: State): ActionCall = ActionCall("guided", ActionCatalog.PLACE_POINT, s.args)

    /** Said after placing: photos the project wants, or null. */
    fun photoReminder(): String? = when {
        rules.minPhotos == 1 -> "本工程要求每个点拍照，现在打开相机吗？"
        rules.minPhotos > 1 -> "本工程要求每个点至少拍 ${rules.minPhotos} 张照片，现在打开相机吗？"
        rules.askAll -> "要拍照吗？"
        else -> null
    }

    companion object {
        /** True when [args] already holds a title the user chose. */
        fun named(args: JsonObject) = !(args["title"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()

        fun withTitle(args: JsonObject, title: String) = buildJsonObject { args.forEach { (k, v) -> put(k, v) }; put("title", title) }
    }
}
