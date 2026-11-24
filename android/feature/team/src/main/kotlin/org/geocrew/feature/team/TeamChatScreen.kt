package org.geocrew.feature.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.data.team.CommentItem
import org.geocrew.core.model.LngLat
import org.geocrew.core.ui.CommentBubble
import org.geocrew.core.ui.CommentComposer

/**
 * The team's message stream: messages to everyone plus comments on records,
 * newest at the bottom. A message can carry the map centre, so "come here"
 * is one tap away for whoever reads it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamChatScreen(
    onBack: () -> Unit,
    onOpenFeature: (String) -> Unit,
    onShowPosition: (LngLat) -> Unit,
    viewModel: TeamChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var confirm by remember { mutableStateOf<CommentItem?>(null) }
    var attachCenter by rememberSaveable { mutableStateOf(false) }

    // Stay at the newest message as messages arrive.
    LaunchedEffect(ui.items.size) {
        if (ui.items.isNotEmpty()) list.animateScrollToItem(ui.items.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("组内消息")
                        if (ui.workspaceName.isNotBlank()) Text(ui.workspaceName, style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!ui.linked) {
                Text("当前工程没有加入工作组。", Modifier.padding(24.dp))
                return@Column
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                if (ui.items.isEmpty()) {
                    item {
                        Text(
                            "还没有消息。这里会显示发给全组的消息，以及大家在各条记录下的讨论。",
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(ui.items, key = { it.item.id }) { row ->
                    val c = row.item
                    CommentBubble(
                        nickname = c.nickname, body = c.body, time = c.createdAt, mine = c.mine, pending = c.pending,
                        context = when {
                            c.featureId != null -> "关于 " + (row.featureTitle ?: "已删除的记录")
                            c.at != null -> "📍 点击查看位置"
                            else -> null
                        },
                        onClick = when {
                            c.featureId != null && row.featureTitle != null -> ({ onOpenFeature(c.featureId!!) })
                            c.at != null -> ({ onShowPosition(c.at!!) })
                            else -> null
                        },
                        onLongPress = if (c.mine || ui.isLeader) ({ confirm = c }) else null,
                    )
                }
            }
            if (ui.canPost) {
                if (ui.mapCenter != null) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = attachCenter, onClick = { attachCenter = !attachCenter }, label = { Text("附上地图中心位置") })
                    }
                }
                CommentComposer(
                    onSend = { viewModel.send(it, if (attachCenter) ui.mapCenter else null); attachCenter = false },
                    placeholder = "发给全组…",
                )
            } else {
                Text("只读成员不能发言", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("删除这条消息？") },
            text = { Text(if (c.pending) "还没有发出，删除后不会再发送。" else "全组都会看不到这条消息。") },
            confirmButton = { TextButton(onClick = { viewModel.delete(c); confirm = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::consumeError,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::consumeError) { Text("好") } },
        )
    }
}
