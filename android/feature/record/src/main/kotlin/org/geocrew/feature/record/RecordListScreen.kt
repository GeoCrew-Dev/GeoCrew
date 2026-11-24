package org.geocrew.feature.record

import org.geocrew.core.model.samples
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.lengthMeters
import org.geocrew.core.model.task
import org.geocrew.core.model.attachmentIds
import org.geocrew.core.ui.KindUi
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordListScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onImportExport: () -> Unit,
    onProjects: () -> Unit = {},
    viewModel: RecordListViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val kind by viewModel.kindFilter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val project by viewModel.projectName.collectAsStateWithLifecycle()
    val taskFilter by viewModel.taskFilter.collectAsStateWithLifecycle()
    val taskCounts by viewModel.taskCounts.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (project.isBlank()) "记录" else "记录 · $project") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = onProjects) { Icon(Icons.Default.Folder, "切换工程") }
                    IconButton(onClick = onImportExport) { Icon(Icons.Default.ImportExport, "导入导出") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                placeholder = { Text("搜索名称或备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = kind == null && taskFilter == RecordListViewModel.TaskFilter.NONE,
                    onClick = { viewModel.kindFilter.value = null; viewModel.taskFilter.value = RecordListViewModel.TaskFilter.NONE },
                    label = { Text("全部 ${counts.values.sum()}") },
                )
                // Task chips only show up once the team uses tasks.
                if (taskCounts.mine > 0 || taskFilter == RecordListViewModel.TaskFilter.MINE) {
                    val on = taskFilter == RecordListViewModel.TaskFilter.MINE
                    FilterChip(
                        selected = on,
                        onClick = { viewModel.taskFilter.value = if (on) RecordListViewModel.TaskFilter.NONE else RecordListViewModel.TaskFilter.MINE },
                        label = { Text("我的任务 ${taskCounts.mine}") },
                    )
                }
                if (taskCounts.open > 0 || taskFilter == RecordListViewModel.TaskFilter.OPEN) {
                    val on = taskFilter == RecordListViewModel.TaskFilter.OPEN
                    FilterChip(
                        selected = on,
                        onClick = { viewModel.taskFilter.value = if (on) RecordListViewModel.TaskFilter.NONE else RecordListViewModel.TaskFilter.OPEN },
                        label = { Text("未完成任务 ${taskCounts.open}") },
                    )
                }
                FeatureKind.builtIn.filter { (counts[it] ?: 0) > 0 }.forEach { k ->
                    FilterChip(
                        selected = kind == k,
                        onClick = { viewModel.kindFilter.value = if (kind == k) null else k },
                        label = { Text("${KindUi.label(k)} ${counts[k]}") },
                    )
                }
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (counts.values.sum() > 0) "没有符合条件的记录。" else "还没有记录。在地图上定点、画线或记录轨迹，都会出现在这里。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(32.dp))
                }
            } else {
                LazyColumn {
                    items(items, key = { it.id }) { f -> RecordRow(f, me) { onOpen(f.id) } }
                }
            }
        }
    }
}

@Composable
private fun RecordRow(f: GeoFeature, me: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(f.title ?: KindUi.label(f.kind)) },
        supportingContent = {
            val parts = buildList {
                add(KindUi.label(f.kind))
                f.attitude?.let { add(it.format()) }
                when (val g = f.geometry) {
                    is Geometry.Polygon, is Geometry.MultiPolygon -> add(CoordinateFormatter.area(g.areaSquareMeters()))
                    is Geometry.LineString, is Geometry.MultiLineString -> add(CoordinateFormatter.distance(g.lengthMeters()))
                    else -> Unit
                }
                if (f.photos.isNotEmpty()) add("${f.photos.size} 张照片")
                f.attachmentIds(org.geocrew.core.model.AttachmentKind.AUDIO).takeIf { it.isNotEmpty() }?.let { add("${it.size} 段录音") }
                f.samples.takeIf { it.isNotEmpty() }?.let { add("${it.size} 件样品") }
                f.task?.let { t ->
                    val who = if (t.assignee == me) "我" else t.assigneeName.ifBlank { "组员" }
                    add("任务：$who · ${t.status.label}")
                }
                if (f.sync.dirty) add("未同步")
            }
            Text(parts.joinToString(" · "))
        },
        leadingContent = { Icon(KindUi.icon(f.kind), null) },
        trailingContent = { Text(timeFormat.format(f.sync.updatedAt), style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
