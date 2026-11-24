package org.geocrew.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** One entry of the tools hub. Features add tools by adding an entry here. */
data class ToolEntry(val title: String, val description: String, val icon: ImageVector, val route: String)

val builtInTools = listOf(
    ToolEntry("罗盘 · 产状", "测量方位和岩层产状，保存为地质点", Icons.Default.Explore, "compass"),
    ToolEntry("导入 / 导出", "GeoJSON、KML、GPX", Icons.Default.ImportExport, Routes.TRANSFER),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(tools: List<ToolEntry>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(tools, key = { it.route }) { t ->
                ListItem(
                    headlineContent = { Text(t.title) },
                    supportingContent = { Text(t.description) },
                    leadingContent = { Icon(t.icon, null) },
                    modifier = Modifier.clickable { onOpen(t.route) },
                )
            }
        }
    }
}
