package org.geocrew.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.ui.KindUi
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val deletedAt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * 最近删除: records deleted in this project, by this phone or a team mate,
 * with a button to bring each back. A deletion keeps the record's content,
 * so it returns with its photos, samples and form answers, and the return
 * syncs to the team like any edit.
 */
@Composable
internal fun RecycleBinDialog(viewModel: RecordListViewModel, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val items by viewModel.deleted.collectAsStateWithLifecycle()
    val canEdit by viewModel.canEdit.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("最近删除") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "删掉的记录先留在这里，恢复后照片、样品和表单答案都还在，并同步给组员。" +
                        if (canEdit) "" else "只读成员只能查看。",
                    style = MaterialTheme.typography.bodySmall,
                )
                note?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                if (items.isEmpty()) Text("没有删除的记录。")
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(items, key = { it.id }) { f ->
                        val name = f.title?.takeIf { it.isNotBlank() } ?: KindUi.label(f.kind)
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = {
                                Text(KindUi.label(f.kind) + " · " + deletedAt.format(f.sync.updatedAt) + " 删除" + if (f.geometry == null) " · 无位置" else "")
                            },
                            trailingContent = {
                                if (canEdit) {
                                    TextButton(onClick = {
                                        viewModel.undelete(listOf(f.id))
                                        note = "已恢复「$name」"
                                    }) { Text("恢复") }
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            if (canEdit && items.size > 1) {
                TextButton(onClick = {
                    val n = items.size
                    viewModel.undelete(items.map { it.id })
                    note = "已恢复 $n 条记录"
                }) { Text("全部恢复") }
            }
        },
    )
}
