package org.geocrew.feature.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.AttributeValues

/**
 * 整理属性值: pick a text attribute, see every value it takes with counts,
 * merge spellings that are probably one value and rename or remove a value
 * on all records at once, so legends and exports group cleanly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttributeTidyDialog(viewModel: RecordListViewModel, onDismiss: () -> Unit) {
    val keys by viewModel.attributeKeys.collectAsStateWithLifecycle()
    val key by viewModel.tidyKey.collectAsStateWithLifecycle()
    val values by viewModel.tidyValues.collectAsStateWithLifecycle()
    val note by viewModel.tidyNote.collectAsStateWithLifecycle()
    val canEdit by viewModel.canEdit.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AttributeValues.Value?>(null) }
    val close = { viewModel.tidyKey.value = null; viewModel.consumeTidyNote(); onDismiss() }
    val label = keys.firstOrNull { it.key == key }?.label ?: key.orEmpty()

    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (key == null) "整理属性值" else "整理「$label」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                note?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                if (key == null) {
                    Text("选一个属性，看它的所有取值。写法不一致（多了空格、大小写、全角）的值会让图例和统计分成两类，可以在这里一次改好。", style = MaterialTheme.typography.bodySmall)
                    if (keys.isEmpty()) Text("这个工程的记录还没有文字属性。")
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(keys, key = { it.key }) { k ->
                            ListItem(
                                headlineContent = { Text(k.label) },
                                supportingContent = { Text("${k.records} 条记录，${k.values} 种值" + if (k.label != k.key) " · ${k.key}" else "") },
                                modifier = Modifier.clickable { viewModel.consumeTidyNote(); viewModel.tidyKey.value = k.key },
                            )
                        }
                    }
                } else {
                    val groups = remember(values) { AttributeValues.similar(values) }
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        if (groups.isNotEmpty()) {
                            item { Text("可能是同一个值", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 4.dp)) }
                            items(groups, key = { g -> "g:" + g.first().value }) { g ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(g.joinToString("、") { "「${it.value}」${it.count}" }, style = MaterialTheme.typography.bodyMedium)
                                    if (canEdit) {
                                        TextButton(onClick = { viewModel.renameValue(key!!, g.drop(1).map { it.value }.toSet(), g.first().value) }) {
                                            Text("统一成「${g.first().value}」")
                                        }
                                    }
                                }
                            }
                            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                        }
                        item { Text("全部取值（点一下修改）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 4.dp)) }
                        items(values, key = { "v:" + it.value }) { v ->
                            ListItem(
                                headlineContent = { Text(v.value) },
                                trailingContent = { Text("${v.count} 条") },
                                modifier = Modifier.clickable(enabled = canEdit) { editing = v },
                            )
                        }
                    }
                    if (!canEdit) Text("只读成员只能查看", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("完成") } },
        dismissButton = {
            if (key != null) TextButton(onClick = { viewModel.consumeTidyNote(); viewModel.tidyKey.value = null }) { Text("换个属性") }
        },
    )

    editing?.let { v ->
        var to by rememberSaveable(v.value) { mutableStateOf(v.value) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("修改「${v.value}」") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${v.count} 条记录的「$label」会一起改，组内同步后组员也会看到。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(to, { to = it.take(200) }, singleLine = true, label = { Text("改成") }, modifier = Modifier.fillMaxWidth())
                    val others = values.filter { it.value != v.value }.take(12)
                    if (others.isNotEmpty()) {
                        Text("或并入已有的值", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            others.forEach { o -> AssistChip(onClick = { to = o.value }, label = { Text(o.value) }) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { editing = null; viewModel.renameValue(key ?: return@TextButton, setOf(v.value), to) },
                    enabled = to.isNotBlank() && to.trim() != v.value,
                ) { Text("修改") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { editing = null; viewModel.renameValue(key ?: return@TextButton, setOf(v.value), "") }) {
                        Text("从这些记录里去掉", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }
}
