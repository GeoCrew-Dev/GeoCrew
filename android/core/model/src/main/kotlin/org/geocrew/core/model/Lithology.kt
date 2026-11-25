package org.geocrew.core.model

/**
 * Rock names to offer while typing a lithology. Names already used in the
 * project come first, most used first, so a team's own vocabulary (and
 * spelling) wins; then common rocks by the usual three families.
 */
object Lithology {
    data class Group(val name: String, val rocks: List<String>)

    val groups: List<Group> = listOf(
        Group(
            "岩浆岩",
            listOf(
                "花岗岩", "二长花岗岩", "花岗闪长岩", "石英闪长岩", "闪长岩", "辉长岩", "辉绿岩", "橄榄岩", "辉石岩", "正长岩",
                "伟晶岩", "细晶岩", "花岗斑岩", "闪长玢岩", "流纹岩", "英安岩", "安山岩", "玄武岩", "粗面岩",
                "凝灰岩", "火山角砾岩", "集块岩", "熔结凝灰岩",
            ),
        ),
        Group(
            "沉积岩",
            listOf(
                "砾岩", "角砾岩", "砂岩", "石英砂岩", "长石砂岩", "岩屑砂岩", "粉砂岩", "泥岩", "页岩", "黏土岩",
                "石灰岩", "泥灰岩", "白云岩", "鲕粒灰岩", "生物碎屑灰岩", "硅质岩", "燧石", "煤", "膏岩",
            ),
        ),
        Group(
            "变质岩",
            listOf(
                "板岩", "千枚岩", "片岩", "云母片岩", "绿片岩", "片麻岩", "花岗片麻岩", "变粒岩", "浅粒岩", "石英岩",
                "大理岩", "角闪岩", "斜长角闪岩", "麻粒岩", "榴辉岩", "混合岩", "糜棱岩", "碎裂岩", "角岩", "矽卡岩", "蛇纹岩",
            ),
        ),
        Group("松散沉积", listOf("黄土", "砂土", "黏土", "砾石层", "残积物", "坡积物", "冲积物")),
    )

    val common: List<String> = groups.flatMap { it.rocks }

    /**
     * Up to [limit] names for what has been typed: an empty [query] gives
     * the project's most used names, then common ones; otherwise names
     * containing the query, those starting with it first.
     */
    fun suggest(query: String, used: List<String>, limit: Int = 8): List<String> {
        val q = query.trim()
        val ranked = used.map { it.trim() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        val pool = (ranked + common).distinct().filter { it != q }
        if (q.isEmpty()) return pool.take(limit)
        val hits = pool.filter { it.contains(q, ignoreCase = true) }
        return (hits.filter { it.startsWith(q, ignoreCase = true) } + hits).distinct().take(limit)
    }
}
