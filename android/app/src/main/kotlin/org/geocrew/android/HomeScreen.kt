package org.geocrew.android

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val lines: Long? = null,
    val areas: Long? = null,
    val created: Instant?,
)

/**
 * The home screen: the projects, latest first, each a large card with a picture
 * of its map from when it was last left, opening straight into the map; a new
 * project; Settings and About. The cards stand side by side where the screen
 * is wide, as in landscape. Everything goes through actions (ADR 0010).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(graph: AppGraph, onOpen: (String) -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var projects by remember { mutableStateOf<List<ProjectEntry>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val failedText = stringResource(R.string.action_failed)
    val startText = stringResource(R.string.start_failed)
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val grid = rememberLazyGridState()
    val expanded by remember { derivedStateOf { grid.firstVisibleItemIndex == 0 } }

    LaunchedEffect(Unit) {
        when (val listed = graph.invoke(Workspace.LIST)) {
            is Outcome.Done -> projects = listed.output.jsonObject.getValue("projects").jsonArray.map {
                val p = it.jsonObject
                ProjectEntry(
                    name = p.getValue("name").jsonPrimitive.content,
                    modified = p.getValue("modified").jsonPrimitive.long,
                    layers = p["layers"]?.jsonPrimitive?.intOrNull,
                    features = p["points"]?.jsonPrimitive?.longOrNull ?: p["features"]?.jsonPrimitive?.longOrNull,
                    lines = p["lines"]?.jsonPrimitive?.longOrNull,
                    areas = p["areas"]?.jsonPrimitive?.longOrNull,
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
                    // The icon carries the name for screen readers: this button hides its text from them.
                    icon = { Icon(Icons.Rounded.Add, stringResource(R.string.project_new)) },
                    text = { Text(stringResource(R.string.project_new)) },
                )
            }
        },
    ) { padding ->
        val shown = projects
        when {
            shown == null -> Box(Modifier.fillMaxSize().padding(padding))
            shown.isEmpty() -> EmptyProjects(Modifier.padding(padding), onCreate = { creating = true })
            else -> {
                val direction = LocalLayoutDirection.current
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 320.dp),
                    state = grid,
                    contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(direction) + 16.dp,
                        end = padding.calculateEndPadding(direction) + 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = 112.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.name }) { project ->
                        ProjectCard(project, graph.thumbnail(project.name), onClick = { onOpen(project.name) })
                    }
                }
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
                    val language = AppLanguage.speaking(context)
                    when (val created = graph.invoke(Workspace.CREATE, buildJsonObject { put("name", name); put("language", language) })) {
                        is Outcome.Done -> {
                            // A picture left by an earlier project of the same name is not this one's.
                            withContext(Dispatchers.IO) { graph.thumbnail(name).delete() }
                            onOpen(name)
                        }
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
private fun ProjectCard(project: ProjectEntry, thumbnail: File, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Thumbnail(thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            // What is in it: points, lines and areas (points even when there are none, unless there are lines or areas), layers, size.
            val context = LocalContext.current
            val lines = project.lines ?: 0
            val areas = project.areas ?: 0
            @Composable fun count(plural: Int, n: Long) = pluralStringResource(plural, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), n)
            val contents = listOfNotNull(
                project.features?.takeIf { it > 0 || (lines == 0L && areas == 0L) }?.let { count(R.plurals.points_count, it) },
                lines.takeIf { it > 0 }?.let { count(R.plurals.lines_count, it) },
                areas.takeIf { it > 0 }?.let { count(R.plurals.areas_count, it) },
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
    }
}

/** The project's map as it was last left; a plain map sign until it has been left once. */
@Composable
private fun Thumbnail(file: File, modifier: Modifier) {
    val picture by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val shown = picture
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.Map, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
    }
}

// A date in the app's language and the phone's time zone, such as 2026年11月26日 or 26 Nov 2026.
private val CREATED = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

@Composable
private fun EmptyProjects(modifier: Modifier, onCreate: () -> Unit) {
    // Centred while it fits; landscape leaves little height, so there the picture is
    // smaller and the rest scrolls if it must.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val short = maxHeight < 420.dp
        val picture = if (short) 80.dp else 112.dp
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 32.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(picture).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Map, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(picture / 2))
            }
            Spacer(Modifier.height(if (short) 16.dp else 24.dp))
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
