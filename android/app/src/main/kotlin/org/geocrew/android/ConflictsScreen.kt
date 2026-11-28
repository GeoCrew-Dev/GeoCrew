package org.geocrew.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.storage.SyncActions

// Kinds of conflict (protocol v1 6.5).
private const val VALUE = "value"
private const val DELETED_FIELD = "deleted_field"
private const val DELETE_REJECTED = "delete_rejected"

/**
 * The team's conflicts in [project] (protocol v1 6.5): where each is, the
 * value kept and the one that lost, and the choice between them. A feature
 * this phone has opens on the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(graph: AppGraph, project: String, onBack: () -> Unit, onFeature: (layer: String, fid: Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var conflicts by remember { mutableStateOf<List<JsonObject>?>(null) }
    // The conflict being settled: one at a time, as the server answers.
    var settling by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val sync = syncTexts()
    val texts = FailureTexts(
        unsaved = stringResource(R.string.failure_unsaved),
        failed = stringResource(R.string.action_failed),
        start = stringResource(R.string.start_failed),
    )

    suspend fun load() {
        val done = graph.invoke(SyncActions.CONFLICTS)
        if (done !is Outcome.Done) failure = sync.of(done)
        conflicts = (done as? Outcome.Done)?.output?.jsonObject?.get("conflicts")?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    LaunchedEffect(project) {
        val opened = graph.open(project)
        if (opened !is Outcome.Done) {
            failure = texts.of(opened)
            conflicts = emptyList()
            return@LaunchedEffect
        }
        load()
    }

    fun settle(id: String, choose: String) = scope.launch {
        settling = id
        val done = graph.invoke(SyncActions.RESOLVE, buildJsonObject { put("conflict", id); put("choose", choose) })
        settling = null
        if (done !is Outcome.Done) failure = sync.of(done)
        load()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflicts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        val shown = conflicts
        when {
            shown == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.conflicts_none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.text("id").orEmpty() }) { c ->
                    val id = c.text("id").orEmpty()
                    val layer = c.text("layer")
                    val fid = (c["fid"] as? JsonPrimitive)?.longOrNull
                    ConflictCard(
                        c,
                        settling = settling == id,
                        enabled = settling == null,
                        onSettle = { settle(id, it) },
                        onShow = if (layer != null && fid != null) ({ onFeature(layer, fid) }) else null,
                    )
                }
            }
        }
    }

    failure?.let { message ->
        AppDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(message) },
        )
    }
}

@Composable
private fun ConflictCard(c: JsonObject, settling: Boolean, enabled: Boolean, onSettle: (String) -> Unit, onShow: (() -> Unit)?) {
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    val geometry = (c["geometry"] as? JsonPrimitive)?.booleanOrNull == true
    val kind = c.text("kind")
    val feature = c.text("name") ?: (c["fid"] as? JsonPrimitive)?.longOrNull?.let { "#$it" }
    val field = if (geometry) stringResource(R.string.conflict_location) else c.text("field")
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(listOfNotNull(feature, field).joinToString(" · ").ifEmpty { c.text("layer").orEmpty() }) },
            supportingContent = c.text("layer")?.takeIf { feature != null || field != null }?.let { { Text(it) } },
            trailingContent = if (settling) ({ CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }) else null,
            modifier = onShow?.let { Modifier.clickable(onClick = it) } ?: Modifier,
            colors = clear,
        )
        val kept = c["kept"] as? JsonObject
        val lost = c["lost"] as? JsonObject
        when (kind) {
            VALUE -> {
                Side(stringResource(R.string.conflict_now), kept, geometry)
                Side(stringResource(R.string.conflict_other), lost, geometry)
                Choices(enabled, keep = stringResource(R.string.conflict_keep), other = stringResource(R.string.conflict_use_other), onSettle)
            }
            DELETED_FIELD -> {
                Side(stringResource(R.string.conflict_field_deleted), lost, geometry)
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onSettle(SyncActions.KEEP) }, enabled = enabled) { Text(stringResource(R.string.ok)) }
                }
            }
            else -> {
                // A deletion refused, or undone by someone's edit: whose it was and when, kept or done after all.
                val what = if (kind == DELETE_REJECTED) R.string.conflict_delete_rejected else R.string.conflict_delete_overridden
                ListItem(
                    headlineContent = { Text(stringResource(what)) },
                    supportingContent = whoAndWhen(lost)?.let { { Text(it) } },
                    colors = clear,
                )
                Choices(enabled, keep = stringResource(R.string.conflict_keep), other = stringResource(R.string.delete), onSettle)
            }
        }
    }
}

// One side of a conflict: its value, and whose it is and when.
@Composable
private fun Side(label: String, side: JsonObject?, geometry: Boolean) {
    if (side == null) return
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(valueText(side["value"], geometry)) },
        supportingContent = whoAndWhen(side)?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun Choices(enabled: Boolean, keep: String, other: String, onSettle: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = { onSettle(SyncActions.KEEP) }, enabled = enabled) { Text(keep) }
        FilledTonalButton(onClick = { onSettle(SyncActions.TAKE_LOST) }, enabled = enabled) { Text(other) }
    }
}

@Composable
private fun whoAndWhen(side: JsonObject?): String? {
    if (side == null) return null
    val who = if ((side["mine"] as? JsonPrimitive)?.booleanOrNull == true) stringResource(R.string.conflict_me) else side.text("by")
    return listOfNotNull(who, side.text("at")?.let(::localTime)).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun valueText(value: JsonElement?, geometry: Boolean): String = when {
    value == null || value is JsonNull -> stringResource(R.string.conflict_empty)
    geometry -> pointText(value) ?: stringResource(R.string.conflict_shape)
    value is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() } ?: stringResource(R.string.conflict_empty)
    else -> value.toString()
}

// A point's longitude and latitude, or null for another kind of geometry.
private fun pointText(value: JsonElement): String? {
    val o = value as? JsonObject ?: return null
    if ((o["type"] as? JsonPrimitive)?.contentOrNull != "Point") return null
    val c = runCatching { o.getValue("coordinates").jsonArray }.getOrNull() ?: return null
    val x = (c.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
    val y = (c.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
    return String.format(Locale.ROOT, "%.6f, %.6f", x, y)
}

// An RFC 3339 time as this phone shows a date and time.
private fun localTime(at: String): String? = runCatching {
    OffsetDateTime.parse(at).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
}.getOrNull()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
