package org.geocrew.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A saved map view, as bookmark.list gives it. */
data class MapBookmark(val id: Long, val name: String, val longitude: Double, val latitude: Double, val zoom: Double, val bearing: Double)

/** The bookmarks in an output of the bookmark actions. */
fun listedBookmarks(output: JsonObject): List<MapBookmark> = output["bookmarks"]?.jsonArray.orEmpty().mapNotNull { b ->
    val o = b.jsonObject
    fun number(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
    MapBookmark(
        (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
        (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        number("longitude") ?: return@mapNotNull null,
        number("latitude") ?: return@mapNotNull null,
        number("zoom") ?: return@mapNotNull null,
        number("bearing") ?: 0.0,
    )
}

/**
 * The project's bookmarks: tap one to go there, add the map's current view,
 * delete one (asked first: it is saved at once and not undone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<MapBookmark>,
    onGo: (MapBookmark) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (MapBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MapBookmark?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tool_bookmarks), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { naming = true }) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bookmark_add))
                }
            }
            if (bookmarks.isEmpty()) {
                Text(
                    stringResource(R.string.bookmarks_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            for (b in bookmarks) {
                ListItem(
                    headlineContent = { Text(b.name) },
                    supportingContent = { Text(String.format(Locale.ROOT, "%.5f, %.5f", b.latitude, b.longitude)) },
                    leadingContent = { Icon(Icons.Rounded.Bookmark, null) },
                    trailingContent = {
                        IconButton(onClick = { deleting = b }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onGo(b) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AppDialog(
            onDismissRequest = { naming = false },
            twoPane = true,
            title = { Text(stringResource(R.string.bookmark_add_title)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.bookmark_name)) }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { naming = false; onAdd(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { b ->
        AppDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.bookmark_delete_title, b.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(b) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
