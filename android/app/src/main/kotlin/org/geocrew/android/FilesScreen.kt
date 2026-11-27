package org.geocrew.android

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.core.files.FileActions

/** A file or folder as file.list gives it. */
private data class FileEntry(val name: String, val path: String, val kind: String, val modified: Long, val size: Long?, val items: Int?) {
    val folder: Boolean get() = kind == "folder"
}

/** A folder as file.list gives it: where it is, the folder above it (null at the top) and what is in it. */
private data class Listing(val path: String, val parent: String?, val entries: List<FileEntry>)

/** A file or folder on its way to the folder the user goes to: a copy, or with [move] a move. */
private data class Clip(val entry: FileEntry, val move: Boolean)

/** A place to start from: the GeoCrew folder, downloads, WeChat's files, the whole storage. */
private data class Place(val label: Int, val folder: File)

/**
 * The built-in file manager (ADR 0011): the GeoCrew folder first, and with
 * all-files access the whole shared storage. Folders open with a tap; a file,
 * or a folder's menu, offers to open it in another app, share, copy or move it
 * (pasted in the folder the user goes to), rename and delete it. Every change
 * goes through the file actions (ADR 0010); the open project's files stay as
 * they are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf(graph.root.path) }
    // The folders gone through, so back goes to the last one.
    var history by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var listing by remember { mutableStateOf<Listing?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var loads by remember { mutableIntStateOf(0) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<FileEntry?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
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

    fun go(to: String) {
        if (to == path) return
        history = history + path
        path = to
    }

    LaunchedEffect(path, loads, hidden) {
        when (val listed = graph.invoke(FileActions.LIST, buildJsonObject { put("path", path); put("hidden", hidden) })) {
            is Outcome.Done -> {
                listing = listingOf(listed.output.jsonObject)
                unreadable = false
            }
            else -> {
                listing = null
                unreadable = true
            }
        }
    }

    // A change through the file actions, then the folder again; a refusal is said.
    fun act(action: String, params: JsonObject, then: () -> Unit = {}) = scope.launch {
        when (val outcome = graph.invoke(action, params)) {
            is Outcome.Done -> {
                loads++
                then()
            }
            else -> failure = texts.of(outcome)
        }
    }

    // Back to the last folder still there: one renamed, moved or deleted since is passed over.
    BackHandler(enabled = history.isNotEmpty()) {
        val back = history.dropLastWhile { !File(it).isDirectory }
        path = back.lastOrNull() ?: graph.root.path
        history = back.dropLast(1)
    }

    val sort = graph.settings.fileSort
    val shown = listing?.entries.orEmpty().let { entries ->
        when (sort) {
            FileSort.NAME -> entries
            FileSort.TIME -> entries.sortedWith(compareBy<FileEntry> { !it.folder }.thenByDescending { it.modified })
            FileSort.SIZE -> entries.sortedWith(compareBy<FileEntry> { !it.folder }.thenByDescending { it.size ?: it.items?.toLong() ?: 0L })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.files)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = {
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
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { making = true }) { Icon(Icons.Rounded.CreateNewFolder, stringResource(R.string.files_new_folder)) }
        },
        bottomBar = {
            clip?.let { c ->
                ClipBar(
                    clip = c,
                    // Not into the folder it is in (for a move), nor into itself.
                    canPaste = !(c.move && File(c.entry.path).parent == path) && path != c.entry.path && !path.startsWith(c.entry.path + "/"),
                    onCancel = { clip = null },
                    onPaste = {
                        val to = path
                        act(if (c.move) FileActions.MOVE else FileActions.COPY, buildJsonObject { put("path", c.entry.path); put("to", to) }) {
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
            if (places.size > 1) {
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
                listing != null && shown.isEmpty() -> Empty(Icons.Rounded.FolderOpen, stringResource(R.string.files_empty))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(detailOf(context, entry)) },
                            leadingContent = { KindIcon(entry.kind) },
                            trailingContent = {
                                IconButton(onClick = { chosen = entry }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.files_more)) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { if (entry.folder) go(entry.path) else chosen = entry },
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
            onShare = {
                chosen = null
                share(context, File(entry.path), shareText)
            },
            onCopy = {
                chosen = null
                clip = Clip(entry, move = false)
            },
            onMove = {
                chosen = null
                clip = Clip(entry, move = true)
            },
            onRename = {
                chosen = null
                renaming = entry
            },
            onDelete = {
                chosen = null
                deleting = entry
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
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.files_delete_title, entry.name)) },
            text = if ((entry.items ?: 0) > 0) ({ Text(pluralStringResource(R.plurals.files_delete_items, entry.items ?: 0, entry.items ?: 0)) }) else null,
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    act(FileActions.DELETE, buildJsonObject { put("path", entry.path) }) { if (clip?.entry?.path == entry.path) clip = null }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
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

/** How the file actions' refusals are said. */
private class FileTexts(val taken: String, val busy: String, val protected: String, val inside: String, val gone: String, val failed: String) {
    fun of(outcome: Outcome): String = when (outcome) {
        is Outcome.Failed -> when (outcome.code) {
            FileActions.EXISTS -> taken
            FileActions.BUSY -> busy
            FileActions.PROTECTED -> protected
            FileActions.INSIDE -> inside
            FileActions.NOT_FOUND -> gone
            else -> failed.format(outcome.message)
        }
        else -> failed.format(outcome.toString())
    }
}

