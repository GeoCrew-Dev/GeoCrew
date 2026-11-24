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
    private val projects: ProjectRepository,
    features: FeatureRepository,
    private val team: TeamRepository,
    private val storage: org.geocrew.core.data.repo.StorageMaintenance,
) : ViewModel() {
    /** Null until loaded, so the empty state does not flash. */
    val rows: StateFlow<List<ProjectRow>?> = combine(projects.projects, features.observeCounts(), projects.currentId) { list, counts, cur ->
        list.map { ProjectRow(it, counts[it.id] ?: 0, it.id == cur) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun create(name: String, then: () -> Unit) = viewModelScope.launch {
        val p = projects.create(name)
        projects.open(p.id)
        then()
    }

    fun open(id: String, then: () -> Unit) = viewModelScope.launch {
        projects.open(id)
        then()
    }
    fun rename(id: String, name: String) = viewModelScope.launch { projects.rename(id, name) }

    fun delete(id: String) = viewModelScope.launch {
        team.leave(id)
        projects.delete(id)
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
fun ProjectsScreen(onOpened: () -> Unit, onOpenSettings: () -> Unit, viewModel: ProjectsViewModel = hiltViewModel()) {
    val loaded by viewModel.rows.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var deleting by remember { mutableStateOf<ProjectRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同勘 · 工程") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "设置") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { naming = null to "" },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新建工程") },
            )
        },
    ) { padding ->
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
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (id == null) "新建工程" else "重命名工程") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (id == null) viewModel.create(name, onOpened) else viewModel.rename(id, name)
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
                        if (r.project.team != null) "本机会先退出工作组，组内服务器上的数据不受影响。" else "如需保留，请先导出。",
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
