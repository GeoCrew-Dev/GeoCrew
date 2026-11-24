package org.geocrew.feature.compass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.geoRoute
import org.geocrew.core.model.lengthMeters
import org.geocrew.core.ui.KindUi
import org.geocrew.core.ui.SectionHeader
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** One line about a stop: kind, lithology, attitude and when it was made. */
private fun stopDetail(f: GeoFeature): String = buildList {
    add(KindUi.label(f.kind))
    (f.properties[Props.LITHOLOGY] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let(::add)
    f.attitude?.let { add(it.format()) }
    add(f.createdAt.atZone(ZoneId.systemDefault()).format(clock))
}.joinToString(" · ")

/**
 * 地质路线: the day's observation points in walking order, what was seen
 * between each pair (点间描述), and the route summary (路线小结).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(onBack: () -> Unit, viewModel: RouteViewModel = hiltViewModel()) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val byId by viewModel.byId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    BackHandler(enabled = draft != null) { viewModel.close() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft == null) "地质路线" else draft?.title?.ifBlank { "地质路线" } ?: "") },
                navigationIcon = {
                    IconButton(onClick = { if (draft != null) viewModel.close() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (draft != null) IconButton(onClick = viewModel::save) { Icon(Icons.Default.Save, "保存") }
                },
            )
        },
        floatingActionButton = {
            if (draft == null) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::startNew,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("新建路线") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = draft
        if (d == null) {
            RouteList(routes, byId, Modifier.padding(padding), onOpen = viewModel::open, onDelete = viewModel::delete)
        } else {
            RouteEditor(d, byId, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun RouteList(
    items: List<GeoFeature>,
    byId: Map<String, GeoFeature>,
    modifier: Modifier,
    onOpen: (GeoFeature) -> Unit,
    onDelete: (String) -> Unit,
) {
    var confirm by remember { mutableStateOf<GeoFeature?>(null) }
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "还没有地质路线。收工前新建一条，今天记的地质点会按先后顺序排好，补上点间描述和路线小结即可；导出的路线记录表可以直接整理成野外记录簿。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(32.dp),
            )
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            items(items, key = { it.id }) { f ->
                val r = f.geoRoute
                val names = r.resolve(byId::get).mapNotNull { it.feature?.title }
                val span = if (names.isEmpty()) "没有点" else if (names.size == 1) names[0] else "${names.first()}→${names.last()}"
                val km = f.geometry?.takeIf { it is Geometry.LineString }?.lengthMeters()?.let { " · " + CoordinateFormatter.distance(it) }.orEmpty()
                ListItem(
                    headlineContent = { Text(f.title?.ifBlank { null } ?: "地质路线") },
                    supportingContent = {
                        Text(listOfNotNull(r.date?.toString(), "${r.stops.size} 个点（$span）$km", r.purpose.takeIf { it.isNotBlank() }).joinToString(" · "))
                    },
                    trailingContent = { IconButton(onClick = { confirm = f }) { Icon(Icons.Default.Delete, "删除") } },
                    modifier = Modifier.clickable { onOpen(f) },
                )
            }
        }
    }
    confirm?.let { f ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("删除这条路线？") },
            text = { Text("只删除路线「${f.title ?: "地质路线"}」本身（点间描述和小结），路线上的地质点都保留。") },
            confirmButton = { TextButton(onClick = { onDelete(f.id); confirm = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun RouteEditor(d: RouteDraft, byId: Map<String, GeoFeature>, vm: RouteViewModel, modifier: Modifier) {
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val resolved = d.route.resolve(byId::get)
    val path = d.route.path(byId::get)
    val length = if (path.size >= 2) Geometry.LineString(path).lengthMeters() else 0.0

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = d.title, onValueChange = { t -> vm.edit { it.copy(title = t) } },
                        label = { Text("路线号") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = d.date, onValueChange = { t -> vm.edit { it.copy(date = t) } },
                        label = { Text("日期") }, singleLine = true, isError = d.date.isNotBlank() && d.parsedDate() == null,
                        modifier = Modifier.weight(1.3f),
                    )
                }
                OutlinedTextField(
                    value = d.route.members, onValueChange = { t -> vm.editRoute { it.copy(members = t) } },
                    label = { Text("参加人员") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = d.route.purpose, onValueChange = { t -> vm.editRoute { it.copy(purpose = t) } },
                    label = { Text("路线目的与任务") }, minLines = 1, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SectionHeader(
                "路线上的点（${resolved.size} 个" + (if (length > 0) "，连线 ${CoordinateFormatter.distance(length)}" else "") + "）",
                Modifier.padding(horizontal = 16.dp),
            )
        }
        itemsIndexed(resolved, key = { _, r -> r.stop.featureId }) { i, r ->
            val f = r.feature
            Column(Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.titleMedium) },
                    headlineContent = { Text(f?.title?.ifBlank { null } ?: if (f == null) "（找不到这条记录）" else "未命名") },
                    supportingContent = { Text(f?.let(::stopDetail) ?: "这条记录已删除或还没同步到，不计入路线") },
                    trailingContent = {
                        Row {
                            if (i > 0) IconButton(onClick = { vm.editRoute { it.move(i, i - 1) } }) { Icon(Icons.Default.ArrowUpward, "上移") }
                            if (i < resolved.size - 1) IconButton(onClick = { vm.editRoute { it.move(i, i + 1) } }) { Icon(Icons.Default.ArrowDownward, "下移") }
                            IconButton(onClick = { vm.editRoute { it.remove(r.stop.featureId) } }) { Icon(Icons.Default.Close, "移出路线") }
                        }
                    },
                )
                if (i < resolved.size - 1) {
                    val next = resolved[i + 1].feature?.title ?: "下一点"
                    OutlinedTextField(
                        value = r.stop.onward,
                        onValueChange = { t -> vm.editRoute { it.withOnward(r.stop.featureId, t) } },
                        label = { Text("${f?.title ?: "本点"} → $next 点间描述") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { picking = true }, label = { Text("添加点") }, leadingIcon = { Icon(Icons.Default.Add, null) })
                AssistChip(onClick = vm::fillDay, label = { Text("填入当天的点") }, leadingIcon = { Icon(Icons.Default.Today, null) })
            }
        }

        item { SectionHeader("路线小结", Modifier.padding(horizontal = 16.dp)) }
        item {
            OutlinedTextField(
                value = d.route.summary, onValueChange = { t -> vm.editRoute { it.copy(summary = t) } },
                label = { Text("主要地质现象、认识与存在问题") }, minLines = 4,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }

    if (picking) {
        StopPicker(
            items = candidates.filterNot { d.route.contains(it.id) },
            onDismiss = { picking = false },
            onPick = { ids -> vm.editRoute { it.plus(ids) }; picking = false },
        )
    }
}

/** Picks several point records at once; they join the route in the order ticked. */
@Composable
private fun StopPicker(items: List<GeoFeature>, onDismiss: () -> Unit, onPick: (List<String>) -> Unit) {
    var chosen by remember { mutableStateOf(listOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加点（按勾选顺序加入）") },
        text = {
            if (items.isEmpty()) {
                Text("没有可以加入的点记录。")
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(items, key = { it.id }) { f ->
                        val on = f.id in chosen
                        ListItem(
                            leadingContent = { Checkbox(checked = on, onCheckedChange = null) },
                            headlineContent = { Text(f.title?.ifBlank { null } ?: "未命名") },
                            supportingContent = { Text(stopDetail(f)) },
                            modifier = Modifier.clickable { chosen = if (on) chosen - f.id else chosen + f.id },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onPick(chosen) }) { Text("加入 ${chosen.size} 个") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
