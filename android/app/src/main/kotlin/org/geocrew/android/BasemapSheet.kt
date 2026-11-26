package org.geocrew.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Satellite
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Which Tianditu map shows under the layers: imagery, the vector map or terrain (ADR 0015). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasemapSheet(chosen: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column {
            Text(stringResource(R.string.tool_basemap), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            for ((kind, label, icon) in listOf(
                Triple("img", R.string.basemap_kind_img, Icons.Rounded.Satellite),
                Triple("vec", R.string.basemap_kind_vec, Icons.Rounded.Map),
                Triple("ter", R.string.basemap_kind_ter, Icons.Rounded.Terrain),
            )) {
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    leadingContent = { Icon(icon, null) },
                    trailingContent = { RadioButton(selected = kind == chosen, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable { onPick(kind) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
