package org.geocrew.feature.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.foundation.layout.fillMaxWidth
import org.geocrew.core.formats.ProjectPackage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.team.TeamRepository
import org.geocrew.core.model.Project
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import javax.inject.Inject

data class ProjectRow(val project: Project, val count: Int, val current: Boolean)

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val packages: org.geocrew.core.data.repo.ProjectPackages,
    private val projects: ProjectRepository,
    features: FeatureRepository,
    private val team: TeamRepository,
    private val storage: org.geocrew.core.data.repo.StorageMaintenance,
    private val reference: org.geocrew.core.data.repo.ReferenceLayers,
) : ViewModel() {
    /** Null until loaded, so the empty state does not flash. */
    val rows: StateFlow<List<ProjectRow>?> = combine(projects.projects, features.observeCounts(), projects.currentId) { list, counts, cur ->
        list.map { ProjectRow(it, counts[it.id] ?: 0, it.id == cur) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Creates a project, taking over [templateId]'s form and map settings (not its records) when given. */
    fun create(name: String, templateId: String?, then: () -> Unit) = viewModelScope.launch {
        val config = templateId?.let { id -> rows.value?.firstOrNull { it.project.id == id }?.project?.config?.template() }
        val p = if (config != null) projects.create(name, config = config) else projects.create(name)
        projects.open(p.id)
        then()
    }

    fun open(id: String, then: () -> Unit) = viewModelScope.launch {
        projects.open(id)
        then()
    }
    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun consumeMessage() { _message.value = null }

    fun exportTo(id: String, uri: android.net.Uri) = work {
        val r = context.contentResolver.openOutputStream(uri, "wt")?.use { packages.export(id, it) }
        _message.value = if (r == null) "无法写入所选位置" else "已导出 ${r.features} 条记录、${r.photos} 张照片" + (if (r.audio > 0) "、${r.audio} 段录音" else "")
    }

    fun importFrom(uri: android.net.Uri, then: () -> Unit) = work {
        val r = context.contentResolver.openInputStream(uri)?.use { packages.import(it) }
        if (r == null) {
            _message.value = "打不开所选文件"
            return@work
        }
        _message.value = "已导入「${r.project.name}」：${r.features} 条记录、${r.photos} 张照片" + (if (r.audio > 0) "、${r.audio} 段录音" else "") +
            (if (r.warnings.isNotEmpty()) "（${r.warnings.size} 条提示：${r.warnings.first()}）" else "")
        projects.open(r.project.id)
        then()
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: org.geocrew.core.formats.FormatException) {
                _message.value = e.message
            } catch (e: java.io.IOException) {
                _message.value = "读写失败：${e.message}"
            } catch (e: IllegalStateException) {
                _message.value = e.message
            } finally {
                _busy.value = false
            }
        }
    }

    fun rename(id: String, name: String) = viewModelScope.launch { projects.rename(id, name) }

    fun delete(id: String) = viewModelScope.launch {
        team.leave(id)
        projects.delete(id)
        reference.removeAll(id)
        storage.sweepPhotos()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * The start screen: every project on this device, most recently opened
 * first. Opening one leads to its map; everything else in the app works
 * inside the open project.
 */
fun ProjectsScreen(
    onOpened: () -> Unit,
    onOpenSettings: () -> Unit,
    onProjectSettings: (String) -> Unit = {},
    viewModel: ProjectsViewModel = hiltViewModel()) {
    val loaded by viewModel.rows.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    var exporting by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(ProjectPackage.MIME),
    ) { uri -> val id = exporting; exporting = null; if (uri != null && id != null) viewModel.exportTo(id, uri) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importFrom(uri, onOpened) }
    var naming by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var deleting by remember { mutableStateOf<ProjectRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同勘 · 工程") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) {
                        Icon(Icons.Default.FileOpen, "导入工程包")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "设置") }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { naming = null to "" },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新建工程") },
            )
        },
    ) { padding ->
        if (busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
        val rows = loaded ?: return@Scaffold
        if (rows.isEmpty()) {
            Empty(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(rows, key = { it.project.id }) { r ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    leadingContent = {
                        Icon(
                            if (r.current) Icons.Default.CheckCircle else if (r.project.team != null) Icons.Default.Groups else Icons.Default.Folder,
                            null,
                            tint = if (r.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = { Text(r.project.name) },
                    supportingContent = {
                        Text(
                            "${r.count} 条记录" +
                                (r.project.team?.let { " · 工作组「${it.workspaceName}」" } ?: "") +
                                " · " + opened(r.project.openedAt),
                        )
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; naming = r.project.id to r.project.name })
                                DropdownMenuItem(text = { Text("工程设置与表单") }, onClick = { menu = false; onProjectSettings(r.project.id) })
                                DropdownMenuItem(text = { Text("导出工程包") }, onClick = {
                                    menu = false
                                    exporting = r.project.id
                                    exportLauncher.launch("${r.project.name}.${ProjectPackage.EXTENSION}")
                                })
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; deleting = r },
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { viewModel.open(r.project.id, onOpened) },
                )
            }
        }
    }

    naming?.let { (id, initial) ->
        var name by remember(id) { mutableStateOf(initial) }
        var template by remember(id) { mutableStateOf<ProjectRow?>(null) }
        var picking by remember(id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (id == null) "新建工程" else "重命名工程") },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                    val others = loaded.orEmpty()
                    if (id == null && others.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { picking = true }) {
                                Text(template?.let { "沿用「${it.project.name}」的设置" } ?: "沿用某个工程的设置…")
                            }
                            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                                DropdownMenuItem(text = { Text("不沿用，从空白开始") }, onClick = { template = null; picking = false })
                                others.forEach { r ->
                                    DropdownMenuItem(text = { Text(r.project.name) }, onClick = { template = r; picking = false })
                                }
                            }
                        }
                        template?.let { t ->
                            val c = t.project.config
                            Text(
                                "带过来：" + listOfNotNull(
                                    "表单 ${c.fields.size} 项".takeIf { c.fields.isNotEmpty() },
                                    "图例".takeIf { c.legend != org.geocrew.core.model.Legend() },
                                    "常用词".takeIf { c.phrases.isNotEmpty() },
                                    "编号规则",
                                    "底图和地图位置",
                                    "本地坐标系".takeIf { c.localGrid != null },
                                ).joinToString("、") + "。记录、照片和叠加图不带。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (id == null) viewModel.create(name, template?.project?.id, onOpened) else viewModel.rename(id, name)
                    naming = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("取消") } },
        )
    }

    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${r.project.name}」？") },
            text = {
                Text(
                    "将从本机删除 ${r.count} 条记录，无法恢复。" +
                        if (r.project.team != null) "本机会先退出工作组，组内服务器上的数据不受影响。" else "如需保留，请先导出工程包。",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.delete(r.project.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Empty(modifier: Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("还没有工程", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "一个工程装着一次野外工作的全部内容：地图与底图、定点和轨迹、照片和产状，以及它共享的工作组。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun opened(at: Instant?) = if (at == null) "未打开过" else "上次打开 " + DAY.format(at)
