package org.geocrew.feature.team

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Warning
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
import org.geocrew.core.ui.OutingBoard
import org.geocrew.core.ui.RollCallPrompt
import org.geocrew.core.ui.commentTime

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
    var alerting by rememberSaveable { mutableStateOf(false) }
    val sendingAlert by viewModel.sendingAlert.collectAsStateWithLifecycle()
    val rollCall by viewModel.rollCall.collectAsStateWithLifecycle()
    val rollBusy by viewModel.rollBusy.collectAsStateWithLifecycle()
    var calling by rememberSaveable { mutableStateOf(false) }
    var ending by rememberSaveable { mutableStateOf(false) }
    val outings by viewModel.outings.collectAsStateWithLifecycle()
    var goingOut by rememberSaveable { mutableStateOf(false) }

    // Stay at the newest message as messages arrive.
    LaunchedEffect(ui.items.size) {
        if (ui.items.isNotEmpty()) list.animateScrollToItem(ui.items.lastIndex)
        viewModel.markRead()
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
                actions = {
                    if (ui.linked) {
                        TextButton(onClick = { goingOut = true }, enabled = !rollBusy) { Text("外出") }
                    }
                    if (ui.linked && ui.isLeader) {
                        TextButton(onClick = { calling = true }, enabled = !rollBusy) { Text("点名") }
                    }
                    if (ui.linked) {
                        TextButton(onClick = { alerting = true }, enabled = !sendingAlert) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Text(" 紧急", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!ui.linked) {
                Text("当前工程没有加入工作组。", Modifier.padding(24.dp))
                return@Column
            }
            OutingBoard(
                outings,
                onBack = viewModel::checkIn,
                onShow = onShowPosition,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                busy = rollBusy,
            )
            rollCall?.let { rc ->
                RollCallPanel(
                    rc,
                    busy = rollBusy,
                    onOk = { viewModel.answerRollCall(true) },
                    onHelp = { viewModel.answerRollCall(false) },
                    onEnd = { ending = true },
                    onShowPosition = onShowPosition,
                    canEnd = rc.call.mine || ui.isLeader,
                )
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
                            c.rollCall -> "点名"
                            c.backBy != null -> "外出 · 预计 ${commentTime(c.backBy!!)} 回" + if (c.at != null) " · 📍" else ""
                            c.backFrom != null -> "报返回" + if (c.at != null) " · 📍" else ""
                            c.answerTo != null && c.at != null -> "回应点名 · 📍 点击查看位置"
                            c.answerTo != null -> "回应点名"
                            c.at != null -> "📍 点击查看位置"
                            else -> null
                        },
                        onClick = when {
                            c.featureId != null && row.featureTitle != null -> ({ onOpenFeature(c.featureId!!) })
                            c.at != null -> ({ onShowPosition(c.at!!) })
                            else -> null
                        },
                        onLongPress = if (c.mine || ui.isLeader) ({ confirm = c }) else null,
                        urgent = c.urgent,
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
                Text("只读成员不能发普通消息，但可以用右上角「外出」报备和「紧急」求助", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (alerting) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { alerting = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("发送紧急消息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("用于求助或要求集合。全组都会收到高优先级提醒，消息附上你现在的位置。")
                    androidx.compose.material3.OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("需要帮助，请到我的位置") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("没有信号时会先存在手机里，联网后立即发出。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.sendAlert(text); alerting = false }) {
                    Text("发送", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { alerting = false }) { Text("取消") } },
        )
    }

    if (goingOut) {
        CheckOutDialog(
            choices = remember { viewModel.returnChoices() },
            onDismiss = { goingOut = false },
            onConfirm = { where, at -> viewModel.checkOut(where, at); goingOut = false },
        )
    }

    if (calling) {
        var text by rememberSaveable { mutableStateOf(org.geocrew.core.sensors.RollCallActions.DEFAULT_CALL) }
        AlertDialog(
            onDismissRequest = { calling = false },
            title = { Text("发起点名") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("全组都会收到提醒，每人一键报平安或求助，回应附上各自的位置。你会看到谁还没回应。")
                    androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                    if (rollCall != null) Text("新的点名会替换现在这一次。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.startRollCall(text); calling = false }) { Text("发出") } },
            dismissButton = { TextButton(onClick = { calling = false }) { Text("取消") } },
        )
    }
    if (ending) {
        AlertDialog(
            onDismissRequest = { ending = false },
            title = { Text("结束点名？") },
            text = { Text("点名会从大家的屏幕上消失，已有的回应仍留在消息里。") },
            confirmButton = { TextButton(onClick = { viewModel.endRollCall(); ending = false }) { Text("结束") } },
            dismissButton = { TextButton(onClick = { ending = false }) { Text("取消") } },
        )
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

/**
 * The current roll call above the messages: the caller sees who has
 * answered and who hasn't; everyone else answers with one tap.
 */
@Composable
private fun RollCallPanel(
    rc: RollCallUi,
    busy: Boolean,
    onOk: () -> Unit,
    onHelp: () -> Unit,
    onEnd: () -> Unit,
    onShowPosition: (LngLat) -> Unit,
    canEnd: Boolean,
) {
    val c = rc.call
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!c.mine) {
            RollCallPrompt(
                caller = c.nickname,
                question = c.body,
                time = c.createdAt,
                onOk = onOk,
                onHelp = onHelp,
                answered = rc.mine?.let { "你已回应：${it.body} · " + if (it.pending) "待发送" else commentTime(it.createdAt) },
                busy = busy,
            )
        } else {
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("你在 ${commentTime(c.createdAt)} 点名" + if (c.pending) "（待发送）" else "", style = MaterialTheme.typography.labelMedium)
                    Text(c.body, style = MaterialTheme.typography.bodyLarge)
                    val roster = rc.roster
                    if (roster == null) {
                        Text("已有 ${rc.answers.size} 人回应；联网后显示还没回应的人", style = MaterialTheme.typography.bodySmall)
                        rc.answers.take(MAX_ROWS).forEach { a -> AnswerLine(a.nickname, a, onShowPosition) }
                    } else {
                        val done = roster.count { it.answer != null }
                        val help = roster.count { it.answer?.needsHelp == true }
                        Text(
                            "已回应 $done / ${roster.size}" + (if (help > 0) "，$help 人需要帮助" else "") + if (done == roster.size) "，全部到齐" else "",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (help > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        roster.take(MAX_ROWS).forEach { r -> AnswerLine(r.nickname, r.answer, onShowPosition) }
                        if (roster.size > MAX_ROWS) Text("还有 ${roster.size - MAX_ROWS} 人", style = MaterialTheme.typography.bodySmall)
                    }
                    if (canEnd) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onEnd, enabled = !busy) { Text("结束点名") }
                        }
                    }
                }
            }
        }
        if (!c.mine && canEnd) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEnd, enabled = !busy) { Text("结束这次点名") }
            }
        }
    }
}

