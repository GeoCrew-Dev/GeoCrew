package org.geocrew.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.geocrew.core.action.Outcome

/**
 * A project's settings, opened from its map's menu: the project and which layer
 * new points go into; more settings join them here. Layers and their fields
 * have their own page, from the map. Everything goes through actions (ADR 0010).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSettingsScreen(graph: AppGraph, project: String, onBack: () -> Unit) {
    var folder by remember { mutableStateOf<String?>(null) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var layers by remember { mutableStateOf<List<LayerInfo>>(emptyList()) }
    // The layer new points go into without asking, by id; null to ask each time.
    var placing by remember { mutableStateOf<String?>(null) }
    var choosingPlacing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val texts = FailureTexts(
        unsaved = stringResource(R.string.failure_unsaved),
        failed = stringResource(R.string.action_failed),
        start = stringResource(R.string.start_failed),
    )

    LaunchedEffect(project) {
        val opened = graph.open(project)
        if (opened !is Outcome.Done) {
            failure = texts.of(opened)
            return@LaunchedEffect
        }
        val about = opened.output.jsonObject
        folder = (about["folder"] as? JsonPrimitive)?.contentOrNull
        projectId = (about["id"] as? JsonPrimitive)?.contentOrNull
        layers = listedLayers(about)
        // A remembered layer that has gone is forgotten: placing asks again.
        placing = projectId?.let(graph.settings::placingLayer)?.takeIf { id -> layers.any { it.id == id && it.point } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.project_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        // List items take the screen's own colour, so the page has no bands.
        val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader(stringResource(R.string.project_section)) }
            item {
                ListItem(
                    headlineContent = { Text(project) },
                    overlineContent = { Text(stringResource(R.string.project_name)) },
                    leadingContent = { Icon(Icons.Rounded.Map, null) },
                    colors = clear,
                )
            }
            folder?.let { path ->
                item {
                    ListItem(
                        headlineContent = { Text(path) },
                        overlineContent = { Text(stringResource(R.string.project_folder)) },
                        leadingContent = { Icon(Icons.Rounded.Folder, null) },
                        colors = clear,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.placing_section)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.placing_layer)) },
                    supportingContent = { Text(layers.firstOrNull { it.id == placing }?.name ?: stringResource(R.string.placing_ask)) },
                    leadingContent = { Icon(Icons.Rounded.AddLocationAlt, null) },
                    colors = clear,
                    modifier = Modifier.clickable { choosingPlacing = true },
                )
            }
        }
    }

    if (choosingPlacing) {
        // Ask each time, or one of the point layers, as "remember" on the map chose.
        val choices = listOf<LayerInfo?>(null) + layers.filter { it.point }
        AlertDialog(
            onDismissRequest = { choosingPlacing = false },
            title = { Text(stringResource(R.string.placing_layer)) },
            text = {
                Column {
                    for (choice in choices) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                choosingPlacing = false
                                placing = choice?.id
                                projectId?.let { graph.settings.setPlacingLayer(it, choice?.id) }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = placing == choice?.id, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(choice?.name ?: stringResource(R.string.placing_ask))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingPlacing = false }) { Text(stringResource(R.string.cancel)) } },
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
