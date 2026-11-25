package org.geocrew.feature.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 批量改属性: pick an attribute (lithology, a form field or one already in
 * use), type or pick the value, and set it on every selected record. An
 * empty value removes the attribute from them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatchEditDialog(viewModel: RecordListViewModel, count: Int, onDismiss: () -> Unit) {
    val targets by viewModel.batchTargets.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf<String?>(null) }
    var value by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val target = targets.firstOrNull { it.key == key }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target == null) "给 $count 条记录改属性" else "「${target.label}」改成") },
        text = {
            if (target == null) {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(targets, key = { it.key }) { t ->
                        ListItem(
                            headlineContent = { Text(t.label) },
                            supportingContent = {
                                Text(
                                    when {
                                        t.form != null -> "表单" + if (t.form?.kinds.isNullOrEmpty()) "" else "（部分类型）"
                                        t.key == org.geocrew.core.model.Props.LITHOLOGY -> "内置"
                                        else -> "已有属性 · ${t.key}"
                                    },
                                )
                            },
                            modifier = Modifier.clickable { key = t.key; value = ""; error = null },
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value, { value = it.take(200); error = null },
                        singleLine = true, label = { Text("新的值（留空则去掉）") },
                        isError = error != null, supportingText = error?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (target.options.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            target.options.forEach { o -> AssistChip(onClick = { value = o; error = null }, label = { Text(o) }) }
                        }
                    }
                    Text("会改 $count 条所选记录里用得上这一项的，每条都作为一次修改同步给组员。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (target != null) {
                TextButton(onClick = {
                    error = viewModel.setOnSelected(target, value)
                    if (error == null) onDismiss()
                }) { Text(if (value.isBlank()) "去掉" else "修改") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (target != null) key = null else onDismiss() }) { Text(if (target != null) "换个属性" else "取消") }
        },
    )
}

/** 复制或移到其他工程: pick the project, then copy or move the selected records there. */
@Composable
internal fun CopyToProjectDialog(viewModel: RecordListViewModel, count: Int, onDismiss: () -> Unit) {
    val others by viewModel.otherProjects.collectAsStateWithLifecycle()
    var to by androidx.compose.runtime.remember { mutableStateOf<Pair<String, String>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(to?.let { "把 $count 条记录放到「${it.second}」" } ?: "放到哪个工程") },
        text = {
            val pick = to
            if (pick == null) {
                if (others.isEmpty()) Text("还没有别的工程，先在工程列表里新建一个。")
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(others, key = { it.first }) { p ->
                        ListItem(headlineContent = { Text(p.second) }, modifier = Modifier.clickable { to = p })
                    }
                }
            } else {
                Text(
                    "复制：两个工程各留一份，副本是新记录，从头记修改历史。\n" +
                        "移动：复制后删掉这里的原记录（工作组里组员那边也会删掉，原记录可在「最近删除」恢复）。\n" +
                        "照片、样品、表单答案都带过去；路线和剖面引用的点一起选上时会指向新工程里的副本；组长审核和任务指派不带。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            to?.let { p ->
                Column {
                    TextButton(onClick = { viewModel.copySelected(p, move = true); onDismiss() }) { Text("移动") }
                    TextButton(onClick = { viewModel.copySelected(p, move = false); onDismiss() }) { Text("复制") }
                }
            }
        },
        dismissButton = { TextButton(onClick = { if (to != null) to = null else onDismiss() }) { Text(if (to != null) "换个工程" else "取消") } },
    )
}
