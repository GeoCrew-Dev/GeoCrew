package org.geocrew.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Architecture
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.geocrew.storage.ExportFormat

/** A format's name in the list. */
private fun labelOf(format: ExportFormat): Int = when (format) {
    ExportFormat.GPKG -> R.string.format_gpkg
    ExportFormat.SHP -> R.string.format_shp
    ExportFormat.KML -> R.string.format_kml
    ExportFormat.KMZ -> R.string.format_kmz
    ExportFormat.GEOJSON -> R.string.format_geojson
    ExportFormat.CSV -> R.string.format_csv
    ExportFormat.XLSX -> R.string.format_xlsx
    ExportFormat.GPX -> R.string.format_gpx
    ExportFormat.DXF -> R.string.format_dxf
}

private fun iconOf(format: ExportFormat): ImageVector = when (format) {
    ExportFormat.GPKG -> Icons.Rounded.Layers
    ExportFormat.SHP -> Icons.Rounded.FolderZip
    ExportFormat.KML -> Icons.Rounded.Public
    ExportFormat.KMZ -> Icons.Rounded.PhotoLibrary
    ExportFormat.GEOJSON -> Icons.Rounded.DataObject
    ExportFormat.CSV -> Icons.Rounded.TableRows
    ExportFormat.XLSX -> Icons.Rounded.GridOn
    ExportFormat.GPX -> Icons.Rounded.Route
    ExportFormat.DXF -> Icons.Rounded.Architecture
}

/**
 * The formats to export in, a button each (ADR 0004): for one layer, whose
 * [geometry] decides whether GPX can hold it, or for the whole project
 * ([geometry] null), where a format of one layer comes as a zip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(title: String, geometry: String?, wholeProject: Boolean, onPick: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    val kind = geometry?.uppercase().orEmpty()
    val formats = ExportFormat.entries.filter { it != ExportFormat.GPX || wholeProject || "POINT" in kind || "LINE" in kind || kind == "GEOMETRY" }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
            for (format in formats) {
                Surface(
                    onClick = { onPick(format) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconOf(format), null)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(labelOf(format)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            "." + if (wholeProject && !format.layers) "zip" else format.extension,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
