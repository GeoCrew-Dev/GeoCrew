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
    val overlays = c.overlays.filter { it.shared }
    if (overlays.isNotEmpty()) add("叠加图" to overlays.joinToString("、") { it.name }.let(::short))
    if (c.teamBasemaps.isNotEmpty()) add("底图" to c.teamBasemaps.joinToString("、") { it.name }.let(::short))
}

private fun short(s: String) = if (s.length <= 40) s else s.take(39) + "…"
