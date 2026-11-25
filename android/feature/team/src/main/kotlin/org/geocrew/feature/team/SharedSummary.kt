package org.geocrew.feature.team

import org.geocrew.core.model.ProjectConfig

/**
 * What the workspace shares with every phone (protocol v0 §2.2), one line
 * per kind, so members know what follows the leader and leaders know what
 * their edits reach. Empty kinds are left out.
 */
internal fun sharedSummary(c: ProjectConfig): List<Pair<String, String>> = buildList {
    if (c.fields.isNotEmpty()) add("记录表单" to c.fields.joinToString("、") { it.label }.let(::short) + "（${c.fields.size} 项）")
    if (c.legend.active) add("地图图例" to "按「${c.legend.key}」着色，${c.legend.entries.size} 种")
    c.localGrid?.let { add("本地坐标系" to it.name) }
    if (c.phrases.isNotEmpty()) add("常用词" to c.phrases.joinToString("、") { "${it.name} ${it.items.size} 个" }.let(::short))
    val overlays = c.overlays.filter { it.shared }
    if (overlays.isNotEmpty()) add("叠加图" to overlays.joinToString("、") { it.name }.let(::short))
    if (c.teamBasemaps.isNotEmpty()) add("底图" to c.teamBasemaps.joinToString("、") { it.name }.let(::short))
    val references = c.referenceLayers.filter { it.shared }
    if (references.isNotEmpty()) add("参考图层" to references.joinToString("、") { it.name }.let(::short))
    if (c.recordRules.active) {
        val r = c.recordRules
        add(
            "记录规则" to listOfNotNull(
                r.pointPrefix?.takeIf { it.isNotBlank() }?.let { "点号 $it…" },
                r.items.takeIf { it.isNotEmpty() }?.let { items -> "必填" + items.joinToString("、") { it.label } },
                r.minPhotos.takeIf { it > 0 }?.let { "至少 $it 张照片" },
            ).joinToString("，").let(::short),
        )
    }
    val denied = c.teamAiDeny.mapNotNull { org.geocrew.core.model.action.ActionRisk.of(it) }
    if (denied.isNotEmpty()) add("助手限制" to "不许助手" + denied.joinToString("、") { it.label }.let(::short))
}

private fun short(s: String) = if (s.length <= 40) s else s.take(39) + "…"
