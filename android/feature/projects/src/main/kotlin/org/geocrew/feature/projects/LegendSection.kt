package org.geocrew.feature.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.FieldType
import org.geocrew.core.model.Legend
import org.geocrew.core.model.LegendEntry
import org.geocrew.core.model.Props

/** Attributes a legend can colour by: lithology, then the form's text and choice fields. */
internal fun legendKeys(fields: List<FieldTemplate>): List<Pair<String, String>> =
    (listOf(Props.LITHOLOGY to "岩性") + fields.filter { it.type != FieldType.NUMBER }.map { it.key to it.label })
        .distinctBy { it.first }

/** Values worth a legend entry for [key]: those records use, then a choice field's options. */
internal fun legendSuggestions(key: String, fields: List<FieldTemplate>, used: Map<String, List<String>>): List<String> =
    (used[key].orEmpty() + fields.firstOrNull { it.key == key }?.options.orEmpty()).distinct()

/**
 * Settings for the map legend: which attribute colours the records and the
 * colour of each value. Picking an attribute fills in the values records
 * already use, with conventional rock colours where the name suggests one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LegendSection(
    legend: Legend,
    fields: List<FieldTemplate>,
    used: Map<String, List<String>>,
    access: FormAccess,
    onChange: (Legend) -> Unit,
) {
    val readOnly = access == FormAccess.TEAM_MEMBER
    var picking by remember { mutableStateOf<LegendEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val keys = legendKeys(fields).let { k ->
        // A key the form no longer has still shows, so the legend can be switched off.
        if (legend.key.isNotBlank() && k.none { it.first == legend.key }) k + (legend.key to legend.key) else k
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (access) {
                FormAccess.LOCAL -> "按一个属性给地图上的记录着色，像地质草图一样一眼分出岩性或地层。自带颜色的记录（分析结果、导入文件）保持原色。"
                FormAccess.TEAM_LEADER -> "按一个属性给地图上的记录着色。你是组长，图例会同步给工作组的所有成员，大家看到同一套颜色。"
                FormAccess.TEAM_MEMBER -> "图例由组长统一维护，组内地图用同一套颜色。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = legend.key.isBlank(), enabled = !readOnly,
                onClick = { onChange(Legend()) }, label = { Text("不着色") },
            )
            keys.forEach { (key, label) ->
                FilterChip(
                    selected = legend.key == key, enabled = !readOnly,
                    onClick = {
                        if (legend.key != key) onChange(Legend(key).withValues(legendSuggestions(key, fields, used)))
                    },
                    label = { Text(label) },
                )
            }
        }
    }
    if (legend.key.isBlank()) return

    legend.entries.forEach { e ->
        ListItem(
            leadingContent = { Swatch(e.color) },
            headlineContent = { Text(e.value) },
            trailingContent = if (readOnly) null else ({
                IconButton(onClick = { onChange(legend.remove(e.value)) }) { Icon(Icons.Default.Delete, "删除 ${e.value}") }
            }),
            modifier = if (readOnly) Modifier else Modifier.clickable { picking = e },
        )
    }
    if (legend.entries.isEmpty()) {
        Text(
            "还没有取值。记录里填了这个属性后，可以在这里一键补全。",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (!readOnly) {
        val missing = legendSuggestions(legend.key, fields, used).filter { v -> legend.entries.none { it.value.equals(v, ignoreCase = true) } }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) { Text("添加取值") }
            if (missing.isNotEmpty()) {
                OutlinedButton(onClick = { onChange(legend.withValues(missing)) }) { Text("补全记录里的 ${missing.size} 个取值") }
            }
        }
    }

    picking?.let { e ->
        ColorDialog(
            title = e.value,
            current = e.color,
            onDismiss = { picking = null },
            onPick = { c -> onChange(legend.put(e.copy(color = c))); picking = null },
        )
    }
    if (adding) {
        AddValueDialog(
            taken = legend.entries.map { it.color },
            onDismiss = { adding = false },
            onAdd = { entry -> onChange(legend.put(entry)); adding = false },
        )
    }
}

internal fun swatchColor(hex: String): Color =
    if (Legend.isColor(hex)) Color(("FF" + hex.drop(1)).toLong(16)) else Color.Gray

@Composable
private fun Swatch(hex: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(if (onClick != null) 36.dp else 24.dp)
            .background(swatchColor(hex), if (onClick != null) CircleShape else RoundedCornerShape(4.dp))
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                if (onClick != null) CircleShape else RoundedCornerShape(4.dp),
            )
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorGrid(current: String, onPick: (String) -> Unit) {
    val colors = (Legend.palette + listOf("#F48FB1", "#CE93D8", "#66BB6A", "#2E7D32", "#FFEE58", "#64B5F6", "#9575CD", "#424242", "#EEEEEE"))
        .distinct()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c -> Swatch(c, selected = c.equals(current, ignoreCase = true)) { onPick(c) } }
    }
}

@Composable
private fun ColorDialog(title: String, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var hex by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorGrid(hex) { hex = it }
                OutlinedTextField(
                    value = hex, onValueChange = { hex = it.trim() }, singleLine = true,
                    label = { Text("颜色（#RRGGBB）") }, isError = !Legend.isColor(hex),
                    leadingIcon = { Swatch(hex) },
                )
            }
        },
        confirmButton = { TextButton(enabled = Legend.isColor(hex), onClick = { onPick(hex) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AddValueDialog(taken: List<String>, onDismiss: () -> Unit, onAdd: (LegendEntry) -> Unit) {
    var value by remember { mutableStateOf("") }
    var hex by remember { mutableStateOf<String?>(null) }
    // Until a colour is picked, follow the name (e.g. 砂岩 → yellow).
    val shown = hex ?: Legend.suggestColor(value.trim(), taken)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加取值") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("取值，例如 花岗岩") })
                ColorGrid(shown) { hex = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Swatch(shown); Text(shown) }
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onAdd(LegendEntry(value.trim(), shown)) }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