private fun listingOf(o: JsonObject): Listing = Listing(
    path = (o["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    parent = (o["parent"] as? JsonPrimitive)?.contentOrNull,
    entries = o["entries"]?.jsonArray.orEmpty().map { e ->
        val f = e.jsonObject
        FileEntry(
            name = (f["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            path = (f["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            kind = (f["kind"] as? JsonPrimitive)?.contentOrNull ?: "other",
            modified = (f["modified"] as? JsonPrimitive)?.longOrNull ?: 0,
            size = (f["size"] as? JsonPrimitive)?.longOrNull,
            items = (f["items"] as? JsonPrimitive)?.intOrNull,
        )
    },
)

// A folder's item count, or a file's size, then when it last changed: the year only when it is not this one.
@Composable
private fun detailOf(context: Context, entry: FileEntry): String {
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val year = Calendar.getInstance().apply { timeInMillis = entry.modified }.get(Calendar.YEAR)
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or
        (if (year != thisYear) DateUtils.FORMAT_SHOW_YEAR else 0)
    val size = if (entry.folder) pluralStringResource(R.plurals.files_items, entry.items ?: 0, entry.items ?: 0) else Formatter.formatShortFileSize(context, entry.size ?: 0)
    return size + " · " + DateUtils.formatDateTime(context, entry.modified, flags)
}

/** What a file is, as an icon in a circle: folders, vector and raster data, pictures, archives, documents. */
@Composable
private fun KindIcon(kind: String) {
    val (icon, container) = when (kind) {
        "folder" -> Icons.Rounded.Folder to MaterialTheme.colorScheme.secondaryContainer
        "vector" -> Icons.Rounded.Layers to MaterialTheme.colorScheme.primaryContainer
        "raster" -> Icons.Rounded.Terrain to MaterialTheme.colorScheme.tertiaryContainer
        "image" -> Icons.Rounded.Image to MaterialTheme.colorScheme.surfaceContainerHighest
        "archive" -> Icons.Rounded.FolderZip to MaterialTheme.colorScheme.surfaceContainerHighest
        "document" -> Icons.Rounded.Description to MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile to MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, modifier = Modifier.size(22.dp))
    }
}

/** Where the folder is: each folder above it, back to the top, to tap; the last is this one. */
@Composable
private fun Crumbs(path: String, top: String, topLabel: String, onGo: (String) -> Unit) {
    val names = if (path == top || path.startsWith("$top/")) path.removePrefix(top).split("/").filter { it.isNotEmpty() } else emptyList()
    val folders = names.indices.map { i -> top + "/" + names.take(i + 1).joinToString("/") }
    val crumbs = listOf(topLabel to top) + names.zip(folders)
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, (label, folder) ->
            if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = i == crumbs.lastIndex
            TextButton(onClick = { onGo(folder) }, enabled = !last) {
                Text(
                    label,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Empty(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What can be done with a file or folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSheet(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            ListItem(
                headlineContent = { Text(entry.name, style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(detailOf(context, entry)) },
                leadingContent = { KindIcon(entry.kind) },
                colors = clear,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            @Composable
            fun item(label: Int, icon: ImageVector, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.onSurface) = ListItem(
                headlineContent = { Text(stringResource(label), color = color) },
                leadingContent = { Icon(icon, null, tint = color) },
                colors = clear,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            )
            item(R.string.files_open, if (entry.folder) Icons.Rounded.FolderOpen else Icons.AutoMirrored.Rounded.OpenInNew, onOpen)
            if (!entry.folder) item(R.string.share, Icons.Rounded.Share, onShare)
            item(R.string.files_copy, Icons.Rounded.ContentCopy, onCopy)
            item(R.string.files_move, Icons.AutoMirrored.Rounded.DriveFileMove, onMove)
            item(R.string.files_rename, Icons.Rounded.DriveFileRenameOutline, onRename)
            item(R.string.delete, Icons.Rounded.Delete, onDelete, MaterialTheme.colorScheme.error)
        }
    }
}

/** The bar shown while a file is on its way: what goes, and paste it here or not. */
@Composable
private fun ClipBar(clip: Clip, canPaste: Boolean, onCancel: () -> Unit, onPaste: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        // Clear of the navigation bar, which the screen runs under.
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (clip.move) Icons.AutoMirrored.Rounded.DriveFileMove else Icons.Rounded.ContentCopy, null)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (clip.move) R.string.files_moving else R.string.files_copying, clip.entry.name),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            FilledTonalButton(onClick = onPaste, enabled = canPaste) {
                Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.files_paste))
            }
        }
    }
}

/**
 * A name for a file or folder: [initial] to start with, not one of [taken]
 * (in any case), no slash, no space at either end.
 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    taken: List<String>,
    confirm: String,
    selectBase: Boolean,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val end = if (selectBase) initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val name = value.text.trim()
    val clash = taken.any { it.equals(name, ignoreCase = true) }
    val valid = name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !clash
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.files_name)) },
                singleLine = true,
                isError = clash,
                supportingText = if (clash) ({ Text(stringResource(R.string.files_name_taken)) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The kind of data in [file], as other apps know it. */
internal fun mimeOf(file: File): String = when (val extension = file.extension.lowercase()) {
    "gpkg" -> "application/geopackage+sqlite3"
    "kml" -> "application/vnd.google-earth.kml+xml"
    "kmz" -> "application/vnd.google-earth.kmz"
    "geojson" -> "application/geo+json"
    "gpx" -> "application/gpx+xml"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

/** Opens [file] in an app the user picks. */
internal fun openWith(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(view, title)) }
}

/** How the file manager lists a folder: by name, newest first, or largest first; folders always come first. */
enum class FileSort { NAME, TIME, SIZE }