@Composable
private fun AnswerLine(nickname: String, answer: CommentItem?, onShowPosition: (LngLat) -> Unit) {
    val status = when {
        answer == null -> "未回应"
        answer.needsHelp -> "需要帮助 · " + commentTime(answer.createdAt)
        else -> answer.body + " · " + commentTime(answer.createdAt)
    }
    val at = answer?.at
    Row(
        Modifier.fillMaxWidth().then(if (at != null) Modifier.clickable { onShowPosition(at) } else Modifier).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(nickname.ifBlank { "组员" }, style = MaterialTheme.typography.bodyMedium)
        Text(
            status + if (at != null) " 📍" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                answer == null -> MaterialTheme.colorScheme.onSurfaceVariant
                answer.needsHelp -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onTertiaryContainer
            },
        )
    }
}

/**
 * Checking out before going off alone: where to and when back. Past that
 * time with no word, the whole team is told.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CheckOutDialog(
    choices: List<org.geocrew.core.model.Outing.Choice>,
    onDismiss: () -> Unit,
    onConfirm: (String, java.time.Instant) -> Unit,
) {
    var where by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }
    val zone = java.time.ZoneId.systemDefault()
    val typedAt = typed.takeIf { it.isNotBlank() }?.let { org.geocrew.core.model.Outing.typed(it, java.time.Instant.now(), zone) }
    val at = typedAt ?: picked?.let(java.time.Instant::ofEpochMilli)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("外出报备") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("单独去一个地方前报一声。过了预计时间你还没报返回，全组都会收到提醒。")
                androidx.compose.material3.OutlinedTextField(
                    value = where,
                    onValueChange = { where = it },
                    label = { Text("去哪里、做什么") },
                    placeholder = { Text(org.geocrew.core.sensors.OutingActions.DEFAULT_OUT) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("预计返回", style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { c ->
                        FilterChip(
                            selected = typedAt == null && picked == c.at.toEpochMilli(),
                            onClick = { picked = c.at.toEpochMilli(); typed = "" },
                            label = { Text(c.label + " · " + commentTime(c.at)) },
                        )
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("或输入时间，如 17:30") },
                    isError = typed.isNotBlank() && typedAt == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("没有信号时会先存在手机里，联网后发出。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { at?.let { onConfirm(where, it) } }, enabled = at != null) { Text("报备") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private const val MAX_ROWS = 12
