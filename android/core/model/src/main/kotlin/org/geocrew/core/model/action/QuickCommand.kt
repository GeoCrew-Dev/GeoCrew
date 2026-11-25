package org.geocrew.core.model.action

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.model.Lithology

/**
 * Chinese numerals as speech recognisers write them: "一百二十" → 120,
 * "三十五" → 35, "四十五点五" → 45.5.
 */
object ChineseNumber {
    private val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    private val units = mapOf('十' to 10, '百' to 100, '千' to 1000)
    private val run = Regex("[零〇一二两三四五六七八九十百千]+(?:点[零〇一二三四五六七八九]+)?")

    fun parse(s: String): Double? {
        if (s.isEmpty()) return null
        val (whole, frac) = s.split('点').let { it[0] to it.getOrNull(1) }
        var total = 0
        var section = 0
        var number = -1
        if (whole.isEmpty()) return null
        if (whole.none { it in units }) {
            // Digit by digit: "一二零" → 120.
            total = whole.fold(0) { acc, c -> acc * 10 + (digits[c] ?: return null) }
        } else {
            for (c in whole) {
                val d = digits[c]
                val u = units[c]
                when {
                    d != null -> number = d
                    u != null -> { section += (if (number < 0) 1 else number) * u; number = -1 }
                    else -> return null
                }
            }
            total = section + number.coerceAtLeast(0)
        }
        val f = frac?.map { digits[it] ?: return null }?.joinToString("")
        return if (f.isNullOrEmpty()) total.toDouble() else "$total.$f".toDouble()
    }

    /**
     * Writes numerals as digits where they read as a measurement: runs with
     * 十/百/千 or of two or more digits, or followed by 度. "一条石英脉"
     * keeps its 一.
     */
    fun normalize(text: String): String = run.replace(text) { m ->
        val v = m.value
        val next = text.getOrNull(m.range.last + 1)
        val measure = v.any { it in units } || v.length >= 2 || next == '度' || next == '°'
        if (!measure) v else parse(v)?.let { n -> if (n % 1.0 == 0.0) n.toLong().toString() else n.toString() } ?: v
    }
}

/**
 * The local fast path: common spoken or typed commands are understood by
 * rule and run at once, offline, as if the user had tapped. Anything the
 * rules do not fully understand returns null and goes to the assistant.
 */
object QuickCommand {
    private val punct = Regex("[\\s，。、！？,.!?;；：:]+")
    private val startTrack = Regex("^(开始|开启|打开)(记录)?轨迹$")
    private val stopTrack = Regex("^(停止|结束|关闭|暂停)(记录)?轨迹$")
    private val photo = Regex("^(拍照|拍一?张(照片)?|照相)$")
    private val undo = Regex("^(撤销|撤回|撤销上一步|撤回上一步)$")
    private val today = Regex("^(?:今天|今日)(?:一共|总共)?(?:做了|干了|记了|完成了|采了)?(?:多少|几个|啥|什么|怎么样|进度|小结|汇总|情况)(?:了|啊|呢)?$|^(?:今日|今天的?)?(?:小结|日报|进度)$")
    private val referenceHere = Regex("^(?:这里|这儿|这|我这里|我这儿|当前位置)(?:是|在)(?:什么|哪个|哪一?个)(?:地层|岩性|单元|填图单元|图斑|岩体|矿权)(?:里)?(?:啊|呢)?$|^(?:我在)(?:什么|哪个|哪一?个)(?:地层|单元|图斑|矿权)(?:里)?$|^查(?:一下)?参考图层$")
    private val point = Regex("^(?:在这里|这里)?(?:打|定|记|加|新建)(?:一?个)?(?:地质)?点")
    private val attitudeWords = Regex("^(?:产状|记产状|测得?产状)")
    private val noteLead = Regex("^(?:描述|备注|记录|写上|说明)(?:是|为)?")
    // On the record at hand: "岩性 花岗岩", "补充 见石英脉", "采样 S12".
    private val lithologyOnly = Regex("^岩性(?:是|为|改成|改为)?(\\D{2,30})$")
    private val noteAppend = Regex("^(?:补充|追加|备注|描述(?:加上|补充|里加))(?:一下|一句)?(?:是|为)?(.{1,200})$")
    private val sample = Regex("^(?:采|取|登记|加)了?(?:一?个)?(薄片|光片|化学分析|岩矿|光谱|拣块|重砂)?样(?:品)?(?:号|编号)?(?:是|为)?([A-Za-z0-9-]{1,20})?$")

    // 120∠35, 120/35, 120度35度, 倾向120倾角35, 产状120 35.
    private val attitude = listOf(
        Regex("倾向(\\d{1,3}(?:\\.\\d+)?)度?倾角(\\d{1,2}(?:\\.\\d+)?)度?"),
        Regex("(\\d{1,3}(?:\\.\\d+)?)[°度]?\\s*[∠/／]\\s*(\\d{1,2}(?:\\.\\d+)?)[°度]?"),
        Regex("(\\d{1,3}(?:\\.\\d+)?)度(\\d{1,2}(?:\\.\\d+)?)度"),
        Regex("(?:产状)(\\d{1,3}(?:\\.\\d+)?)\\s+(\\d{1,2}(?:\\.\\d+)?)"),
    )

