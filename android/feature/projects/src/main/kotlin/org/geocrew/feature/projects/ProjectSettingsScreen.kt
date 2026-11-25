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
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.team.TeamForms
import org.geocrew.core.data.team.TeamRepository
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.FieldType
import org.geocrew.core.model.Legend
import org.geocrew.core.model.Project
import org.geocrew.core.ui.KindUi
import org.geocrew.core.ui.SectionHeader
import org.geocrew.core.model.Numbering
import javax.inject.Inject

/** Route argument holding the project id. */
const val PROJECT_ID_ARG = "projectId"

/** Who may change a project's form. */
enum class FormAccess {
    /** Local project: the form is the user's own. */
    LOCAL,
    /** Linked project, and the user leads the workspace: edits go to every member. */
    TEAM_LEADER,
    /** Linked project led by someone else: the form is read-only here. */
    TEAM_MEMBER,
}

@HiltViewModel
class ProjectSettingsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val projects: ProjectRepository,
    private val team: TeamRepository,
    private val forms: TeamForms,
    features: FeatureRepository,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[PROJECT_ID_ARG])

    val project: StateFlow<Project?> = projects.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val access: StateFlow<FormAccess> = project.map { p ->
        when {
            p?.team == null -> FormAccess.LOCAL
            p.team?.role == TeamRepository.ROLE_LEADER -> FormAccess.TEAM_LEADER
            else -> FormAccess.TEAM_MEMBER
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FormAccess.LOCAL)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    /** Values the records of this project use, per property key, most used first (legend suggestions). */
    val usedValues: StateFlow<Map<String, List<String>>> = features.observeProject(id).map { list ->
        val counts = mutableMapOf<String, MutableMap<String, Int>>()
        for (f in list) for ((k, v) in f.properties) {
            val t = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (t.isNullOrEmpty() || t.length > 40) continue
            val m = counts.getOrPut(k) { mutableMapOf() }
            m[t] = (m[t] ?: 0) + 1
        }
        counts.mapValues { (_, m) -> m.entries.sortedByDescending { it.value }.map { it.key } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Saves the map legend; in a team it goes to every member like the form. */
    fun setLegend(legend: Legend) {
        if (access.value == FormAccess.TEAM_MEMBER) return
        viewModelScope.launch {
            val h = if (access.value == FormAccess.TEAM_LEADER) team.handle(id) else null
            if (h == null) {
                projects.updateConfig(id) { it.copy(legend = legend) }
                return@launch
            }
            _message.value = try {
                forms.publishLegend(h, legend)
            } catch (e: java.io.IOException) {
                "已保存在本机，联网后会同步给组员"
            }
        }
    }

    fun rename(name: String) = viewModelScope.launch {
        if (name.isNotBlank() && name.trim() != project.value?.name) projects.rename(id, name)
    }

    fun describe(text: String) = viewModelScope.launch { projects.updateConfig(id) { it.copy(description = text.trim()) } }

    /** Saves how new records are named; returns the problem instead when there is one. */
    fun setNumbering(n: Numbering): String? {
        val clean = n.copy(geoPoint = n.geoPoint.trim(), point = n.point.trim(), line = n.line.trim(), polygon = n.polygon.trim())
        Numbering.problem(clean)?.let { return it }
        viewModelScope.launch { projects.updateConfig(id) { it.copy(numbering = clean) } }
        return null
    }

    /** Adds or replaces the field with [old]'s key; returns the problem instead when there is one. */
    fun putField(old: FieldTemplate?, field: FieldTemplate): String? {
        val fields = project.value?.config?.fields ?: return "工程不存在"
        val others = fields.filter { it.key != old?.key }
        FieldTemplate.problem(field, others)?.let { return it }
        val i = fields.indexOfFirst { it.key == old?.key }
        saveFields(if (i < 0) fields + field else fields.toMutableList().also { it[i] = field })
        return null
    }

    fun removeField(field: FieldTemplate) = change { it - field }

    fun moveUp(field: FieldTemplate) = change { list ->
        val i = list.indexOf(field)
        if (i <= 0) list else list.toMutableList().also { it.add(i - 1, it.removeAt(i)) }
    }

    /** Adds the preset's fields that the project does not have yet. */
    fun addPreset() = change { list -> list + FieldTemplate.geologyPreset.filter { p -> list.none { it.key == p.key } } }

    private fun change(edit: (List<FieldTemplate>) -> List<FieldTemplate>) {
        project.value?.config?.fields?.let { saveFields(edit(it)) }
    }

    private fun saveFields(fields: List<FieldTemplate>) {
        if (access.value == FormAccess.TEAM_MEMBER) return
        viewModelScope.launch {
            val h = if (access.value == FormAccess.TEAM_LEADER) team.handle(id) else null
            if (h == null) {
                projects.updateConfig(id) { it.copy(fields = fields) }
                return@launch
            }
            _message.value = try {
                forms.publish(h, fields)
            } catch (e: java.io.IOException) {
                // Saved locally with the pending flag; the next connection publishes it.
                "已保存在本机，联网后会同步给组员"
            }
        }
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
    val access by viewModel.access.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val usedValues by viewModel.usedValues.collectAsStateWithLifecycle()
    val readOnly = access == FormAccess.TEAM_MEMBER
    var editing by remember { mutableStateOf<Pair<FieldTemplate?, FieldTemplate>?>(null) }
    var editingNumbers by remember { mutableStateOf(false) }

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

            SectionHeader("记录编号")
            val n = p.config.numbering
            ListItem(
                headlineContent = {
                    Text(listOf(n.geoPoint, n.point, n.line, n.polygon).joinToString("  ") { n.next(it, emptyList()) })
                },
                supportingContent = {
                    Text(
                        "新记录按前缀和顺序号命名，接着本工程已用过的最大编号往下排。" +
                            if (p.team != null) "多人同时打点时，建议每人用自己的前缀，例如 D-王，避免重号。" else "",
                    )
                },
                modifier = Modifier.clickable { editingNumbers = true },
            )

            SectionHeader("记录表单")
            Text(
                when (access) {
                    FormAccess.LOCAL -> "这里的属性会出现在本工程每条记录的详情里，并随工程包一起导出。"
                    FormAccess.TEAM_LEADER -> "这里的属性会出现在本工程每条记录的详情里。你是组长，修改后会同步给工作组的所有成员。"
                    FormAccess.TEAM_MEMBER -> "表单由组长统一维护，组内所有人按同一套属性填写。"
                },
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            p.config.fields.forEachIndexed { i, field ->
                ListItem(
                    headlineContent = { Text(field.label) },
                    supportingContent = { Text(describe(field)) },
                    trailingContent = if (readOnly) null else ({
                        Row {
                            if (i > 0) IconButton(onClick = { viewModel.moveUp(field) }) { Icon(Icons.Default.ArrowUpward, "上移") }
                            IconButton(onClick = { viewModel.removeField(field) }) { Icon(Icons.Default.Delete, "删除 ${field.label}") }
                        }
                    }),
                    modifier = if (readOnly) Modifier else Modifier.clickable { editing = field to field },
                )
            }
            if (!readOnly) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = null to FieldTemplate("", "") }) { Text("添加属性") }
                if (FieldTemplate.geologyPreset.any { pre -> p.config.fields.none { it.key == pre.key } }) {
                    OutlinedButton(onClick = viewModel::addPreset) { Text("套用地质填图模板") }
                }
            }

            SectionHeader("地图图例")
            LegendSection(
                legend = p.config.legend,
                fields = p.config.fields,
                used = usedValues,
                access = access,
                onChange = viewModel::setLegend,
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::consumeMessage) { Text("好") } },
        )
    }

    if (editingNumbers) {
        project?.let { p ->
            NumberingDialog(
                start = p.config.numbering,
                onDismiss = { editingNumbers = false },
                onSave = { viewModel.setNumbering(it).also { e -> if (e == null) editingNumbers = false } },
            )
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
    if (f.required) add("必填")
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
    var required by remember { mutableStateOf(start.required) }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("必填", style = MaterialTheme.typography.bodyLarge)
                        Text("没填的记录在列表里标为「待补全」，不会阻止保存", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = required, onCheckedChange = { required = it })
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
                    required = required,
                )
                problem = onSave(f)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NumberingDialog(start: Numbering, onDismiss: () -> Unit, onSave: (Numbering) -> String?) {
    var geo by rememberSaveable { mutableStateOf(start.geoPoint) }
    var point by rememberSaveable { mutableStateOf(start.point) }
    var line by rememberSaveable { mutableStateOf(start.line) }
    var polygon by rememberSaveable { mutableStateOf(start.polygon) }
    var width by rememberSaveable { mutableStateOf(start.width.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录编号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("地质点前缀", geo) { v: String -> geo = v },
                    Triple("普通点前缀", point) { v: String -> point = v },
                    Triple("线前缀", line) { v: String -> line = v },
                    Triple("面前缀", polygon) { v: String -> polygon = v },
                ).forEach { (label, value, set) ->
                    OutlinedTextField(value = value, onValueChange = { set(it); error = null }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    value = width, onValueChange = { width = it.filter(Char::isDigit).take(1); error = null },
                    label = { Text("编号位数（不足补 0）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = onSave(Numbering(geo, point, line, polygon, width.toIntOrNull() ?: 0))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
