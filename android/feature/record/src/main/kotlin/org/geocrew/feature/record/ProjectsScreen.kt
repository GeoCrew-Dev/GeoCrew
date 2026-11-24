package org.geocrew.feature.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import javax.inject.Inject

data class ProjectRow(val project: Project, val count: Int, val current: Boolean)

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val projects: ProjectRepository,
    features: FeatureRepository,
    private val team: TeamRepository,
) : ViewModel() {
    val rows: StateFlow<List<ProjectRow>> = combine(projects.projects, features.observeCounts(), projects.current) { list, counts, cur ->
        list.map { ProjectRow(it, counts[it.id] ?: 0, it.id == cur.id) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun create(name: String) = viewModelScope.launch {
        val p = projects.create(name)
        projects.select(p.id)
    }

    fun select(id: String) = viewModelScope.launch { projects.select(id) }
    fun rename(id: String, name: String) = viewModelScope.launch { projects.rename(id, name) }

    fun delete(id: String) = viewModelScope.launch {
        team.leave(id)
        projects.delete(id)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onBack: () -> Unit, onOpened: () -> Unit, viewModel: ProjectsViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var deleting by remember { mutableStateOf<ProjectRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工程") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { naming = null to "" }) { Icon(Icons.Default.Add, "新建工程") }
        },
    ) { padding ->
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
                        Text("${r.count} 条记录" + (r.project.team?.let { " · 工作组「${it.workspaceName}」" } ?: ""))
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
                    modifier = Modifier.clickable {
                        viewModel.select(r.project.id)
                        onOpened()
                    },
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
                    if (id == null) viewModel.create(name) else viewModel.rename(id, name)
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
