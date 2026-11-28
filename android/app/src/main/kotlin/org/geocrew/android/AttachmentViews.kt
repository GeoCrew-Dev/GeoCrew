package org.geocrew.android

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.storage.AttachmentActions
import org.geocrew.storage.SyncActions

/** Where a new attachment comes from: the app's camera, the phone's photos, or any file. */
enum class AttachmentSource { CAMERA, GALLERY, FILE }

private val TILE = 88.dp

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * A feature's attachments in its form (ADR 0004): a tile for each, a photo's
 * thumbnail or a file's kind and name, with a button that takes it off; a tile
 * to add another ([onAdd]), busy while one is being added ([adding]).
 */
@Composable
internal fun AttachmentStrip(
    files: List<JsonObject>,
    adding: Boolean,
    onAdd: (AttachmentSource) -> Unit,
    onOpen: (JsonObject) -> Unit,
    onRemove: (JsonObject) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        // No keys: a file picked twice is two tiles with the same hash until the feature gives them ids.
        items(files) { file ->
            Box(Modifier.size(TILE)) {
                AttachmentTile(file, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).clickable { onOpen(file) })
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).clickable { onRemove(file) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, stringResource(R.string.attachment_remove), Modifier.size(16.dp)) }
            }
        }
        item {
            var choosing by remember { mutableStateOf(false) }
            Box {
                Column(
                    Modifier.size(TILE).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(enabled = !adding) { choosing = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (adding) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Rounded.Add, null)
                        Text(stringResource(R.string.attachment_add), style = MaterialTheme.typography.labelMedium)
                    }
                }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attachment_camera)) },
                        leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                        onClick = {
                            choosing = false
                            onAdd(AttachmentSource.CAMERA)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attachment_gallery)) },
                        leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                        onClick = {
                            choosing = false
                            onAdd(AttachmentSource.GALLERY)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attachment_file)) },
                        leadingIcon = { Icon(Icons.Rounded.Description, null) },
                        onClick = {
                            choosing = false
                            onAdd(AttachmentSource.FILE)
                        },
                    )
                }
            }
        }
    }
}

// A photo's or video's thumbnail; else what kind of file it is and its name. A file not on this phone says so.
@Composable
private fun AttachmentTile(file: JsonObject, modifier: Modifier) {
    val thumb = file.text("thumbPath")
    val picture by produceState<ImageBitmap?>(null, thumb) {
        value = thumb?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val shown = picture
        if (shown != null) {
            Image(shown, file.text("name"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val missing = (file["missing"] as? JsonPrimitive)?.booleanOrNull == true
            val type = file.text("type").orEmpty()
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Icon(
                    when {
                        missing -> Icons.Rounded.CloudOff
                        type.startsWith("image/") -> Icons.Rounded.Image
                        type.startsWith("video/") -> Icons.Rounded.Movie
                        type.startsWith("audio/") -> Icons.Rounded.AudioFile
                        else -> Icons.Rounded.Description
                    },
                    if (missing) stringResource(R.string.attachment_missing) else null,
                )
                Text(file.text("name").orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A photo shown whole, on black, with a way to open it in another app; [note] under its name, when there is one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoViewer(file: JsonObject, note: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val path = file.text("path").orEmpty()
    val name = file.text("name").orEmpty()
    val openText = stringResource(R.string.attachment_open_with)
    // Null while it is read; a failure when it cannot be shown here, which another app may still open.
    val picture by produceState<Result<ImageBitmap>?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { decodeFitted(File(path)) } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f)) }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) } },
                    actions = {
                        IconButton(onClick = { openWith(context, File(path), openText) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, openText) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                picture?.onSuccess { Image(it, name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    ?.onFailure { Icon(Icons.Rounded.Image, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
            }
        }
    }
}

/** Fetches [file]'s original from the team's workspace (protocol v1 9.2): null once it is here, else what to tell the user. */
internal suspend fun fetchAttachment(graph: AppGraph, texts: SyncTexts, missing: String, file: JsonObject): String? {
    val params = buildJsonObject {
        put("file", file.text("file").orEmpty())
        put("type", file.text("type").orEmpty())
    }
    return when (val done = graph.invoke(SyncActions.FILE, params)) {
        is Outcome.Done -> null
        // A project with no team to fetch it from: the file is just not on this phone.
        else -> if ((done as? Outcome.Failed)?.code == SyncActions.NOT_LINKED) missing else texts.of(done)
    }
}

// The photo turned the way it was taken (ImageDecoder reads its EXIF orientation), no bigger than a screen needs.
private fun decodeFitted(file: File): ImageBitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
    val longest = maxOf(info.size.width, info.size.height)
    var sample = 1
    while (longest / (sample * 2) >= 2048) sample *= 2
    decoder.setTargetSampleSize(sample)
}.asImageBitmap()

/** Copies what [uri] points at into the open project as an attachment (attachment.import); null when it cannot. */
internal suspend fun importAttachment(context: Context, graph: AppGraph, uri: Uri): JsonObject? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    val incoming = File(context.cacheDir, "incoming").apply { mkdirs() }
    val temp = File(incoming, UUID.randomUUID().toString())
    try {
        val copied = runCatching { resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } }.getOrNull()
        if (copied == null) return@withContext null
        val params = buildJsonObject {
            put("path", temp.path)
            put("name", name ?: uri.lastPathSegment ?: temp.name)
            resolver.getType(uri)?.let { put("type", it) }
        }
        (graph.invoke(AttachmentActions.IMPORT, params) as? Outcome.Done)?.output?.jsonObject
    } finally {
        temp.delete()
    }
}
