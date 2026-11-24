package org.geocrew.feature.team

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.data.team.TeamRepository
import org.geocrew.core.data.team.TeamStatus
import org.geocrew.core.network.Invite
import org.geocrew.core.network.Member
import org.geocrew.core.network.RealtimeState
import org.geocrew.core.ui.InfoRow
import org.geocrew.core.ui.SectionHeader
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(onBack: () -> Unit, viewModel: TeamViewModel = hiltViewModel()) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val invite by viewModel.invite.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val incoming by viewModel.incoming.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工作组") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (project?.team != null) {
                        IconButton(onClick = viewModel::refresh, enabled = !busy) { Icon(Icons.Default.Refresh, "刷新成员") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy || status.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            val p = project ?: return@Column
            if (p.team == null) {
                ConnectPanel(
                    projectName = p.name,
                    defaultNickname = settings?.nickname.orEmpty(),
                    busy = busy,
                    incoming = incoming,
                    onIncomingUsed = viewModel::consumeIncoming,
                    onCreate = viewModel::create,
                    onJoin = viewModel::join,
                )
            } else {
                incoming?.let { inv ->
                    InviteElsewhere(
                        invite = inv,
                        projectName = p.name,
                        busy = busy,
                        onNewProject = { viewModel.newProjectFor(inv) },
                        onIgnore = viewModel::consumeIncoming,
                    )
                }
                LinkedPanel(
                    status = status,
                    myId = p.team!!.memberId,
                    isLeader = p.team!!.role == TeamRepository.ROLE_LEADER,
                    serverUrl = p.team!!.serverUrl,
                    share = settings?.sharePosition == true,
                    invite = invite,
                    members = members,
                    busy = busy,
                    onSync = viewModel::syncNow,
                    onShare = viewModel::setShare,
                    onResetInvite = viewModel::resetInvite,
                    onSetRole = viewModel::setRole,
                    onRevoke = viewModel::revoke,
                    onLeave = viewModel::leave,
                )
            }
        }
    }
}

@Composable
private fun ConnectPanel(
    projectName: String,
    defaultNickname: String,
    busy: Boolean,
    incoming: Invite?,
    onIncomingUsed: () -> Unit,
    onCreate: (ConnectForm) -> Unit,
    onJoin: (ConnectForm) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var server by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable(defaultNickname) { mutableStateOf(defaultNickname) }
    var share by rememberSaveable { mutableStateOf(true) }
    var fromLink by rememberSaveable { mutableStateOf(false) }
    var pasteFailed by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    fun fill(inv: Invite, viaLink: Boolean) {
        tab = 0; server = inv.server; value = inv.code; fromLink = viaLink; pasteFailed = false
    }
    LaunchedEffect(incoming) {
        incoming?.let { fill(it, viaLink = true); onIncomingUsed() }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0; value = "" }, text = { Text("加入工作组") })
                Tab(selected = tab == 1, onClick = { tab = 1; value = "" }, text = { Text("新建工作组") })
            }
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (tab == 0) "向组长要服务器地址和邀请码。加入后，工程「$projectName」会与组内共享，已有的记录也会上传。"
                    else "在你自己部署的同勘服务器上建立工作组，你将成为组长，并得到一个邀请码发给组员。工程「$projectName」会成为组内共享的工程。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            val inv = clipboard.getText()?.text?.let(Invite::find)
                            if (inv != null) fill(inv, viaLink = false) else pasteFailed = true
                        }) {
                            Icon(Icons.Default.ContentPaste, null)
                            Spacer(Modifier.width(6.dp))
                            Text("粘贴收到的邀请")
                        }
                        if (pasteFailed) Text("剪贴板里没有邀请", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (fromLink && tab == 0) {
                    Text(
                        "已按邀请链接填好。加入前请确认服务器地址是组长给你的，工程数据会上传到这台服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                OutlinedTextField(
                    value = server, onValueChange = { server = it.trim(); fromLink = false },
                    label = { Text("服务器地址") }, placeholder = { Text("geocrew.example.com") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = { Text("不写 http:// 时默认使用 https") },
                )
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text(if (tab == 0) "邀请码" else "工作组名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = if (tab == 0) KeyboardOptions(capitalization = KeyboardCapitalization.Characters) else KeyboardOptions.Default,
                )
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it },
                    label = { Text("我的昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = share, onCheckedChange = { share = it })
                    Text("打开地图或记录轨迹时向组员共享我的位置")
                }
                val form = ConnectForm(server, value.trim(), nickname.trim(), share)
                Button(
                    onClick = { if (tab == 0) onJoin(form) else onCreate(form) },
                    enabled = !busy && server.isNotBlank() && value.isNotBlank() && nickname.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (tab == 0) "加入" else "创建") }
            }
        }
    }
}

