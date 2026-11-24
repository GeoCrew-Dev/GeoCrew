package org.geocrew.feature.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.FieldType
import org.geocrew.core.model.Project
import org.geocrew.core.ui.KindUi
import org.geocrew.core.ui.SectionHeader
import javax.inject.Inject

/** Route argument holding the project id. */
const val PROJECT_ID_ARG = "projectId"

@HiltViewModel
class ProjectSettingsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val projects: ProjectRepository,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[PROJECT_ID_ARG])

    val project: StateFlow<Project?> = projects.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun rename(name: String) = viewModelScope.launch {
        if (name.isNotBlank() && name.trim() != project.value?.name) projects.rename(id, name)
    }

    fun describe(text: String) = edit { it.copy(description = text.trim()) }

    /** Adds or replaces the field with [old]'s key; returns the problem instead when there is one. */
    fun putField(old: FieldTemplate?, field: FieldTemplate): String? {
        val fields = project.value?.config?.fields ?: return "工程不存在"
        val others = fields.filter { it.key != old?.key }
        FieldTemplate.problem(field, others)?.let { return it }
        edit { cfg ->
            val i = cfg.fields.indexOfFirst { it.key == old?.key }
            cfg.copy(fields = if (i < 0) cfg.fields + field else cfg.fields.toMutableList().also { it[i] = field })
        }
        return null
    }

    fun removeField(field: FieldTemplate) = edit { it.copy(fields = it.fields - field) }

    fun moveUp(field: FieldTemplate) = edit { cfg ->
        val i = cfg.fields.indexOf(field)
        if (i <= 0) cfg else cfg.copy(fields = cfg.fields.toMutableList().also { it.add(i - 1, it.removeAt(i)) })
    }

    /** Adds the preset's fields that the project does not have yet. */
    fun addPreset() = edit { cfg ->
        cfg.copy(fields = cfg.fields + FieldTemplate.geologyPreset.filter { p -> cfg.fields.none { it.key == p.key } })
    }

    private fun edit(change: (org.geocrew.core.model.ProjectConfig) -> org.geocrew.core.model.ProjectConfig) {
        viewModelScope.launch { projects.updateConfig(id, change) }
    }
}

/**
 * A project's own settings: its name, a description and the record form,
 * which every record of the project offers and which travels with the
 * project package.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSettingsScreen(onBack: () -> Unit, viewModel: ProjectSettingsViewModel = hiltViewModel()) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<FieldTemplate?, FieldTemplate>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工程设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        val p = project ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("基本信息")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var name by rememberSaveable(p.id) { mutableStateOf(p.name) }
                var desc by rememberSaveable(p.id) { mutableStateOf(p.config.description) }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("工程名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) viewModel.rename(name) },
                )
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it }, label = { Text("说明（工区、任务等）") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && desc.trim() != p.config.description) viewModel.describe(desc) },
                )
            }

            SectionHeader("记录表单")
            Text(
                "这里的属性会出现在本工程每条记录的详情里，并随工程包一起导出。",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            p.config.fields.forEachIndexed { i, field ->
                ListItem(
                    headlineContent = { Text(field.label) },
                    supportingContent = { Text(describe(field)) },
                    trailingContent = {
                        Row {
                            if (i > 0) IconButton(onClick = { viewModel.moveUp(field) }) { Icon(Icons.Default.ArrowUpward, "上移") }
                            IconButton(onClick = { viewModel.removeField(field) }) { Icon(Icons.Default.Delete, "删除 ${field.label}") }
                        }
                    },
                    modifier = Modifier.clickable { editing = field to field },
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = null to FieldTemplate("", "") }) { Text("添加属性") }
                if (FieldTemplate.geologyPreset.any { pre -> p.config.fields.none { it.key == pre.key } }) {
                    OutlinedButton(onClick = viewModel::addPreset) { Text("套用地质填图模板") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    editing?.let { (old, start) ->
        FieldDialog(
            old = old,
            start = start,
            onDismiss = { editing = null },
            onSave = { f -> viewModel.putField(old, f).also { if (it == null) editing = null } },
        )
    }
}

private fun describe(f: FieldTemplate): String = buildList {
    add(
        when (f.type) {
            FieldType.TEXT -> "文字"
            FieldType.NUMBER -> "数字" + f.unit.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            FieldType.CHOICE -> "选项：" + f.options.joinToString("、")
        },
    )
    if (f.kinds.isNotEmpty()) add(f.kinds.joinToString("、") { KindUi.label(FeatureKind(it)) })
    if (f.key != f.label) add("键 ${f.key}")
}.joinToString(" · ")

/** Edits one field. [onSave] returns a problem to show, or null once saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldDialog(old: FieldTemplate?, start: FieldTemplate, onDismiss: () -> Unit, onSave: (FieldTemplate) -> String?) {
    var label by remember { mutableStateOf(start.label) }
    // The key is what records store, so it is fixed once the field exists.
    var key by remember { mutableStateOf(start.key) }
    var type by remember { mutableStateOf(start.type) }
    var options by remember { mutableStateOf(start.options.joinToString("\n")) }
    var unit by remember { mutableStateOf(start.unit) }
    var kinds by remember { mutableStateOf(start.kinds.toSet()) }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (old == null) "添加属性" else "修改属性") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it; problem = null }, label = { Text("名称") }, singleLine = true)
                if (old == null) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it; problem = null }, singleLine = true,
                        label = { Text("属性键（可留空，用名称）") },
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t; problem = null }, label = {
                            Text(when (t) { FieldType.TEXT -> "文字"; FieldType.NUMBER -> "数字"; FieldType.CHOICE -> "选项" })
                        })
                    }
                }
                when (type) {
                    FieldType.NUMBER -> OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("单位（可空）") }, singleLine = true)
                    FieldType.CHOICE -> OutlinedTextField(
                        value = options, onValueChange = { options = it; problem = null }, minLines = 3,
                        label = { Text("选项，每行一个") },
                    )
                    FieldType.TEXT -> Unit
                }
                Text("用于", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kinds.isEmpty(), onClick = { kinds = emptySet() }, label = { Text("全部") })
                    FeatureKind.builtIn.forEach { k ->
                        FilterChip(
                            selected = k.id in kinds,
                            onClick = { kinds = if (k.id in kinds) kinds - k.id else kinds + k.id },
                            label = { Text(KindUi.label(k)) },
                        )
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = label.trim()
                val f = FieldTemplate(
                    key = if (old != null) old.key else key.trim().ifEmpty { l },
                    label = l,
                    type = type,
                    options = if (type == FieldType.CHOICE) options.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct() else emptyList(),
                    unit = if (type == FieldType.NUMBER) unit.trim() else "",
                    kinds = FeatureKind.builtIn.map { it.id }.filter { it in kinds },
                )
                problem = onSave(f)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
