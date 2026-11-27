package org.geocrew.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.Calendar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.geocrew.core.action.Outcome
import org.geocrew.core.files.FileActions

/** A file or folder as file.list gives it. */
internal data class FileEntry(val name: String, val path: String, val kind: String, val modified: Long, val size: Long?, val items: Int?) {
    val folder: Boolean get() = kind == "folder"
}

/** A folder as file.list gives it: where it is, the folder above it (null at the top) and what is in it. */
internal data class Listing(val path: String, val parent: String?, val entries: List<FileEntry>)

/** Files and folders on their way to the folder the user goes to: a copy, or with [move] a move. */
internal data class Clip(val entries: List<FileEntry>, val move: Boolean)

/** A place to start from: the GeoCrew folder, downloads, WeChat's files, the whole storage. */
internal data class Place(val label: Int, val folder: File)

/** How the file actions' refusals are said. */
internal class FileTexts(val taken: String, val busy: String, val protected: String, val inside: String, val gone: String, val failed: String) {
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

internal fun listingOf(o: JsonObject): Listing = Listing(
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

// When something last changed, in the phone's language: the year only when it is not this one.
internal fun dateOf(context: Context, time: Long): String {
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val year = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR)
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or
        (if (year != thisYear) DateUtils.FORMAT_SHOW_YEAR else 0)
    return DateUtils.formatDateTime(context, time, flags)
}

// A folder's item count, or a file's size, then when it last changed.
@Composable
internal fun detailOf(context: Context, entry: FileEntry): String {
    val size = if (entry.folder) pluralStringResource(R.plurals.files_items, entry.items ?: 0, entry.items ?: 0) else Formatter.formatShortFileSize(context, entry.size ?: 0)
    return size + " · " + dateOf(context, entry.modified)
}

/** What a file is, as an icon in a circle: folders, vector and raster data, pictures, archives, documents. */
@Composable
internal fun KindIcon(kind: String) {
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

/** A picked file's icon: a check in the theme's main colour. */
@Composable
internal fun PickedIcon() {
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
    }
}

/** Where the folder is: each folder above it, back to the top, to tap; the last is this one. */
@Composable
internal fun Crumbs(path: String, top: String, topLabel: String, onGo: (String) -> Unit) {
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
internal fun Empty(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What to look for in the folder's names, typed in the top bar; the keyboard comes up at once. */
@Composable
internal fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.files_search_hint)) },
        singleLine = true,
        // The list follows what is typed; the search key only puts the keyboard away.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/** What can be done with one file or folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileSheet(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPick: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
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
            item(R.string.files_pick, Icons.Rounded.CheckCircleOutline, onPick)
            if (!entry.folder) item(R.string.share, Icons.Rounded.Share, onShare)
            item(R.string.files_copy, Icons.Rounded.ContentCopy, onCopy)
            item(R.string.files_move, Icons.AutoMirrored.Rounded.DriveFileMove, onMove)
            item(R.string.files_rename, Icons.Rounded.DriveFileRenameOutline, onRename)
            item(R.string.files_details, Icons.Rounded.Info, onDetails)
            item(R.string.delete, Icons.Rounded.Delete, onDelete, MaterialTheme.colorScheme.error)
        }
    }
}

/** What can be done with what is picked, each a labelled button along the bottom. */
@Composable
internal fun PickBar(
    count: Int,
    folders: Boolean,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp)) {
            PickAction(R.string.files_copy, Icons.Rounded.ContentCopy, onCopy)
            PickAction(R.string.files_move, Icons.AutoMirrored.Rounded.DriveFileMove, onMove)
            // Folders are not shared; only one thing is renamed at a time.
            PickAction(R.string.share, Icons.Rounded.Share, onShare, enabled = !folders)
            PickAction(R.string.files_rename, Icons.Rounded.DriveFileRenameOutline, onRename, enabled = count == 1)
            PickAction(R.string.files_details, Icons.Rounded.Info, onDetails)
            PickAction(R.string.delete, Icons.Rounded.Delete, onDelete, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RowScope.PickAction(label: Int, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface) {
    val shown = if (enabled) color else color.copy(alpha = 0.38f)
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = shown)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = shown, maxLines = 1)
    }
}

