package org.geocrew.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.geocrew.core.project.ProjectFormat

/**
 * One operation in a row's ⋮ menu (ADR 0017): its name and icon, whether it
 * can be used, whether it deletes (said in the error colour), and for a choice
 * among several, whether it is the one chosen. A null entry is a divider.
 */
data class MoreItem(
    val label: Int,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val checked: Boolean? = null,
    val run: () -> Unit,
)

/** A row's ⋮: its operations, as QGIS keeps them in a layer's context menu. */
@Composable
fun MoreMenu(items: List<MoreItem?>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.files_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) {
                if (item == null) {
                    HorizontalDivider()
                    continue
                }
                val color = if (item.danger) MaterialTheme.colorScheme.error else LocalContentColor.current
                DropdownMenuItem(
                    text = { Text(stringResource(item.label), color = if (item.enabled) color else color.copy(alpha = 0.38f)) },
                    leadingIcon = { Icon(item.icon, null, tint = if (item.danger) MaterialTheme.colorScheme.error else LocalContentColor.current) },
                    trailingIcon = if (item.checked == true) ({ Icon(Icons.Rounded.Check, null) }) else null,
                    enabled = item.enabled,
                    onClick = {
                        open = false
                        item.run()
                    },
                )
            }
        }
    }
}

/**
 * A row of the layer list (ADR 0017), lined up with the section headers: the
 * eye at the left edge, the layer's look in small ([swatch]), its name and
 * [detail], and the ⋮ ([more]) at the right edge. Tapping the row opens it.
 */
@Composable
fun LayerRow(
    title: String,
    detail: String?,
    visible: Boolean,
    onToggle: () -> Unit,
    swatch: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detailError: Boolean = false,
    indent: Dp = 0.dp,
    more: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(start = 4.dp + indent, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VisibilityButton(visible, onToggle)
        Spacer(Modifier.width(4.dp))
        swatch()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (detailError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (more != null) more() else Spacer(Modifier.width(48.dp))
    }
}

/** Where an opened row's content starts: under its name (eye 4 + 48, gap 4, swatch 24, gap 16). */
val ROW_TEXT = 96.dp

/** Shows or hides a layer, the first thing in its row, as the box in front of a layer in QGIS. */
@Composable
fun VisibilityButton(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            stringResource(if (visible) R.string.layer_hide else R.string.layer_show),
            tint = if (visible) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.6f),
        )
    }
}

/**
 * A layer's look in small: a dot, a stroke or a patch in [colors], by what
 * the layer holds; a style by values or ranges gives its first colours, side
 * by side. Faded while the layer is hidden.
 */
@Composable
fun LegendSwatch(geometry: String?, colors: List<Color>, visible: Boolean) {
    // Several colours side by side, each a band of its own: classes are not blended.
    val brush = if (colors.size > 1) {
        Brush.horizontalGradient(*colors.flatMapIndexed { i, c -> listOf(i.toFloat() / colors.size to c, (i + 1f) / colors.size to c) }.toTypedArray())
    } else {
        SolidColor(colors.firstOrNull() ?: Color.Gray)
    }
    Box(Modifier.size(24.dp).alpha(if (visible) 1f else 0.38f), contentAlignment = Alignment.Center) {
        when (LayerKind.of(geometry)) {
            LayerKind.LINE -> Box(Modifier.size(width = 20.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(brush))
            LayerKind.POLYGON -> Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(brush).border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(4.dp)),
            )
            else -> {
                Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
                Box(Modifier.size(12.dp).clip(CircleShape).background(brush))
            }
        }
    }
}

/**
 * A layer's fields, from its ⋮ menu: each with its own menu (rename, range,
 * delete, given by [menu]), and a way to add one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldsSheet(
    layer: String,
    fields: List<FormField>,
    detail: @Composable (FormField) -> String,
    icon: (FormField) -> ImageVector,
    menu: @Composable (FormField) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.layer_fields_of, layer),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            for (field in fields) {
                ListItem(
                    headlineContent = { Text(field.name) },
                    supportingContent = { Text(detail(field)) },
                    leadingContent = { Icon(icon(field), null) },
                    trailingContent = { menu(field) },
                    colors = clear,
                )
            }
            FilledTonalButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.field_add))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Which group a layer goes into: one of [groups] (id to name), a new one, or out of the one it is in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPicker(
    layer: String,
    groups: List<Pair<String, String>>,
    inGroup: Boolean,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.layer_move_title, layer),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            for ((id, name) in groups) {
                ListItem(
                    headlineContent = { Text(name) },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    colors = clear,
                    modifier = Modifier.clickable { onPick(id) },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.layer_group_new)) },
                leadingContent = { Icon(Icons.Rounded.CreateNewFolder, null) },
                colors = clear,
                modifier = Modifier.clickable(onClick = onNew),
            )
            if (inGroup) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.layer_move_out)) },
                    leadingContent = { Icon(Icons.Rounded.DriveFileMove, null) },
                    colors = clear,
                    modifier = Modifier.clickable(onClick = onOut),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A group's name: new ([current] empty) or changed, not one of [existing] (in any case). */
@Composable
fun GroupNameDialog(title: String, current: String, existing: List<String>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val trimmed = name.trim()
    val taken = existing.any { it.equals(trimmed, ignoreCase = true) && !it.equals(current, ignoreCase = true) }
    val valid = trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME && trimmed != current && !taken
    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.layer_group_name)) },
                singleLine = true,
                isError = taken,
                supportingText = { if (taken) Text(stringResource(R.string.layer_group_taken)) },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(trimmed) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** What a layer is: what it holds, how many features and fields, and its coordinate system. */
@Composable
fun LayerPropertiesDialog(name: String, geometry: String?, count: Int, fields: Int, srs: String?, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, null) },
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val kind = when {
                    geometry == null -> "—"
                    else -> LayerKind.of(geometry)?.let { stringResource(it.label) } ?: stringResource(R.string.layer_kind_mixed)
                }
                Property(stringResource(R.string.layer_property_kind), kind)
                Property(stringResource(R.string.layer_property_count), count.toString())
                Property(stringResource(R.string.layer_property_fields), fields.toString())
                srs?.let { Property(stringResource(R.string.layer_crs), it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
private fun Property(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
