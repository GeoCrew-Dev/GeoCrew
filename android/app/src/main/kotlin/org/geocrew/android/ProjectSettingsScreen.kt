package org.geocrew.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.storage.SyncActions
import org.geocrew.storage.Workspace

/**
 * A project's settings, opened from its map's menu: the project, which layer
 * new points go into, its snapshots, and the team it syncs with (ADR 0008).
 * More settings join them here. Layers and their fields
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
    // The days with a snapshot (ADR 0004), newest first, and the one about to be restored.
    var snapshots by remember { mutableStateOf<List<String>>(emptyList()) }
    var restoring by remember { mutableStateOf<String?>(null) }
    // The team the project syncs with (sync.status), its invite code when this member may invite, and what is under way.
    var team by remember { mutableStateOf<JsonObject?>(null) }
    var invite by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var shownInvite by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    val sync = syncTexts()
    val noInviteText = stringResource(R.string.team_invite_none)
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
        placing = projectId?.let(graph.settings::placingLayer)?.takeIf { id -> layers.any { it.id == id } }
        snapshots = (graph.invoke(Workspace.SNAPSHOTS) as? Outcome.Done)?.output?.jsonObject?.get("days")?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        team = (graph.invoke(SyncActions.STATUS) as? Outcome.Done)?.output?.jsonObject
    }

    // The invite code from the server, asked for when wanted: actions run one at a time, and a server out of reach
    // would hold the others up for as long as the call waits.
    fun showInvite() = scope.launch {
        invite?.let { shownInvite = it; return@launch }
        busy = true
        val done = graph.invoke(SyncActions.INVITE)
        busy = false
        when (done) {
            is Outcome.Done -> done.output.jsonObject.text("invite_code").let { code ->
                if (code == null) failure = noInviteText else { invite = code; shownInvite = code }
            }
            else -> failure = sync.of(done)
        }
    }

    // Sends the project's saved edits and brings in the team's; the counts come again after.
    fun syncNow() = scope.launch {
        busy = true
        val done = graph.invoke(SyncActions.NOW)
        busy = false
        if (done !is Outcome.Done) failure = sync.of(done)
        team = (graph.invoke(SyncActions.STATUS) as? Outcome.Done)?.output?.jsonObject
    }

    fun share(server: String, name: String, nickname: String) = scope.launch {
        busy = true
        val params = buildJsonObject {
            put("server", server)
            put("name", name)
            put("nickname", nickname)
            put("device", deviceName())
        }
        val done = graph.invoke(SyncActions.SHARE, params)
        busy = false
        when (done) {
            is Outcome.Done -> {
                invite = done.output.jsonObject.text("invite_code")
                shownInvite = invite
            }
            else -> failure = sync.of(done)
        }
        team = (graph.invoke(SyncActions.STATUS) as? Outcome.Done)?.output?.jsonObject
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
                    headlineContent = { Text(stringResource(R.string.project_name)) },
                    supportingContent = { Text(project) },
                    leadingContent = { Icon(Icons.Rounded.Map, null) },
                    colors = clear,
                )
            }
            folder?.let { path ->
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.project_folder)) },
                        supportingContent = { Text(path) },
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
            item { SectionHeader(stringResource(R.string.snapshots_section)) }
            if (snapshots.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.snapshots_none)) },
                        leadingContent = { Icon(Icons.Rounded.History, null) },
                        colors = clear,
                    )
                }
            }
            items(snapshots, key = { it }) { day ->
                ListItem(
                    headlineContent = { Text(dayText(day)) },
                    leadingContent = { Icon(Icons.Rounded.History, null) },
                    trailingContent = { TextButton(onClick = { restoring = day }) { Text(stringResource(R.string.snapshot_restore)) } },
                    colors = clear,
                )
            }
            // The team (ADR 0008): sharing the project, or, once shared, its server, syncing and the invite code.
            item { SectionHeader(stringResource(R.string.team_section)) }
            val progress: @Composable () -> Unit = { if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
            if (!team.flag("linked")) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.team_share)) },
                        leadingContent = { Icon(Icons.Rounded.GroupAdd, null) },
                        trailingContent = progress,
                        colors = clear,
                        modifier = Modifier.clickable(enabled = !busy && team != null) { sharing = true },
                    )
                }
            } else {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.team_server)) },
                        supportingContent = { Text(team?.text("server").orEmpty()) },
                        leadingContent = { Icon(Icons.Rounded.Dns, null) },
                        colors = clear,
                    )
                }
                item {
                    val waiting = team?.number("waiting") ?: 0
                    val rejected = team?.number("rejected") ?: 0
                    val state = listOfNotNull(
                        pluralStringResource(R.plurals.team_waiting, waiting, waiting).takeIf { waiting > 0 },
                        pluralStringResource(R.plurals.team_rejected, rejected, rejected).takeIf { rejected > 0 },
                    ).joinToString(" · ").ifEmpty { stringResource(R.string.team_up_to_date) }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.team_sync_now)) },
                        supportingContent = { Text(state) },
                        leadingContent = { Icon(Icons.Rounded.Sync, null) },
                        trailingContent = progress,
                        colors = clear,
                        modifier = Modifier.clickable(enabled = !busy) { syncNow() },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.team_invite)) },
                        supportingContent = invite?.let { code -> { Text(code) } },
                        leadingContent = { Icon(Icons.Rounded.PersonAdd, null) },
                        colors = clear,
                        modifier = Modifier.clickable(enabled = !busy) { showInvite() },
                    )
                }
            }
        }
    }

    if (sharing) {
        ShareDialog(
            project = project,
            onDismiss = { sharing = false },
            onShare = { server, name, nickname ->
                sharing = false
                share(server, name, nickname)
            },
        )
    }
    shownInvite?.let { code -> InviteDialog(code, onDismiss = { shownInvite = null }) }

    restoring?.let { day ->
        AppDialog(
            onDismissRequest = { restoring = null },
            icon = { Icon(Icons.Rounded.History, null) },
            title = { Text(stringResource(R.string.snapshot_restore_title, dayText(day))) },
            text = { Text(stringResource(R.string.snapshot_restore_body)) },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    scope.launch {
                        // Back to the map, which shows the project as restored.
                        when (val done = graph.invoke(Workspace.RESTORE, buildJsonObject { put("day", day) })) {
                            is Outcome.Done -> onBack()
                            else -> failure = texts.of(done)
                        }
                    }
                }) { Text(stringResource(R.string.snapshot_restore), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (choosingPlacing) {
        // Ask each time, or one of the point layers, as "remember" on the map chose.
        val choices = listOf<LayerInfo?>(null) + layers
        AppDialog(
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
        AppDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(message) },
        )
    }
}

// A snapshot's day as the phone writes dates: 2026年11月27日, November 27, 2026.
@Composable
private fun dayText(day: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)) }.getOrDefault(day)
}

private fun JsonObject?.flag(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.contentOrNull == "true"

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