@Composable
private fun LinkedPanel(
    status: TeamStatus,
    myId: String,
    isLeader: Boolean,
    serverUrl: String,
    share: Boolean,
    invite: String?,
    members: List<Member>,
    busy: Boolean,
    onSync: () -> Unit,
    onShare: (Boolean) -> Unit,
    onResetInvite: () -> Unit,
    onSetRole: (Member, String) -> Unit,
    onRevoke: (Member) -> Unit,
    onLeave: () -> Unit,
) {
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf<Member?>(null) }
    var showQr by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val shareable = invite?.let { Invite.of(serverUrl, it) }
    val workspace = status.workspaceName.ifBlank { "工作组" }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(status.workspaceName.ifBlank { "工作组" }, style = MaterialTheme.typography.titleLarge)
                    InfoRow("我的角色", TeamViewModel.roleLabel(status.role))
                    InfoRow("服务器", serverUrl)
                    InfoRow("实时连接", connectionLabel(status.connection))
                    InfoRow("上次同步", status.lastSync?.let { TIME.format(it) } ?: "尚未同步")
                    if (status.pending > 0) InfoRow("待上传", "${status.pending} 条")
                    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (status.needsRejoin) {
                        Text(
                            "这台设备的组员身份已失效，可能已被组长移出。可退出后用新的邀请码重新加入，本地数据不会丢失。",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onSync, enabled = !busy && !status.syncing && !status.needsRejoin) {
                            Icon(Icons.Default.Sync, null)
                            Spacer(Modifier.width(6.dp))
                            Text("立即同步")
                        }
                        OutlinedButton(onClick = { confirmLeave = true }) { Text("退出工作组") }
                    }
                }
            }
        }
        item {
            ListItem(
                headlineContent = { Text("共享我的位置") },
                supportingContent = { Text("打开地图或记录轨迹时（包括锁屏后），组员能看到你的实时位置") },
                trailingContent = { Switch(checked = share, onCheckedChange = onShare) },
            )
        }
        if (isLeader) {
            item { SectionHeader("邀请码") }
            item {
                ListItem(
                    headlineContent = {
                        Text(invite ?: "—", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                    },
                    supportingContent = { Text("分享给组员，或让组员扫二维码。更换后旧码立即失效。") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { shareable?.let { shareInvite(context, workspace, it) } }, enabled = shareable != null) {
                                Icon(Icons.Default.Share, "分享邀请")
                            }
                            IconButton(onClick = { showQr = true }, enabled = shareable != null) {
                                Icon(Icons.Default.QrCode2, "显示二维码")
                            }
                            IconButton(onClick = { invite?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = invite != null) {
                                Icon(Icons.Default.ContentCopy, "复制邀请码")
                            }
                            TextButton(onClick = onResetInvite, enabled = !busy) { Text("更换") }
                        }
                    },
                )
            }
        }
        item { SectionHeader("成员（${members.count { !it.revoked }}）") }
        items(members.filterNot { it.revoked }, key = { it.id }) { m ->
            MemberRow(
                member = m,
                online = m.id in status.online,
                isMe = m.id == myId,
                canManage = isLeader && m.id != myId,
                onSetRole = { onSetRole(m, it) },
                onRevoke = { confirmRevoke = m },
            )
        }
    }

    if (showQr && shareable != null) InviteQrDialog(workspace, shareable) { showQr = false }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("退出工作组？") },
            text = { Text("本机不再与组内同步，已有记录保留在本地。之后可用邀请码重新加入。") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("取消") } },
        )
    }
    confirmRevoke?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text("移出 ${m.nickname}？") },
            text = { Text("对方的设备将立即失去访问权限，已上传的数据保留在组内。") },
            confirmButton = { TextButton(onClick = { confirmRevoke = null; onRevoke(m) }) { Text("移出") } },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MemberRow(
    member: Member,
    online: Boolean,
    isMe: Boolean,
    canManage: Boolean,
    onSetRole: (String) -> Unit,
    onRevoke: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = {
            Box(
                Modifier.size(10.dp).background(if (online) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        },
        headlineContent = { Text(member.nickname + if (isMe) "（我）" else "") },
        supportingContent = { Text(TeamViewModel.roleLabel(member.role) + if (online) " · 在线" else "") },
        trailingContent = if (canManage) {
            {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "管理") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(TeamRepository.ROLE_LEADER, TeamRepository.ROLE_MEMBER, TeamRepository.ROLE_READONLY)
                            .filter { it != member.role }
                            .forEach { role ->
                                DropdownMenuItem(
                                    text = { Text("设为${TeamViewModel.roleLabel(role)}") },
                                    onClick = { menu = false; onSetRole(role) },
                                )
                            }
                        DropdownMenuItem(
                            text = { Text("移出工作组", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onRevoke() },
                        )
                    }
                }
            }
        } else null,
    )
}

private fun connectionLabel(s: RealtimeState) = when (s) {
    RealtimeState.Idle -> "未连接"
    RealtimeState.Connecting -> "连接中…"
    RealtimeState.Connected -> "已连接"
    RealtimeState.Waiting -> "网络中断，稍后重连"
    RealtimeState.Unauthorized -> "身份失效"
}

private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** An invite arrived while this project is already linked to a workspace. */
@Composable
private fun InviteElsewhere(invite: Invite, projectName: String, busy: Boolean, onNewProject: () -> Unit, onIgnore: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("收到新的邀请", style = MaterialTheme.typography.titleMedium)
            Text(
                "服务器 ${invite.server}，邀请码 ${invite.code}。工程「$projectName」已连接工作组，可以新建一个工程来加入。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onNewProject, enabled = !busy) { Text("新建工程并加入") }
                TextButton(onClick = onIgnore) { Text("忽略") }
            }
        }
    }
}
