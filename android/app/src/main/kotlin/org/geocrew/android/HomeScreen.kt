package org.geocrew.android

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.core.project.ProjectFormat
import org.geocrew.core.project.ProjectFormat.NameProblem
import org.geocrew.storage.Workspace

private data class ProjectEntry(
    val name: String,
    val modified: Long,
    val layers: Int?,
    val features: Long?,
    val bytes: Long?,
    val created: Instant?,
)

/**
 * The home screen: the projects, latest first, each opening straight into its
 * map; a new project; Settings and About. Everything goes through actions
 * (ADR 0010).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(graph: AppGraph, onOpen: (String) -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectEntry>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val failedText = stringResource(R.string.action_failed)
    val startText = stringResource(R.string.start_failed)
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val list = rememberLazyListState()
    val expanded by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }

    LaunchedEffect(Unit) {
        when (val listed = graph.invoke(Workspace.LIST)) {
            is Outcome.Done -> projects = listed.output.jsonObject.getValue("projects").jsonArray.map {
                val p = it.jsonObject
                ProjectEntry(
                    name = p.getValue("name").jsonPrimitive.content,
                    modified = p.getValue("modified").jsonPrimitive.long,
                    layers = p["layers"]?.jsonPrimitive?.intOrNull,
                    features = p["features"]?.jsonPrimitive?.longOrNull,
                    bytes = p["bytes"]?.jsonPrimitive?.longOrNull,
                    created = p["created"]?.jsonPrimitive?.contentOrNull?.let { c -> runCatching { Instant.parse(c) }.getOrNull() },
                )
            }
            is Outcome.Failed -> failure = (if (listed.code == AppGraph.START_FAILED) startText else failedText).format(listed.message)
            else -> failure = failedText.format(listed.toString())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.projects_title)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings)) }
                    IconButton(onClick = onAbout) { Icon(Icons.Rounded.Info, stringResource(R.string.about)) }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (!projects.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    expanded = expanded,
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(stringResource(R.string.project_new)) },
                )
            }
        },
    ) { padding ->
        val shown = projects
        when {
            shown == null -> Box(Modifier.fillMaxSize().padding(padding))
            shown.isEmpty() -> EmptyProjects(Modifier.padding(padding), onCreate = { creating = true })
            else -> LazyColumn(
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.name }) { project -> ProjectCard(project, onClick = { onOpen(project.name) }) }
            }
        }
    }

    if (creating) {
        NewProjectDialog(
            existing = projects.orEmpty().map { it.name },
            onDismiss = { creating = false },
            onCreate = { name ->
                creating = false
                scope.launch {
                    when (val created = graph.invoke(Workspace.CREATE, buildJsonObject { put("name", name) })) {
                        is Outcome.Done -> onOpen(name)
                        is Outcome.Failed -> failure = failedText.format(created.message)
                        else -> failure = failedText.format(created.toString())
                    }
                }
            },
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(message) },
        )
    }
}

@Composable
private fun ProjectCard(project: ProjectEntry, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Map, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                // What is in it: points, layers, size.
                val context = LocalContext.current
                val contents = listOfNotNull(
                    project.features?.let { pluralStringResource(R.plurals.points_count, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it) },
                    project.layers?.let { pluralStringResource(R.plurals.layers_count, it, it) },
                    project.bytes?.let { Formatter.formatShortFileSize(context, it) },
                )
                if (contents.isNotEmpty()) {
                    Text(contents.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // When it was made and last changed.
                val ago = DateUtils.getRelativeTimeSpanString(project.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                val dates = listOfNotNull(
                    project.created?.let { stringResource(R.string.project_created, CREATED.withLocale(LocalConfiguration.current.locales[0]).format(it)) },
                    stringResource(R.string.project_modified, ago),
                )
                Text(dates.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// A date in the app's language and the phone's time zone, such as 2026年11月26日 or 26 Nov 2026.
private val CREATED = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

@Composable
private fun EmptyProjects(modifier: Modifier, onCreate: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Map, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.projects_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.projects_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onCreate) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.project_new))
        }
    }
}

@Composable
private fun NewProjectDialog(existing: List<String>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val problem = ProjectFormat.nameProblem(name)
    val taken = existing.any { it.equals(name, ignoreCase = true) } // memory cards ignore case
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
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
    NameProblem.TOO_LONG -> pluralStringResource(R.plurals.name_too_long, ProjectFormat.MAX_NAME, ProjectFormat.MAX_NAME)
    NameProblem.DOT -> stringResource(R.string.name_dot)
    NameProblem.CHARACTER -> stringResource(R.string.name_character)
}
