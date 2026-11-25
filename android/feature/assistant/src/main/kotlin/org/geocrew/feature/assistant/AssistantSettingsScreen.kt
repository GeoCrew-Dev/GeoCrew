package org.geocrew.feature.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.geocrew.core.actions.LogEntry
import org.geocrew.core.model.RecordRules
import org.geocrew.core.model.action.ActionMode
import org.geocrew.core.model.action.ActionRisk
import org.geocrew.core.model.action.ActionSource

/** The assistant's connection, what it may do, voice prompts, the project's record rules and the action log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(onBack: () -> Unit, viewModel: AssistantSettingsViewModel = hiltViewModel()) {
    val v by viewModel.values.collectAsStateWithLifecycle()
    val side by viewModel.project.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val note by viewModel.note.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(note) { note?.let { snack.showSnackbar(it); viewModel.consumeNote() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("助手设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Section("模型接口") }
            item { Endpoint(v, viewModel) }
            item { Section("AI 可以做什么") }
            item {
                Text(
                    "常用口令（定点、轨迹、产状、拍照）在手机上直接执行，算你自己的操作。下面只管 AI 理解后提出的操作。",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
            items(ActionRisk.entries.toList(), key = { it.id }) { risk ->
                Modes(risk, if (risk == ActionRisk.READ) ActionMode.ALLOW else v.permissions.mode(risk), teamDenied = side?.aiDeny?.contains(risk) == true) {
                    viewModel.setMode(risk, it)
                }
            }
            side?.takeIf { it.inTeam && it.canEdit }?.let { s -> item { TeamLimits(s.aiDeny, viewModel::saveTeamLimits) } }
            item { Section("语音提示") }
            item { Voice(v, viewModel) }
            side?.let { s -> item { Section("本工程记录规则") }; item { Rules(s, viewModel::saveRules) } }
            item {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Section("操作日志", Modifier.weight(1f))
                    if (log.isNotEmpty()) TextButton(onClick = viewModel::clearLog) { Text("清空") }
                }
            }
            if (log.isEmpty()) item { Text("还没有操作", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            val groups = log.asReversed().groupBy { it.requestId }
            groups.forEach { (req, entries) ->
                item(key = "req-$req") { LogGroup(entries, onUndo = viewModel::undo, onUndoAll = { viewModel.undoRequest(req) }) }
            }
        }
    }
}

@Composable
private fun Section(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Endpoint(v: AssistantConfig.Values, vm: AssistantSettingsViewModel) {
    var base by rememberSaveable(v.baseUrl) { mutableStateOf(v.baseUrl) }
    var model by rememberSaveable(v.model) { mutableStateOf(v.model) }
    var key by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "填任何兼容 OpenAI 的接口（官方、国内大模型平台或自己部署的）。Key 只加密存在本机，不随工程或工作组传出。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(base, { base = it.trim() }, Modifier.fillMaxWidth(), label = { Text("接口地址") }, singleLine = true)
        OutlinedTextField(model, { model = it.trim() }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true)
        OutlinedTextField(
            key, { key = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (v.hasKey) "API Key（已保存，留空不改）" else "API Key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveEndpoint(base, model, key.ifBlank { null }); key = "" }) { Text("保存") }
            OutlinedButton(onClick = { vm.test() }, enabled = v.hasKey) { Text("测试连接") }
            if (v.hasKey) TextButton(onClick = vm::clearKey) { Text("删除 Key") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Modes(risk: ActionRisk, mode: ActionMode, teamDenied: Boolean, onPick: (ActionMode) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(risk.label, style = MaterialTheme.typography.bodyMedium)
        if (teamDenied) Text("组长已设置本工作组不允许 AI 做", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m, onClick = { onPick(m) }, label = { Text(m.label) },
                    enabled = risk != ActionRisk.READ && !teamDenied,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamLimits(denied: Set<ActionRisk>, onSave: (Set<ActionRisk>) -> Unit) {
    var pick by remember(denied) { mutableStateOf(denied) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("组长：本工作组不允许 AI 做的事", style = MaterialTheme.typography.bodyMedium)
        Text("对全组每个人的助手生效，组员自己改不了。", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionRisk.entries.filter { it != ActionRisk.READ }.forEach { r ->
                FilterChip(selected = r in pick, onClick = { pick = if (r in pick) pick - r else pick + r }, label = { Text(r.label) })
            }
        }
        if (pick != denied) Button(onClick = { onSave(pick) }) { Text("同步给全组") }
    }
}

@Composable
private fun Voice(v: AssistantConfig.Values, vm: AssistantSettingsViewModel) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Toggle("朗读追问和结果", v.speak, vm::setSpeak)
        Text("语速 ${"%.1f".format(v.rate)} 倍", style = MaterialTheme.typography.bodySmall)
        Slider(v.rate, vm::setRate, valueRange = 0.6f..2.0f, steps = 13, enabled = v.speak)
        Toggle("免手持：读完问题自动听你回答", v.handsFree, vm::setHandsFree, enabled = v.speak)
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(on, onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Rules(side: AssistantSettingsViewModel.ProjectSide, onSave: (RecordRules) -> Unit) {
    var r by remember(side.rules) { mutableStateOf(side.rules) }
    var prefix by remember(side.rules) { mutableStateOf(side.rules.pointPrefix.orEmpty()) }
    val edit = side.canEdit
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (side.inTeam) (if (edit) "改后同步给全组；缺项的记录进「待核对」，语音定点时也按这里追问。" else "组长设置，全组共用。")
            else "缺项的记录进「待核对」，语音定点时也按这里追问。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            prefix, { prefix = it }, Modifier.fillMaxWidth(), enabled = edit, singleLine = true,
            label = { Text("地质点编号前缀（如 D、2025D，留空按本机编号）") },
        )
        Text("地质点必填", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordRules.Item.entries.forEach { i ->
                FilterChip(
                    selected = r.requires(i), enabled = edit, label = { Text(i.label) },
                    onClick = { r = r.copy(required = if (r.requires(i)) r.required - i.id else r.required + i.id) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("至少拍照", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { r = r.copy(minPhotos = (r.minPhotos - 1).coerceAtLeast(0)) }, enabled = edit) { Text("－") }
            Text(if (r.minPhotos == 0) "不要求" else "${r.minPhotos} 张")
            TextButton(onClick = { r = r.copy(minPhotos = (r.minPhotos + 1).coerceAtMost(20)) }, enabled = edit) { Text("＋") }
        }
        Toggle("语音定点时确认点号", r.askName, { r = r.copy(askName = it) }, enabled = edit)
        Toggle("非必填项也问一遍（可说「跳过」）", r.askAll, { r = r.copy(askAll = it) }, enabled = edit)
        val changed = r.copy(pointPrefix = prefix).clean() != side.rules.clean()
        if (edit && changed) Button(onClick = { onSave(r.copy(pointPrefix = prefix)) }) { Text(if (side.inTeam) "同步给全组" else "保存") }
    }
}

private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun LogGroup(entries: List<LogEntry>, onUndo: (LogEntry) -> Unit, onUndoAll: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        val first = entries.first()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                TIME.format(first.at) + "  " + if (first.source == ActionSource.AI) "AI" else "口令",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            )
            if (entries.count { it.undoable } > 1) TextButton(onClick = { ask = true }) { Text("全部撤销") }
        }
        entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.bodyMedium)
                    Text(e.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                when {
                    e.undone -> Text("已撤销", style = MaterialTheme.typography.labelSmall)
                    e.undoable -> TextButton(onClick = { onUndo(e) }) { Text("撤销") }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("撤销这一次的所有操作？") },
        text = { Text("会按相反顺序恢复这 ${entries.count { it.undoable }} 项改动前的样子。") },
        confirmButton = { TextButton(onClick = { ask = false; onUndoAll() }) { Text("撤销") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("取消") } },
    )
}
