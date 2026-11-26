package org.geocrew.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.geocrew.core.project.ProjectFormat
import org.geocrew.storage.Workspace

/** The field types a user can add, in the order offered. Dates with a time wait for a picker. */
private val TYPES = listOf("string", "real", "integer", "date", "boolean")

/**
 * Adds a field of the user's own to a layer: GeoCrew's point layers carry only
 * the basic fields, and every field worker adds what their work needs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddFieldDialog(existing: List<String>, onDismiss: () -> Unit, onAdd: (name: String, type: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TYPES.first()) }
    val trimmed = name.trim()
    val taken = (existing + Workspace.RESERVED).any { it.equals(trimmed, ignoreCase = true) }
    val valid = trimmed.isNotEmpty() && trimmed.length <= ProjectFormat.MAX_NAME && !taken
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
        title = { Text(stringResource(R.string.field_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    isError = taken,
                    supportingText = { if (taken) Text(stringResource(R.string.field_name_taken)) },
                )
                Text(stringResource(R.string.field_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in TYPES) {
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(typeName(t)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(trimmed, type) }, enabled = valid) { Text(stringResource(R.string.field_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A field type as the user reads it. */
@Composable
fun typeName(type: String): String = when (type) {
    "string" -> stringResource(R.string.type_string)
    "real" -> stringResource(R.string.type_real)
    "integer", "integer64" -> stringResource(R.string.type_integer)
    "date" -> stringResource(R.string.type_date)
    "datetime" -> stringResource(R.string.type_datetime)
    "boolean" -> stringResource(R.string.type_boolean)
    else -> type
}
