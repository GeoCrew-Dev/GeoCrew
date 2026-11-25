package org.geocrew.feature.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.geocrew.core.speech.OfflineSpeech

/**
 * The assistant: type or talk. Common commands run on the phone at once;
 * with a model configured anything else is understood too. Changes show
 * as ✓ lines that can be undone; risky ones wait on one card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, viewModel: AssistantViewModel = hiltViewModel()) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val confirm by viewModel.confirm.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var hearing by remember { mutableStateOf<String?>(null) }
    var listenJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, confirm) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1 + if (confirm != null) 1 else 0) }
    DisposableEffect(Unit) { onDispose { viewModel.stopSpeaking() } }

    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) scope.launch { viewModel.send(viewModel.fixSpeech(said)) }
    }

    fun listen() {
        viewModel.stopSpeaking()
        if (viewModel.offlineReady) {
            if (listenJob?.isActive == true) { listenJob?.cancel(); hearing = null; return }
            listenJob = scope.launch {
                hearing = ""
                try {
                    viewModel.offlineListen().collect { h ->
                        when (h) {
                            is OfflineSpeech.Heard.Partial -> hearing = h.text
                            is OfflineSpeech.Heard.Final -> if (h.text.isNotBlank()) {
                                hearing = null
                                viewModel.send(viewModel.fixSpeech(h.text))
                                listenJob?.cancel()
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    hearing = null
                }
            }
        } else {
            runCatching {
                system.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"),
                )
            }
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen() }
    fun micTap() {
        if (!viewModel.offlineReady || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Hands-free: after a question is read out, listen for the answer.
    LaunchedEffect(Unit) { viewModel.listen.collect { if (listenJob?.isActive != true) micTap() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("助手") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { viewModel.clear() }) { Icon(Icons.Default.DeleteSweep, "清空对话") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "助手设置") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Box(Modifier.padding(top = 8.dp)) }
                items(lines, key = { it.id }) { line -> Line(line, onUndo = { viewModel.undo(line) }) }
                confirm?.let { c -> item(key = "confirm") { ConfirmCard(c, onRun = { viewModel.approve(false) }, onAlways = { viewModel.approve(true) }, onCancel = viewModel::reject) } }
            }
            hearing?.let { Text(if (it.isEmpty()) "请说话…" else it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary) }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::micTap) {
                    Icon(if (settings.handsFree) Icons.Default.Headset else Icons.Default.Mic, "说话", tint = if (hearing != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    input, { input = it }, Modifier.weight(1f), placeholder = { Text("定点、开始轨迹、产状 120∠35…") }, maxLines = 4,
                )
                IconButton(onClick = { viewModel.send(input); input = "" }, enabled = input.isNotBlank() && !busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送")
                }
            }
        }
    }
}

@Composable
private fun Line(line: ChatLine, onUndo: () -> Unit) {
    val mine = line.role == ChatLine.Role.USER
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        val bg = when (line.role) {
            ChatLine.Role.USER -> MaterialTheme.colorScheme.primaryContainer
            ChatLine.Role.DONE -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Column(Modifier.widthIn(max = 320.dp).background(bg, RoundedCornerShape(12.dp)).padding(10.dp)) {
            Text(line.text, style = MaterialTheme.typography.bodyMedium)
            if (line.role == ChatLine.Role.DONE && line.requestId != null) {
                if (line.undone) Text("已撤销", style = MaterialTheme.typography.labelSmall)
                else TextButton(onClick = onUndo) { Text("撤销") }
            }
        }
    }
}

@Composable
private fun ConfirmCard(c: Confirmation, onRun: () -> Unit, onAlways: () -> Unit, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("这些操作要你确认", style = MaterialTheme.typography.titleSmall)
            c.items.forEach { p ->
                Text("• " + p.spec.title + describe(p.call.args), style = MaterialTheme.typography.bodyMedium)
                Text("  " + p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun) { Text(if (c.items.size > 1) "全部执行" else "执行") }
                TextButton(onClick = onAlways) { Text("本次对话都允许") }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

/** A short, readable form of a call's arguments for the confirmation card. */
private fun describe(args: kotlinx.serialization.json.JsonObject): String {
    if (args.isEmpty()) return ""
    val parts = args.entries.take(4).map { (k, v) ->
        val s = when (v) {
            is kotlinx.serialization.json.JsonArray -> "${v.size} 项"
            is kotlinx.serialization.json.JsonPrimitive -> v.content.take(60)
            else -> "…"
        }
        "$k=$s"
    }
    return "（" + parts.joinToString("，") + "）"
}