/** The bar shown while files are on their way: what goes, and paste it here or not. */
@Composable
internal fun ClipBar(clip: Clip, canPaste: Boolean, onCancel: () -> Unit, onPaste: () -> Unit) {
    val one = clip.entries.singleOrNull()
    val what = when {
        one != null -> stringResource(if (clip.move) R.string.files_moving else R.string.files_copying, one.name)
        else -> pluralStringResource(if (clip.move) R.plurals.files_moving_many else R.plurals.files_copying_many, clip.entries.size, clip.entries.size)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        // Clear of the navigation bar, which the screen runs under.
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (clip.move) Icons.AutoMirrored.Rounded.DriveFileMove else Icons.Rounded.ContentCopy, null)
            Spacer(Modifier.width(12.dp))
            Text(what, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
internal fun NameDialog(
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

/**
 * The details of [entries]: for one, where it is (the path can be copied) and
 * when it changed; for all, how big they are and how many files and folders
 * they hold, counted through [info] (file.info) while the dialog shows. One
 * GIS file also says what GDAL makes of it, read through [probe] (file.probe,
 * in a process of its own): a raster's format, pixels, bands, pixel size, CRS
 * and extent, vector data's layers.
 */
@Composable
internal fun DetailsDialog(
    entries: List<FileEntry>,
    info: suspend (String) -> JsonObject?,
    probe: (suspend (String) -> JsonObject?)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var counted by remember { mutableStateOf<List<JsonObject>?>(null) }
    LaunchedEffect(entries) { counted = entries.mapNotNull { info(it.path) } }
    val one = entries.singleOrNull()
    fun total(key: String) = counted?.sumOf { (it[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { if (one != null) KindIcon(one.kind) else Icon(Icons.Rounded.Info, null) },
        title = {
            Text(
                one?.name ?: pluralStringResource(R.plurals.files_picked, entries.size, entries.size),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            // A file with many layers says a lot: it scrolls.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (one != null) {
                    Detail(stringResource(R.string.files_location), File(one.path).parent.orEmpty()) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(one.path)) }) {
                            Icon(Icons.Rounded.ContentCopy, stringResource(R.string.files_copy_path), modifier = Modifier.size(20.dp))
                        }
                    }
                    Detail(stringResource(R.string.files_modified), dateOf(context, one.modified))
                }
                val size = total("size")
                Detail(stringResource(R.string.files_size), size?.let { Formatter.formatFileSize(context, it) } ?: stringResource(R.string.files_counting))
                // What a folder holds, or what was picked with all it holds: so many files, so many folders.
                if (one == null || one.folder) {
                    val files = total("files")?.plus(if (one != null) 0 else entries.count { !it.folder })
                    val folders = total("folders")?.plus(if (one != null) 0 else entries.count { it.folder })
                    val held = if (files == null || folders == null) {
                        stringResource(R.string.files_counting)
                    } else {
                        stringResource(
                            R.string.files_contains_value,
                            pluralStringResource(R.plurals.files_count_files, files.toInt(), files.toInt()),
                            pluralStringResource(R.plurals.files_count_folders, folders.toInt(), folders.toInt()),
                        )
                    }
                    Detail(stringResource(R.string.files_contains), held)
                }
                if (one != null && probe != null && (one.kind == "raster" || one.kind == "vector")) {
                    var read by remember { mutableStateOf<JsonObject?>(null) }
                    var unreadable by remember { mutableStateOf(false) }
                    LaunchedEffect(one.path) {
                        val got = probe(one.path)
                        if (got == null) unreadable = true else read = got
                    }
                    val got = read
                    when {
                        unreadable -> Detail(stringResource(R.string.files_format), stringResource(R.string.files_cannot_read))
                        got == null -> Detail(stringResource(R.string.files_format), stringResource(R.string.files_reading))
                        else -> GisDetails(got)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/** What file.probe says of a GIS file, a row each. */
@Composable
private fun GisDetails(read: JsonObject) {
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    Detail(stringResource(R.string.files_format), read.text("format") ?: read.text("driver").orEmpty())
    @Composable
    fun crsText(crs: JsonObject?): String =
        crs?.let { c -> listOfNotNull(c.text("name"), c.text("id")?.let { "($it)" }).joinToString(" ") } ?: stringResource(R.string.files_no_crs)
    if (read.text("kind") == "raster") {
        val width = read.number("width")?.toLong()
        val height = read.number("height")?.toLong()
        if (width != null && height != null) Detail(stringResource(R.string.files_pixels), "$width × $height")
        val bands = (read["bands"] as? JsonArray).orEmpty().map { it.jsonObject }
        val types = bands.mapNotNull { it.text("type") }.distinct().joinToString("、")
        Detail(stringResource(R.string.files_bands), stringResource(R.string.files_bands_value, bands.size, types))
        val crs = read["crs"] as? JsonObject
        val pixel = (read["pixel"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
        if (pixel != null && pixel.size == 2) {
            val geographic = crs?.text("geographic") == "true"
            fun one(v: Double) = if (geographic) "${short(v)}°" else short(v)
            val shown = if (pixel[0] == pixel[1]) one(pixel[0]) else "${one(pixel[0])} × ${one(pixel[1])}"
            Detail(stringResource(R.string.files_cell), if (geographic || crs == null) shown else stringResource(R.string.files_metres, shown))
        }
        Detail(stringResource(R.string.files_crs), crsText(crs))
        val extent = (read["extent"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
        if (extent != null && extent.size == 4) {
            val (west, south, east, north) = extent.map { "%.4f".format(java.util.Locale.ROOT, it) }
            Detail(stringResource(R.string.files_extent), stringResource(R.string.files_extent_value, west, east, south, north))
        }
    } else {
        val layers = (read["layers"] as? JsonArray).orEmpty().map { it.jsonObject }
        val lines = layers.map { l ->
            val kind = when (l.text("geometry")?.removePrefix("Multi")?.removeSuffix(" Z")?.removeSuffix(" M")) {
                "Point" -> stringResource(R.string.layer_kind_point)
                "LineString" -> stringResource(R.string.layer_kind_line)
                "Polygon" -> stringResource(R.string.layer_kind_polygon)
                "None", null -> stringResource(R.string.files_no_geometry)
                else -> l.text("geometry").orEmpty()
            }
            val count = l.number("features")?.toInt()
            val features = count?.let { pluralStringResource(R.plurals.files_features, it, it) }
            stringResource(R.string.files_layer_value, l.text("name").orEmpty(), listOfNotNull(kind, features).joinToString(" · "))
        }
        Detail(stringResource(R.string.files_layers), lines.joinToString("\n").ifEmpty { "—" })
        val crss = layers.map { crsText(it["crs"] as? JsonObject) }.distinct()
        if (crss.isNotEmpty()) Detail(stringResource(R.string.files_crs), crss.joinToString("\n"))
    }
}

// A number as people read it: at most six significant digits, no trailing zeros.
private fun short(v: Double): String = java.math.BigDecimal(v).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()

@Composable
private fun Detail(label: String, value: String, action: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        action?.invoke()
    }
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

private fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

/** Opens [file] in an app the user picks. */
internal fun openWith(context: Context, file: File, title: String) {
    runCatching {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(context, file), mimeOf(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, title))
    }
}

/** Hands [files] to another app through the system's share sheet, one or many at once. */
internal fun shareAll(context: Context, files: List<File>, title: String) {
    if (files.isEmpty()) return
    runCatching {
        val send = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mimeOf(files[0])).putExtra(Intent.EXTRA_STREAM, uriOf(context, files[0]))
        } else {
            // One kind of data, or one family of them (image/*), or anything.
            val types = files.map(::mimeOf).distinct()
            val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uriOf(context, it) }))
        }
        context.startActivity(Intent.createChooser(send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), title))
    }
}
