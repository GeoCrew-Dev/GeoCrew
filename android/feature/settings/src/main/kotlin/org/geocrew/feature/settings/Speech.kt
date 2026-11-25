package org.geocrew.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.geocrew.core.speech.OfflineSpeech
import org.geocrew.core.speech.VoiceNoteTranscriber
import javax.inject.Inject

@HiltViewModel
class SpeechViewModel @Inject constructor(
    private val speech: OfflineSpeech,
    private val transcriber: VoiceNoteTranscriber,
) : ViewModel() {
    val state = speech.state
    val progress = transcriber.progress
    var modelUrl: String
        get() = speech.modelUrl
        set(v) { speech.modelUrl = v }

    private var job: Job? = null

    fun download() { if (job?.isActive != true) job = viewModelScope.launch { runCatching { speech.download() } } }
    fun cancel() { job?.cancel() }
    fun delete() = viewModelScope.launch { speech.delete() }
    suspend fun pending(): Int = transcriber.pendingInCurrent()
    fun transcribeAll() = viewModelScope.launch { runCatching { transcriber.transcribeCurrent() } }
    fun clearProgress() = transcriber.clearProgress()
}

private fun mb(bytes: Long) = "%.0f MB".format(bytes / 1_048_576.0)

/**
 * Optional offline Chinese speech model: downloaded on demand (never shipped
 * in the APK), then dictation works with no signal and voice notes can be
 * written down in one go.
 */
@Composable
internal fun SpeechSection(viewModel: SpeechViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    var editingUrl by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pending by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state, progress?.finished) {
        if (state is OfflineSpeech.ModelState.Ready) pending = runCatching { viewModel.pending() }.getOrDefault(0)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "下载一个约 40 MB 的中文识别模型后，没有信号也能口述描述，录音也能转成文字。常用词和本工程的岩性名会用来校正同音字。",
            style = MaterialTheme.typography.bodySmall,
        )
        when (val s = state) {
            OfflineSpeech.ModelState.Missing -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::download) { Text("下载模型") }
                TextButton(onClick = { editingUrl = true }) { Text("下载地址") }
            }
            is OfflineSpeech.ModelState.Downloading -> {
                Text("正在下载 " + mb(s.bytes) + (if (s.total > 0) " / " + mb(s.total) else ""))
                if (s.total > 0) {
                    LinearProgressIndicator(progress = { s.bytes.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TextButton(onClick = viewModel::cancel) { Text("取消") }
            }
            OfflineSpeech.ModelState.Unpacking -> {
                Text("正在解压…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is OfflineSpeech.ModelState.Ready -> {
                Text("模型已就绪（${mb(s.bytes)}），口述会优先用它。")
                val p = progress
                when {
                    p != null && !p.finished -> {
                        Text("正在转写录音 ${p.done} / ${p.total}")
                        LinearProgressIndicator(progress = { if (p.total == 0) 1f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        p?.let {
                            Text(
                                if (it.total == 0) "当前工程没有要转写的录音" else "已转写 ${it.done - it.failed} 段" + if (it.failed > 0) "，${it.failed} 段失败" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.clearProgress(); viewModel.transcribeAll() }, enabled = pending != 0) {
                                Text(if (pending > 0) "转写当前工程 $pending 段录音" else "当前工程录音都已转写")
                            }
                            TextButton(onClick = { confirmDelete = true }) { Text("删除模型") }
                        }
                        Text("转写结果放在每段录音下面，录音原件保留；要不要加进描述由你决定。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            is OfflineSpeech.ModelState.Failed -> {
                Text("下载失败：${s.message}", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::download) { Text("重试") }
                    TextButton(onClick = { editingUrl = true }) { Text("下载地址") }
                }
            }
        }
    }
    if (editingUrl) {
        var url by remember { mutableStateOf(viewModel.modelUrl) }
        AlertDialog(
            onDismissRequest = { editingUrl = false },
            title = { Text("模型下载地址") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("默认是 Vosk 官方的中文小模型（zip）。国内下载慢时可以换成自己放的镜像，必须是 https。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(url, { url = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.modelUrl = url; editingUrl = false }, enabled = url.startsWith("https://")) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { url = OfflineSpeech.DEFAULT_URL }) { Text("恢复默认") }
                    TextButton(onClick = { editingUrl = false }) { Text("取消") }
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除离线语音模型？") },
            text = { Text("已转写的文字和录音都保留，以后要用再下载。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
