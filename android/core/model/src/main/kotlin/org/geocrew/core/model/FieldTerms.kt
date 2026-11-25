package org.geocrew.core.model

import kotlinx.serialization.Serializable

/** A named list of words offered while writing a description, e.g. "颜色". */
@Serializable
data class PhraseGroup(val name: String, val items: List<String>)

/**
 * Words geologists write again and again in point descriptions, offered as
 * one-tap chips. A project adds its own groups (its formations, its usual
 * phrases); in a team project the leader keeps them and they travel in the
 * workspace settings (protocol v0 §2.2, key `phrases`).
 */
object FieldTerms {
    val builtIn: List<PhraseGroup> = listOf(
        PhraseGroup(
            "颜色",
            listOf(
                "灰白色", "浅灰色", "灰色", "深灰色", "灰黑色", "黑色", "肉红色", "浅肉红色", "紫红色", "砖红色",
                "黄褐色", "褐色", "灰绿色", "暗绿色", "浅黄色", "杂色",
            ),
        ),
        PhraseGroup(
            "结构",
            listOf(
                "粗粒", "中粒", "细粒", "中细粒", "不等粒", "斑状结构", "似斑状结构", "隐晶质", "玻璃质", "花岗结构",
                "碎屑结构", "泥质结构", "砂状结构", "鲕粒结构", "生物碎屑结构", "变晶结构", "鳞片变晶结构", "碎裂结构",
            ),
        ),
        PhraseGroup(
            "构造",
            listOf(
                "块状构造", "层状构造", "薄层状", "中厚层状", "厚层状", "水平层理", "交错层理", "粒序层理", "波痕", "泥裂",
                "气孔构造", "杏仁构造", "流纹构造", "片理", "板理", "片麻状构造", "条带状构造", "眼球状构造", "节理发育", "褶皱",
            ),
        ),
        PhraseGroup(
            "矿物",
            listOf(
                "石英", "长石", "斜长石", "钾长石", "黑云母", "白云母", "角闪石", "辉石", "橄榄石", "方解石",
                "白云石", "绿泥石", "绿帘石", "石榴子石", "黄铁矿", "磁铁矿", "褐铁矿", "孔雀石",
            ),
        ),
        PhraseGroup("风化", listOf("新鲜", "微风化", "中等风化", "强风化", "全风化", "风化面呈", "新鲜面呈")),
        PhraseGroup(
            "接触与产出",
            listOf(
                "整合接触", "平行不整合", "角度不整合", "断层接触", "侵入接触", "渐变过渡", "露头良好", "零星出露",
                "转石", "第四系覆盖", "可见化石", "含矿化",
            ),
        ),
    )

    /** Longest word and most words per group a project may keep, so the settings stay small. */
    const val MAX_ITEM = 40
    const val MAX_ITEMS = 100
    const val MAX_GROUPS = 20

    /** [own] cleaned: blank and over-long words dropped, duplicates removed, sizes capped. */
    fun clean(own: List<PhraseGroup>): List<PhraseGroup> =
        own.map { g -> PhraseGroup(g.name.trim().take(20), g.items.map(String::trim).filter { it.isNotEmpty() && it.length <= MAX_ITEM }.distinct().take(MAX_ITEMS)) }
            .filter { it.name.isNotEmpty() && it.items.isNotEmpty() }
            .distinctBy { it.name }
            .take(MAX_GROUPS)

    /** The project's groups first, then the built-in ones; a project group with a built-in name adds to it. */
    fun all(own: List<PhraseGroup>): List<PhraseGroup> {
        val mine = clean(own)
        val builtInNames = builtIn.map { it.name }.toSet()
        val extra = mine.filter { it.name !in builtInNames }
        val merged = builtIn.map { b -> mine.firstOrNull { it.name == b.name }?.let { b.copy(items = (it.items + b.items).distinct()) } ?: b }
        return extra + merged
    }

    /** [own] with [word] added to group [group] (created at the front if new). */
    fun add(own: List<PhraseGroup>, group: String, word: String): List<PhraseGroup> {
        val g = group.trim().ifEmpty { PROJECT_GROUP }
        val w = word.trim()
        if (w.isEmpty()) return clean(own)
        val list = clean(own)
        val i = list.indexOfFirst { it.name == g }
        val next = if (i < 0) listOf(PhraseGroup(g, listOf(w))) + list
        else list.toMutableList().also { it[i] = it[i].copy(items = (it[i].items + w).distinct()) }
        return clean(next)
    }

    /** [own] without [word] in [group]; a group left empty goes away. */
    fun remove(own: List<PhraseGroup>, group: String, word: String): List<PhraseGroup> =
        clean(own.map { if (it.name == group) it.copy(items = it.items - word) else it })

    const val PROJECT_GROUP = "本工程"

    /**
     * Puts [word] into [text] at [cursor], with a Chinese comma in between
     * when it would otherwise run into the word before. Returns the new text
     * and where the cursor goes.
     */
    fun insert(text: String, cursor: Int, word: String): Pair<String, Int> {
        val at = cursor.coerceIn(0, text.length)
        val before = text.substring(0, at)
        val after = text.substring(at)
        val last = before.lastOrNull()
        val sep = if (last == null || last.isWhitespace() || last in SEPARATORS) "" else "，"
        val out = before + sep + word + after
        return out to (before.length + sep.length + word.length)
    }

    private const val SEPARATORS = "，,。.；;：:、（(“\"《"
}
