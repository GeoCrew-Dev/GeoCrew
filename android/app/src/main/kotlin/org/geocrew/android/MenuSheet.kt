package org.geocrew.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** An entry of the map's menu: a group of entries one level down, or a tool. */
sealed interface MenuEntry {
    val label: String
    val icon: ImageVector
}

data class MenuGroup(override val label: String, override val icon: ImageVector, val entries: List<MenuEntry>) : MenuEntry

data class MenuTool(override val label: String, override val icon: ImageVector, val run: () -> Unit) : MenuEntry

/**
 * The map's menu, in levels: groups of tools (map, measure, data, ...) open
 * one level down; back goes up a level. Only tools that work are listed, and
 * a group with none is left out. Choosing a tool closes the menu first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(title: String, entries: List<MenuEntry>, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(emptyList<MenuGroup>()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BackHandler(enabled = path.isNotEmpty()) { path = path.dropLast(1) }
        AnimatedContent(targetState = path, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "menu") { shown ->
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val group = shown.lastOrNull()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (group != null) {
                        IconButton(onClick = { path = path.dropLast(1) }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                        }
                    }
                    Text(
                        group?.label ?: title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                for (entry in group?.entries ?: entries) {
                    if (entry is MenuGroup && entry.entries.isEmpty()) continue
                    ListItem(
                        headlineContent = { Text(entry.label) },
                        leadingContent = { Icon(entry.icon, null) },
                        trailingContent = if (entry is MenuGroup) ({ Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }) else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().clickable {
                            when (entry) {
                                is MenuGroup -> path = path + entry
                                is MenuTool -> {
                                    onDismiss()
                                    entry.run()
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
