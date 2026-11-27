package org.geocrew.android

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Satellite
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A tool of the map: in the menu, under its [group] (a menu group's name;
 * null for the top level), and on the map's toolbar when the user puts it
 * there. [id] is what the toolbar keeps; it does not change.
 */
enum class MapTool(val id: String, val group: Int?, val label: Int, val icon: ImageVector) {
    LAYERS("layers", null, R.string.map_layers, Icons.Rounded.Layers),
    UNDO("undo", R.string.menu_edit, R.string.map_undo, Icons.AutoMirrored.Rounded.Undo),
    REDO("redo", R.string.menu_edit, R.string.map_redo, Icons.AutoMirrored.Rounded.Redo),
    ADD_FEATURE("add_feature", R.string.menu_edit, R.string.tool_add_feature, Icons.Rounded.AddCircleOutline),
    SNAP("snap", R.string.menu_edit, R.string.tool_snap, Icons.Rounded.FilterCenterFocus),
    SHOW_ALL("show_all", R.string.menu_map, R.string.tool_show_all, Icons.Rounded.ZoomOutMap),
    GO_TO("go_to", R.string.menu_map, R.string.tool_go_to, Icons.Rounded.NearMe),
    NORTH_UP("north_up", R.string.menu_map, R.string.tool_north_up, Icons.Rounded.Explore),
    BASEMAP("basemap", R.string.menu_map, R.string.tool_basemap, Icons.Rounded.Satellite),
    SCREENSHOT("screenshot", R.string.menu_map, R.string.tool_screenshot, Icons.Rounded.PhotoCamera),
    BOOKMARKS("bookmarks", R.string.menu_map, R.string.tool_bookmarks, Icons.Rounded.Bookmarks),
    LOCK_ROTATION("lock_rotation", R.string.menu_map, R.string.tool_lock_rotation, Icons.Rounded.ScreenLockRotation),
    MEASURE_DISTANCE("measure_distance", R.string.menu_measure, R.string.tool_measure_distance, Icons.Rounded.Straighten),
    MEASURE_AREA("measure_area", R.string.menu_measure, R.string.tool_measure_area, Icons.Rounded.SquareFoot),
    AZIMUTH("azimuth", R.string.menu_measure, R.string.tool_azimuth, Icons.Rounded.Explore),
    PICK("pick", R.string.menu_measure, R.string.tool_pick, Icons.Rounded.PinDrop),
    CLINOMETER("clinometer", R.string.menu_measure, R.string.clino_title, Icons.Rounded.Terrain),
    MY_LOCATION("my_location", R.string.menu_location, R.string.my_position, Icons.Rounded.MyLocation),
    TRACK("track", R.string.menu_location, R.string.tool_track, Icons.Rounded.Route),
    CONVERT("convert", R.string.menu_analysis, R.string.tool_convert, Icons.Rounded.Transform),
    EXPORT("export", R.string.menu_data, R.string.layer_export, Icons.Rounded.SaveAlt),
    FILES("files", R.string.menu_data, R.string.files, Icons.Rounded.FolderOpen),
    PROJECT_SETTINGS("project_settings", R.string.project_section, R.string.project_settings, Icons.Rounded.Tune),
    CLOSE_PROJECT("close_project", R.string.project_section, R.string.project_close, Icons.AutoMirrored.Rounded.Logout),
    ;

    companion object {
        /** The toolbar a new install starts with: what the map had before the toolbar could change. */
        val DEFAULT = listOf(LAYERS, UNDO, REDO, MY_LOCATION)

        /** The menu's groups, in order. */
        val GROUPS = listOf(R.string.menu_edit, R.string.menu_map, R.string.menu_measure, R.string.menu_location, R.string.menu_analysis, R.string.menu_data, R.string.project_section)

        // The export of one format each (export_gpkg, export_kml, export_kmz) became one export with a list of formats.
        fun of(id: String): MapTool? = entries.firstOrNull { it.id == id } ?: EXPORT.takeIf { id.startsWith("export_") }
    }
}

/**
 * How a tool shows now: what it is called and its icon (some change, as
 * recording a track becomes finishing it), whether it can be used, and
 * whether it is on, as a measuring tool being used.
 */
data class ToolState(val label: String, val icon: ImageVector, val enabled: Boolean = true, val on: Boolean = false)

/**
 * The map's toolbar to put together: the tools on it, in order, to move up
 * or down or take off; and every other tool, by menu group, to put on. The
 * default comes back with one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarEditor(tools: List<MapTool>, onChange: (List<MapTool>) -> Unit, onDismiss: () -> Unit) {
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.toolbar_title)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                    actions = {
                        TextButton(onClick = { onChange(MapTool.DEFAULT) }, enabled = tools != MapTool.DEFAULT) { Text(stringResource(R.string.toolbar_default)) }
                    },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { SectionHeader(stringResource(R.string.toolbar_on)) }
                if (tools.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.toolbar_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                for ((at, tool) in tools.withIndex()) {
                    item(key = "on:${tool.id}") {
                        ListItem(
                            headlineContent = { Text(stringResource(tool.label)) },
                            leadingContent = { Icon(tool.icon, null) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onChange(tools.toMutableList().apply { add(at - 1, removeAt(at)) }) }, enabled = at > 0) {
                                        Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.move_up))
                                    }
                                    IconButton(onClick = { onChange(tools.toMutableList().apply { add(at + 1, removeAt(at)) }) }, enabled = at < tools.size - 1) {
                                        Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.move_down))
                                    }
                                    IconButton(onClick = { onChange(tools - tool) }) {
                                        Icon(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.toolbar_remove), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            colors = clear,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                // What is not on it yet, under the menu's groups.
                for (group in listOf<Int?>(null) + MapTool.GROUPS) {
                    val off = MapTool.entries.filter { it.group == group && it !in tools }
                    if (off.isEmpty()) continue
                    item(key = "group:$group") { SectionHeader(stringResource(group ?: R.string.toolbar_more)) }
                    for (tool in off) {
                        item(key = "off:${tool.id}") {
                            ListItem(
                                headlineContent = { Text(stringResource(tool.label)) },
                                leadingContent = { Icon(tool.icon, null) },
                                trailingContent = {
                                    IconButton(onClick = { onChange(tools + tool) }) {
                                        Icon(Icons.Rounded.AddCircle, stringResource(R.string.toolbar_add), tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                colors = clear,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
