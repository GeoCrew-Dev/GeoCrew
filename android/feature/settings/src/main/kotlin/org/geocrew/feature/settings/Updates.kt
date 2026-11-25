package org.geocrew.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import org.geocrew.core.data.repo.AppUpdater
import org.geocrew.core.data.repo.UpdateState
import org.geocrew.core.model.AppRelease
import org.geocrew.core.model.AppReleases
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(private val updater: AppUpdater) : ViewModel() {
    val state = updater.state
    val autoCheck = updater.autoCheck
    val available get() = updater.available
    val currentVersion get() = updater.currentVersion

    fun checkOnStart() = updater.checkOnStart()
    fun check() = updater.check()
    fun download(r: AppRelease) = updater.download(r)
    fun install(s: UpdateState.Ready) = updater.install(s.file)
    fun skip(r: AppRelease) = updater.skip(r)
    fun dismiss() = updater.dismiss()
    fun cancel() = updater.cancel()
    fun setAutoCheck(on: Boolean) = updater.setAutoCheck(on)
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * The new-version dialog, shown over any screen. Put it once at the root:
 * it checks at start (once a day, if enabled) and also shows what a manual
 * check from the settings found.
 */
@Composable
fun UpdatePrompt(viewModel: UpdateViewModel = hiltViewModel()) {
    if (!viewModel.available) return
    LaunchedEffect(Unit) { viewModel.checkOnStart() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var needPermission by remember { mutableStateOf(false) }

    when (val s = state) {
        is UpdateState.Available -> ReleaseDialog(
            release = s.release,
            current = viewModel.currentVersion,
            confirm = { TextButton(onClick = { viewModel.download(s.release) }) { Text("下载并安装") } },
            dismiss = {
                Row {
                    TextButton(onClick = { viewModel.skip(s.release) }) { Text("跳过这个版本") }
                    TextButton(onClick = viewModel::dismiss) { Text("以后再说") }
                }
            },
            onDismiss = viewModel::dismiss,
        )
        is UpdateState.Downloading -> ReleaseDialog(
            release = s.release,
            current = viewModel.currentVersion,
            progress = if (s.total > 0) s.done.toFloat() / s.total else null,
            footer = "正在下载 ${s.done / MB} / ${if (s.total > 0) "${s.total / MB} MB" else "…"}",
            confirm = {},
            dismiss = { TextButton(onClick = viewModel::cancel) { Text("取消") } },
            onDismiss = {},
        )
        is UpdateState.Ready -> ReleaseDialog(
            release = s.release,
            current = viewModel.currentVersion,
            footer = if (needPermission) {
                "请在刚打开的设置里允许同勘安装应用，返回后再点「安装」。"
            } else {
                "已下载并核对校验值和签名。点「安装」后由系统确认安装，记录和设置都会保留。"
            },
            confirm = { TextButton(onClick = { needPermission = !viewModel.install(s) }) { Text("安装") } },
            dismiss = { TextButton(onClick = viewModel::dismiss) { Text("稍后") } },
            onDismiss = viewModel::dismiss,
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("没有更新") },
            text = { Text(s.message) },
            confirmButton = {
                val url = s.release?.url
                if (!url.isNullOrBlank()) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        viewModel.dismiss()
                    }) { Text("打开发布页") }
                } else {
                    TextButton(onClick = viewModel::dismiss) { Text("知道了") }
                }
            },
            dismissButton = { if (s.release?.url.isNullOrBlank().not()) TextButton(onClick = viewModel::dismiss) { Text("关闭") } },
        )
        else -> {}
    }
}

@Composable
private fun ReleaseDialog(
    release: AppRelease,
    current: String,
    confirm: @Composable () -> Unit,
    dismiss: @Composable () -> Unit,
    onDismiss: () -> Unit,
    footer: String? = null,
    progress: Float? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新版本 ${release.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "当前 $current" + (release.publishedAt?.let { " · 发布于 " + dateFormat.format(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                val notes = AppReleases.shortNotes(release.notes)
                if (notes.isNotBlank()) {
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    )
                }
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else if (footer?.startsWith("正在下载") == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                footer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = confirm,
        dismissButton = dismiss,
    )
}

/** 设置 › 版本更新. */
@Composable
internal fun UpdateSection(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val auto by viewModel.autoCheck.collectAsStateWithLifecycle()
    Column {
        ListItem(
            headlineContent = { Text("当前版本 ${viewModel.currentVersion.ifBlank { "开发版" }}") },
            supportingContent = {
                Text(
                    when {
                        !viewModel.available -> "这个安装包由应用商店或自行编译提供，请从安装来源更新。"
                        state is UpdateState.Checking -> "正在检查…"
                        state is UpdateState.UpToDate -> (state as UpdateState.UpToDate).latest
                            ?.let { "已是最新版本（最新发布 $it）" } ?: "还没有发布过正式版本"
                        else -> "从 GitHub Releases 检查新版本，下载后核对校验值和签名再交给系统安装。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
        if (viewModel.available) {
            ListItem(
                headlineContent = { Text("启动时检查更新") },
                supportingContent = { Text("每天最多一次，没有网络时不提示", style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Switch(checked = auto, onCheckedChange = viewModel::setAutoCheck) },
            )
            OutlinedButton(
                onClick = viewModel::check,
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("检查更新") }
        }
    }
}

private const val MB = 1024 * 1024
