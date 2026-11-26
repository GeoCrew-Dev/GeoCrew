package org.geocrew.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The app's settings: appearance, language, the map's key and where projects are. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val settings = graph.settings
    var askKey by remember { mutableStateOf(false) }
    var askLanguage by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(TiandituKey.read(context)) }
    val language = remember { AppLanguage.current(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        // List items take the screen's own colour, so the page has no bands.
        val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                    val modes = listOf(
                        Triple(ThemeMode.SYSTEM, R.string.theme_system, Icons.Rounded.SettingsBrightness),
                        Triple(ThemeMode.LIGHT, R.string.theme_light, Icons.Rounded.LightMode),
                        Triple(ThemeMode.DARK, R.string.theme_dark, Icons.Rounded.DarkMode),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        modes.forEachIndexed { index, (mode, label, icon) ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { settings.setTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                icon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
                            ) { Text(stringResource(label)) }
                        }
                    }
                }
            }
            item {
                ListItem(
                    colors = clear,
                    headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.settings_dynamic_color_body)) },
                    leadingContent = { Icon(Icons.Rounded.Palette, null) },
                    trailingContent = { Switch(checked = settings.dynamicColor, onCheckedChange = settings::setDynamic) },
                    modifier = Modifier.clickable { settings.setDynamic(!settings.dynamicColor) },
                )
            }
            item { SectionHeader(stringResource(R.string.settings_language)) }
            item {
                ListItem(
                    colors = clear,
                    headlineContent = { Text(stringResource(R.string.settings_language)) },
                    supportingContent = {
                        Text(if (AppLanguage.canChoose) languageName(language) else stringResource(R.string.settings_language_unavailable))
                    },
                    leadingContent = { Icon(Icons.Rounded.Translate, null) },
                    modifier = Modifier.clickable(enabled = AppLanguage.canChoose) { askLanguage = true },
                )
            }
            item { SectionHeader(stringResource(R.string.settings_map)) }
            item {
                ListItem(
                    colors = clear,
                    headlineContent = { Text(stringResource(R.string.settings_tianditu_key)) },
                    supportingContent = { Text(stringResource(if (key != null) R.string.key_set else R.string.key_not_set)) },
                    leadingContent = { Icon(Icons.Rounded.Key, null) },
                    modifier = Modifier.clickable { askKey = true },
                )
            }
            item { SectionHeader(stringResource(R.string.settings_storage)) }
            item {
                ListItem(
                    colors = clear,
                    headlineContent = { Text(stringResource(R.string.settings_projects_folder)) },
                    supportingContent = { Text(graph.root.path) },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                ListItem(
                    colors = clear,
                    headlineContent = { Text(stringResource(R.string.about)) },
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    modifier = Modifier.clickable(onClick = onAbout),
                )
            }
        }
    }

    if (askKey) {
        KeyDialog(current = key.orEmpty(), onDismiss = { askKey = false }, onSave = {
            TiandituKey.write(context, it)
            key = it
            askKey = false
        })
    }
    if (askLanguage) {
        AlertDialog(
            onDismissRequest = { askLanguage = false },
            icon = { Icon(Icons.Rounded.Translate, null) },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    for (choice in AppLanguage.Choice.entries) {
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = choice == language, role = Role.RadioButton, onClick = {
                                    askLanguage = false
                                    AppLanguage.set(context, choice) // Android makes the screens again in it
                                })
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == language, onClick = null)
                            Text(languageName(choice), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { askLanguage = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun languageName(choice: AppLanguage.Choice): String = when (choice) {
    AppLanguage.Choice.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.Choice.CHINESE -> stringResource(R.string.language_zh)
    AppLanguage.Choice.ENGLISH -> stringResource(R.string.language_en)
}

/** The user's own Tianditu key (ADR 0015). */
@Composable
fun KeyDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val valid = TiandituKey.isValid(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text(stringResource(R.string.key_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.key_hint)) },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                supportingText = { if (text.isNotEmpty() && !valid) Text(stringResource(R.string.key_invalid)) },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
