package org.geocrew.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A field of a layer, as layer.features describes it. */
data class FormField(val name: String, val type: String)

/**
 * A feature's fields to read and change, like QGIS's attribute form. Text
 * fields take text, number fields numbers; dates are shown but not changed here
 * yet. [onSave] gets only the fields that changed, a cleared field as null.
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
    // Full screen, with Save in the top bar, where the keyboard never covers it.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
                    actions = {
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                val changed = fields.filter { editable(it.type) && texts[it.name].orEmpty() != original[it.name] }
                                onSave(JsonObject(changed.associate { it.name to value(it.type, texts[it.name].orEmpty()) }))
                            },
                        ) { Text(stringResource(R.string.map_save)) }
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
                        value = texts[field.name].orEmpty(),
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
                TextButton(onClick = onDelete, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.feature_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun editable(type: String) = type in setOf("string", "real", "integer", "integer64")

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
