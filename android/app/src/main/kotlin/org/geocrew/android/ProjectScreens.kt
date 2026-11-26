package org.geocrew.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.NameProblem
import org.geocrew.core.project.Templates
import org.geocrew.storage.Workspace

/** Where projects go, asked once (ADR 0002): all-files access, or the app's own folder for now. */
@Composable
fun StorageScreen(onGrant: () -> Unit, onOwnFolder: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.storage_body))
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.storage_grant)) }
            OutlinedButton(onClick = onOwnFolder, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.storage_own_folder)) }
            Text(stringResource(R.string.storage_own_folder_warning), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The projects, and the open one. Everything goes through the action registry (ADR 0010). */
@Composable
fun ProjectsRoute(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf(emptyList<String>()) }
    var open by remember { mutableStateOf<JsonObject?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var startFailure by remember { mutableStateOf<String?>(null) }
    var mapLayer by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        val listed = graph.invoke(Workspace.LIST)
        if (listed is Outcome.Done) projects = listed.output.jsonObject.getValue("projects").jsonArray.map { it.jsonPrimitive.content }
    }

    fun run(action: String, params: JsonObject, then: suspend (JsonObject) -> Unit) {
        scope.launch {
            when (val outcome = graph.invoke(action, params)) {
                is Outcome.Done -> then(outcome.output as? JsonObject ?: JsonObject(emptyMap()))
                is Outcome.Failed -> failure = outcome.message
                else -> failure = outcome.toString()
            }
        }
    }

    LaunchedEffect(Unit) {
        val started = withContext(Dispatchers.IO) { runCatching { graph.start() } }
        started.exceptionOrNull()?.let {
            startFailure = it.message ?: it.toString()
            return@LaunchedEffect
        }
        refresh()
        // The activity may have been made again (the phone turned) with a project still open.
        val current = graph.invoke(Workspace.CURRENT)
        if (current is Outcome.Done && current.output.jsonObject.containsKey("name")) open = current.output.jsonObject
        ready = true
    }

    val shown = open
    when {
        startFailure != null -> Scaffold { padding ->
            Text(stringResource(R.string.start_failed, startFailure!!), Modifier.padding(padding).padding(24.dp))
        }
        !ready -> Scaffold { padding -> Text(stringResource(R.string.starting), Modifier.padding(padding).padding(24.dp)) }
        shown != null && mapLayer != null -> MapScreen(graph, shown.getValue("name").jsonPrimitive.content, mapLayer!!, onBack = { mapLayer = null })
        shown != null -> ProjectScreen(shown, onMap = { mapLayer = it }, onClose = {
            run(Workspace.CLOSE, JsonObject(emptyMap())) {
                open = null
                refresh()
            }
        })
        else -> ProjectList(
            projects = projects,
            root = graph.root.path,
            onOpen = { name -> run(Workspace.OPEN, nameParams(name)) { open = it } },
            onCreate = { name -> run(Workspace.CREATE, nameParams(name)) { open = it } },
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(stringResource(R.string.action_failed, message)) },
        )
    }
}

private fun nameParams(name: String) = buildJsonObject { put("name", name) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectList(projects: List<String>, root: String, onOpen: (String) -> Unit, onCreate: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.projects_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }) { Text(stringResource(R.string.project_new)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.projects_root, root),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (projects.isEmpty()) {
                Text(stringResource(R.string.projects_empty), Modifier.padding(16.dp))
            }
            LazyColumn {
                items(projects) { name ->
                    ListItem(headlineContent = { Text(name) }, modifier = Modifier.clickable { onOpen(name) })
                    HorizontalDivider()
                }
            }
        }
    }
    if (creating) {
        NewProjectDialog(existing = projects, onDismiss = { creating = false }, onCreate = { creating = false; onCreate(it) })
    }
}

@Composable
private fun NewProjectDialog(existing: List<String>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val problem = ProjectFormat.nameProblem(name)
    val taken = existing.any { it.equals(name, ignoreCase = true) } // memory cards ignore case
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.project_name)) },
                singleLine = true,
                isError = name.isNotEmpty() && (problem != null || taken),
                supportingText = {
                    when {
                        name.isEmpty() -> Unit
                        taken -> Text(stringResource(R.string.name_taken))
                        problem != null -> Text(nameProblemText(problem))
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = problem == null && !taken) { Text(stringResource(R.string.project_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun nameProblemText(problem: NameProblem): String = when (problem) {
    NameProblem.EMPTY -> stringResource(R.string.name_empty)
    NameProblem.SPACE -> stringResource(R.string.name_space)
    NameProblem.TOO_LONG -> stringResource(R.string.name_too_long, ProjectFormat.MAX_NAME)
    NameProblem.DOT -> stringResource(R.string.name_dot)
    NameProblem.CHARACTER -> stringResource(R.string.name_character)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreen(project: JsonObject, onMap: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val name = project.getValue("name").jsonPrimitive.content
    val layers = project.getValue("layers").jsonArray.map { it.jsonObject }
    Scaffold(topBar = { TopAppBar(title = { Text(name) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.project_layers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
            for (layer in layers) {
                ListItem(
                    headlineContent = { Text(layer.getValue("name").jsonPrimitive.content) },
                    supportingContent = {
                        (layer["template"] as? JsonPrimitive)?.contentOrNull?.let { id ->
                            Text(stringResource(R.string.layer_template, Templates.byId(id)?.name ?: id))
                        }
                    },
                )
                HorizontalDivider()
            }
            // The map shows the layer made from the geological point template.
            val points = layers.firstOrNull { (it["template"] as? JsonPrimitive)?.contentOrNull == Templates.GEO_POINT.id }
            points?.let { p ->
                Button(onClick = { onMap(p.getValue("name").jsonPrimitive.content) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.map_open))
                }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.project_close)) }
        }
    }
}