    /** Words that mean more than one simple step; those go to the assistant. */
    private val tooMuch = listOf("然后", "并且", "再", "发给", "发送", "删除", "删掉", "移到", "导出", "全部", "所有", "每个", "如果")

    data class Attitude(val dipDirection: Double, val dip: Double)

    private val loose = Regex("(\\d{1,3}(?:\\.\\d+)?)度?\\s+(\\d{1,2}(?:\\.\\d+)?)度?")

    /**
     * Finds an attitude in [text]; returns it and the text without it. With
     * [loose], two numbers apart ("一百二十 三十五") also count, which is
     * right only when the user was just asked for an attitude.
     */
    fun attitude(text: String, loose: Boolean = false): Pair<Attitude, String>? {
        val t = ChineseNumber.normalize(text)
        for (r in if (loose) attitude + this.loose else attitude) {
            val m = r.find(t) ?: continue
            val dd = m.groupValues[1].toDouble()
            val dip = m.groupValues[2].toDouble()
            if (dd > 360 || dip > 90) continue
            return Attitude(dd % 360, dip) to (t.removeRange(m.range)).trim()
        }
        return null
    }

    /** The longest known rock name in [text], and the text without it. */
    fun lithology(text: String, extra: Collection<String> = emptyList()): Pair<String, String>? {
        val names = (Lithology.common + extra).filter { it.length >= 2 }.sortedByDescending { it.length }
        val hit = names.firstOrNull { text.contains(it) } ?: return null
        return hit to text.replaceFirst(hit, "")
    }

    fun parse(raw: String, lithologies: Collection<String> = emptyList()): List<ActionCall>? {
        val text = punct.replace(raw.trim(), "").trim()
        if (text.isEmpty() || text.length > 120) return null
        fun call(name: String, args: kotlinx.serialization.json.JsonObject = buildJsonObject {}) = listOf(ActionCall("quick-1", name, args))
        when {
            startTrack.matches(text) -> return call(ActionCatalog.START_TRACK)
            stopTrack.matches(text) -> return call(ActionCatalog.STOP_TRACK)
            photo.matches(text) -> return call(ActionCatalog.TAKE_PHOTO)
            undo.matches(text) -> return call(ActionCatalog.UNDO_LAST)
            today.matches(text) -> return call(ActionCatalog.DAY_SUMMARY)
            referenceHere.matches(text) -> return call(ActionCatalog.REFERENCE_HERE)
        }
        if (tooMuch.any { text.contains(it) }) return null

        point.find(text)?.let { m ->
            var rest = text.substring(m.range.last + 1)
            val args = buildJsonObject {
                attitude(rest)?.let { (a, left) -> put("dip_direction", a.dipDirection); put("dip", a.dip); rest = left }
                rest = rest.removePrefix("岩性").removePrefix("是")
                lithology(rest, lithologies)?.let { (name, left) ->
                    // Only when the rock name leads; "描述里有花岗岩脉" is a description.
                    if (rest.indexOf(name) <= 2) { put("lithology", name); rest = left }
                }
                rest = rest.removePrefix("，").trim()
                val note = noteLead.replace(rest, "").trim()
                if (note.isNotEmpty()) put("note", JsonPrimitive(note))
            }
            return call(ActionCatalog.PLACE_POINT, args)
        }

        fun current(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("id", ActionCatalog.CURRENT)
            build()
        }
        lithologyOnly.matchEntire(text)?.let { m ->
            return call(ActionCatalog.UPDATE_RECORD, current { put("lithology", m.groupValues[1]) })
        }
        noteAppend.matchEntire(text)?.let { m ->
            return call(ActionCatalog.UPDATE_RECORD, current { put("note_append", m.groupValues[1]) })
        }
        sample.matchEntire(ChineseNumber.normalize(text))?.let { m ->
            return call(
                ActionCatalog.ADD_SAMPLE,
                current {
                    m.groupValues[1].takeIf { it.isNotEmpty() }?.let { put("kind", it) }
                    m.groupValues[2].takeIf { it.isNotEmpty() }?.let { put("no", it.uppercase()) }
                },
            )
        }

        // An attitude on its own goes on the record at hand.
        val bare = attitudeWords.replace(text, "")
        attitude(bare)?.let { (a, left) ->
            if (left.isNotEmpty()) return null
            return call(
                ActionCatalog.UPDATE_RECORD,
                buildJsonObject { put("id", ActionCatalog.CURRENT); put("dip_direction", a.dipDirection); put("dip", a.dip) },
            )
        }
        return null
    }
}
