package org.geocrew.android

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.core.files.FileActions

/**
 * The built-in file manager (ADR 0011), one list in the MD3 way: the GeoCrew
 * folder first, and with all-files access the whole shared storage. A tap
 * opens a folder or offers what can be done with a file; a long press picks
 * it, and then taps pick more, for copying, moving, sharing, renaming,
 * deleting or seeing their details together. Copies and moves are pasted in
 * the folder the user goes to. The folder can be searched by name. Every
 * change goes through the file actions (ADR 0010); the open project's files
 * stay as they are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var path by rememberSaveable { mutableStateOf(graph.root.path) }
    // The folders gone through, so back goes to the last one.
    var history by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var listing by remember { mutableStateOf<Listing?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var loads by remember { mutableIntStateOf(0) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    // What is picked, by path: a long press starts picking, and it ends when nothing is picked.
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Searching this folder by name: what is typed; null when not searching.
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<FileEntry?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<List<FileEntry>?>(null) }
    var details by remember { mutableStateOf<List<FileEntry>?>(null) }
    var making by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val texts = FileTexts(
        taken = stringResource(R.string.files_name_taken),
        busy = stringResource(R.string.files_busy),
        protected = stringResource(R.string.files_protected),
        inside = stringResource(R.string.files_inside),
        gone = stringResource(R.string.files_gone),
        failed = stringResource(R.string.action_failed),
    )
    val copiedText = stringResource(R.string.files_copied)
    val movedText = stringResource(R.string.files_moved)
    val shareText = stringResource(R.string.share)
    val openWithText = stringResource(R.string.files_open_with)

    val storage = remember { Environment.getExternalStorageDirectory() }
    val places = remember(graph.hasAllFilesAccess) {
        buildList {
            add(Place(R.string.files_geocrew, graph.root))
            if (graph.hasAllFilesAccess) {
                File(storage, Environment.DIRECTORY_DOWNLOADS).takeIf { it.isDirectory }?.let { add(Place(R.string.files_downloads, it)) }
                File(storage, "Download/WeiXin").takeIf { it.isDirectory }?.let { add(Place(R.string.files_wechat, it)) }
                add(Place(R.string.files_storage, storage))
            }
        }
    }

    // Into another folder: what was picked or searched for here does not go along.
    fun go(to: String) {
        if (to == path) return
        history = history + path
        path = to
        picked = emptySet()
        query = null
    }

    LaunchedEffect(path, loads, hidden) {
        when (val listed = graph.invoke(FileActions.LIST, buildJsonObject { put("path", path); put("hidden", hidden) })) {
            is Outcome.Done -> {
                val read = listingOf(listed.output.jsonObject)
                listing = read
                unreadable = false
                // What has gone (renamed, moved, deleted) is no longer picked.
                picked = picked intersect read.entries.map { it.path }.toSet()
            }
            else -> {
                listing = null
                unreadable = true
                picked = emptySet()
            }
        }
    }

    // Runs [action] once for each of [runs], then reads the folder again. The first refusal is said; the rest still run.
    fun actOn(action: String, runs: List<JsonObject>, then: () -> Unit = {}) = scope.launch {
        var refused: Outcome? = null
        var done = 0
        for (params in runs) {
            when (val outcome = graph.invoke(action, params)) {
                is Outcome.Done -> done++
                else -> if (refused == null) refused = outcome
            }
        }
        loads++
        refused?.let { failure = texts.of(it) }
        if (done > 0) then()
    }

    fun act(action: String, params: JsonObject, then: () -> Unit = {}) = actOn(action, listOf(params), then)

    fun pick(entry: FileEntry) {
        picked = if (entry.path in picked) picked - entry.path else picked + entry.path
    }

    // Back first puts down what is picked, then ends the search, then goes to the last folder still there.
    BackHandler(enabled = history.isNotEmpty()) {
        val back = history.dropLastWhile { !File(it).isDirectory }
        path = back.lastOrNull() ?: graph.root.path
        history = back.dropLast(1)
        picked = emptySet()
    }
    BackHandler(enabled = query != null) { query = null }
    BackHandler(enabled = picked.isNotEmpty()) { picked = emptySet() }

    val sort = graph.settings.fileSort
    val sorted = listing?.entries.orEmpty().let { entries ->
        when (sort) {
            FileSort.NAME -> entries
            FileSort.TIME -> entries.sortedWith(compareBy<FileEntry> { !it.folder }.thenByDescending { it.modified })
            FileSort.SIZE -> entries.sortedWith(compareBy<FileEntry> { !it.folder }.thenByDescending { it.size ?: it.items?.toLong() ?: 0L })
        }
    }
    val shown = query?.trim()?.takeIf { it.isNotEmpty() }?.let { q -> sorted.filter { it.name.contains(q, ignoreCase = true) } } ?: sorted
    // Only what is shown counts as picked: a search that hides a picked file leaves it alone.
    val pickedEntries = shown.filter { it.path in picked }

    Scaffold(
        topBar = {
            when {
                picked.isNotEmpty() -> TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.files_picked, pickedEntries.size, pickedEntries.size)) },
                    navigationIcon = { IconButton(onClick = { picked = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) } },
                    actions = {
                        val all = shown.map { it.path }.toSet()
                        IconButton(onClick = { picked = if (picked.containsAll(all)) emptySet() else all }) {
                            Icon(Icons.Rounded.SelectAll, stringResource(R.string.files_pick_all))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                query != null -> TopAppBar(
                    title = { SearchField(query.orEmpty(), onChange = { query = it }) },
                    navigationIcon = { IconButton(onClick = { query = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                    actions = {
                        if (!query.isNullOrEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.files_clear)) }
                    },
                )
                else -> TopAppBar(
                    title = { Text(stringResource(R.string.files)) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                    actions = {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Search, stringResource(R.string.files_search)) }
                        Box {
                            IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.files_sort)) }
                            DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                                for ((s, label) in listOf(FileSort.NAME to R.string.files_sort_name, FileSort.TIME to R.string.files_sort_time, FileSort.SIZE to R.string.files_sort_size)) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        leadingIcon = { RadioButton(selected = sort == s, onClick = null) },
                                        onClick = {
                                            sorting = false
                                            graph.settings.sortFilesBy(s)
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_hidden)) },
                                    leadingIcon = { Checkbox(checked = hidden, onCheckedChange = null) },
                                    onClick = {
                                        sorting = false
                                        hidden = !hidden
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (picked.isEmpty()) {
                FloatingActionButton(onClick = { making = true }) { Icon(Icons.Rounded.CreateNewFolder, stringResource(R.string.files_new_folder)) }
            }
        },
        bottomBar = {
            val c = clip
            when {
                picked.isNotEmpty() -> PickBar(
                    count = pickedEntries.size,
                    folders = pickedEntries.any { it.folder },
                    onCopy = {
                        clip = Clip(pickedEntries, move = false)
                        picked = emptySet()
                    },
                    onMove = {
                        clip = Clip(pickedEntries, move = true)
                        picked = emptySet()
                    },
                    onShare = { shareAll(context, pickedEntries.map { File(it.path) }, shareText) },
                    onRename = { pickedEntries.singleOrNull()?.let { renaming = it } },
                    onDetails = { details = pickedEntries },
                    onDelete = { deleting = pickedEntries },
                )
                c != null -> ClipBar(
                    clip = c,
                    // Not into itself, nor (for a move) all into the folder they are in.
                    canPaste = c.entries.none { path == it.path || path.startsWith(it.path + "/") } &&
                        !(c.move && c.entries.all { File(it.path).parent == path }),
                    onCancel = { clip = null },
                    onPaste = {
                        val to = path
                        actOn(if (c.move) FileActions.MOVE else FileActions.COPY, c.entries.map { e -> buildJsonObject { put("path", e.path); put("to", to) } }) {
                            clip = null
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar((if (c.move) movedText else copiedText).format(File(to).name))
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (places.size > 1 && query == null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (place in places) {
                        // The place the folder is in: the deepest one holding it.
                        val here = places.filter { path == it.folder.path || path.startsWith(it.folder.path + "/") }.maxByOrNull { it.folder.path.length } == place
                        FilterChip(selected = here, onClick = { go(place.folder.path) }, label = { Text(stringResource(place.label)) })
                    }
                }
            }
            Crumbs(
                path = path,
                top = if (graph.hasAllFilesAccess) storage.path else graph.root.path,
                topLabel = stringResource(if (graph.hasAllFilesAccess) R.string.files_storage else R.string.files_geocrew),
                onGo = ::go,
            )
            HorizontalDivider()
            when {
                unreadable -> Empty(Icons.Rounded.FolderOpen, stringResource(R.string.files_unreadable))
                listing != null && sorted.isEmpty() -> Empty(Icons.Rounded.FolderOpen, stringResource(R.string.files_empty))
                listing != null && shown.isEmpty() -> Empty(Icons.Rounded.SearchOff, stringResource(R.string.files_not_found))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { entry ->
                        val isPicked = entry.path in picked
                        ListItem(
                            headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(detailOf(context, entry)) },
                            leadingContent = { if (isPicked) PickedIcon() else KindIcon(entry.kind) },
                            trailingContent = if (picked.isEmpty()) {
                                { IconButton(onClick = { chosen = entry }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.files_more)) } }
                            } else {
                                null
                            },
                            colors = ListItemDefaults.colors(containerColor = if (isPicked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    when {
                                        picked.isNotEmpty() -> pick(entry)
                                        entry.folder -> go(entry.path)
                                        else -> chosen = entry
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pick(entry)
                                },
                            ),
                        )
                    }
                    // Clear of the button for a new folder.
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    chosen?.let { entry ->
        FileSheet(
            entry = entry,
            onDismiss = { chosen = null },
            onOpen = {
                chosen = null
                if (entry.folder) go(entry.path) else openWith(context, File(entry.path), openWithText)
            },
            onPick = {
                chosen = null
                picked = setOf(entry.path)
            },
            onShare = {
                chosen = null
                shareAll(context, listOf(File(entry.path)), shareText)
            },
            onCopy = {
                chosen = null
                clip = Clip(listOf(entry), move = false)
            },
            onMove = {
                chosen = null
                clip = Clip(listOf(entry), move = true)
            },
            onRename = {
                chosen = null
                renaming = entry
            },
            onDetails = {
                chosen = null
                details = listOf(entry)
            },
            onDelete = {
                chosen = null
                deleting = listOf(entry)
            },
        )
    }
    renaming?.let { entry ->
        NameDialog(
            title = stringResource(R.string.files_rename),
            initial = entry.name,
            taken = listing?.entries.orEmpty().map { it.name }.filter { !it.equals(entry.name, ignoreCase = true) },
            confirm = stringResource(R.string.files_rename),
            // The name without its extension is selected, ready to type over.
            selectBase = !entry.folder,
            onDismiss = { renaming = null },
            onDone = { name ->
                renaming = null
                picked = emptySet()
                if (name != entry.name) act(FileActions.RENAME, buildJsonObject { put("path", entry.path); put("name", name) })
            },
        )
    }
    if (making) {
        val base = stringResource(R.string.files_new_folder)
        val names = listing?.entries.orEmpty().map { it.name }
        NameDialog(
            title = stringResource(R.string.files_new_folder),
            initial = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { n -> names.none { it.equals(n, ignoreCase = true) } },
            taken = names,
            confirm = stringResource(R.string.files_create),
            selectBase = false,
            onDismiss = { making = false },
            onDone = { name ->
                making = false
                act(FileActions.MAKE_FOLDER, buildJsonObject { put("path", path); put("name", name) })
            },
        )
    }
    deleting?.let { entries ->
        val one = entries.singleOrNull()
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = {
                Text(if (one != null) stringResource(R.string.files_delete_title, one.name) else pluralStringResource(R.plurals.files_delete_many, entries.size, entries.size))
            },
            // A folder says how much goes with it.
            text = if (one != null && (one.items ?: 0) > 0) ({ Text(pluralStringResource(R.plurals.files_delete_items, one.items ?: 0, one.items ?: 0)) }) else null,
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    picked = emptySet()
                    val gone = entries.map { it.path }.toSet()
                    actOn(FileActions.DELETE, gone.map { p -> buildJsonObject { put("path", p) } }) {
                        // What was deleted is no longer on its way anywhere.
                        clip = clip?.let { c -> c.copy(entries = c.entries.filter { it.path !in gone }).takeIf { it.entries.isNotEmpty() } }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    details?.let { entries ->
        DetailsDialog(entries, info = { p -> (graph.invoke(FileActions.INFO, buildJsonObject { put("path", p) }) as? Outcome.Done)?.output?.jsonObject }) { details = null }
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(message) },
        )
    }
}

/** How the file manager lists a folder: by name, newest first, or largest first; folders always come first. */
enum class FileSort { NAME, TIME, SIZE }
