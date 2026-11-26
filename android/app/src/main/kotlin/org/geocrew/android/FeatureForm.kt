package org.geocrew.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A field of a layer, as layer.features describes it. */
data class FormField(val name: String, val type: String)

/**
 * A feature's fields to read and change, like QGIS's attribute form: full
 * screen, with close and save in the top bar, where the keyboard never covers
 * them. Text fields take text, number fields numbers; dates are shown, in local
 * time, but not changed here yet. [onSave] gets only the fields that changed, a
 * cleared field as null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureForm(
    title: String,
    fields: List<FormField>,
    properties: JsonObject,
    onDismiss: () -> Unit,
    onSave: (JsonObject) -> Unit,
    onDelete: () -> Unit,
) {
    val original = remember(properties) { fields.associate { it.name to text(properties[it.name]) } }
    val texts = remember(properties) { mutableStateMapOf<String, String>().apply { putAll(original) } }
    val problems = fields.associate { it.name to problem(it.type, texts[it.name].orEmpty()) }
    val canSave = problems.values.all { it == null }
    val locale = LocalConfiguration.current.locales[0]
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) } },
                    actions = {
                        FilledIconButton(
                            enabled = canSave,
                            onClick = {
                                val changed = fields.filter { editable(it.type) && texts[it.name].orEmpty() != original[it.name] }
                                onSave(JsonObject(changed.associate { it.name to value(it.type, texts[it.name].orEmpty()) }))
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Icon(Icons.Rounded.Check, stringResource(R.string.save)) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (field in fields) {
                    val problem = problems[field.name]
                    OutlinedTextField(
                        value = if (editable(field.type)) texts[field.name].orEmpty() else shown(field.type, texts[field.name].orEmpty(), locale),
                        onValueChange = { texts[field.name] = it },
                        label = { Text(field.name) },
                        singleLine = field.type != "string",
                        readOnly = !editable(field.type),
                        isError = problem != null,
                        supportingText = problem?.let { { Text(stringResource(it)) } },
                        keyboardOptions = KeyboardOptions(keyboardType = keyboard(field.type)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.feature_delete))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text(stringResource(R.string.feature_delete_title)) },
            text = { Text(stringResource(R.string.feature_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun editable(type: String) = type in setOf("string", "real", "integer", "integer64")

// A date and time stored as UTC, shown in the phone's time zone and the app's language.
private fun shown(type: String, text: String, locale: Locale): String {
    if (type != "datetime" || text.isBlank()) return text
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault()).format(Instant.parse(text))
    }.getOrDefault(text)
}

private fun keyboard(type: String) = when (type) {
    "real" -> KeyboardType.Decimal
    "integer", "integer64" -> KeyboardType.Number
    else -> KeyboardType.Text
}

private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()

// What is wrong with the text for a field of this type, as a string resource.
private fun problem(type: String, text: String): Int? {
    if (text.isBlank()) return null
    return when (type) {
        "real" -> if (text.trim().toDoubleOrNull()?.isFinite() == true) null else R.string.field_not_a_number
        "integer" -> if (text.trim().toIntOrNull() != null) null else R.string.field_not_a_whole_number
        "integer64" -> if (text.trim().toLongOrNull() != null) null else R.string.field_not_a_whole_number
        else -> null
    }
}

private fun value(type: String, text: String): JsonElement = when {
    text.isBlank() -> JsonNull
    type == "real" -> JsonPrimitive(text.trim().toDouble())
    type == "integer" || type == "integer64" -> JsonPrimitive(text.trim().toLong())
    else -> JsonPrimitive(text)
}
