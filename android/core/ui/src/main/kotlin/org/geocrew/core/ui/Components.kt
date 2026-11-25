package org.geocrew.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.geocrew.core.model.FeatureKind

/** Display name and icon of each built-in feature kind. */
object KindUi {
    fun label(kind: FeatureKind): String = when (kind) {
        FeatureKind.POINT -> "标注点"
        FeatureKind.GEO_POINT -> "地质点"
        FeatureKind.LINE -> "线"
        FeatureKind.POLYGON -> "面"
        FeatureKind.TRACK -> "轨迹"
        FeatureKind.PHOTO -> "照片点"
        FeatureKind.SECTION -> "实测剖面"
        FeatureKind.ROUTE -> "地质路线"
        FeatureKind.BOREHOLE -> "钻孔"
        else -> kind.id
    }

    fun icon(kind: FeatureKind): ImageVector = when (kind) {
        FeatureKind.POINT -> Icons.Outlined.Place
        FeatureKind.GEO_POINT -> Icons.Outlined.Explore
        FeatureKind.LINE -> Icons.Outlined.Timeline
        FeatureKind.POLYGON -> Icons.Outlined.CropSquare
        FeatureKind.TRACK -> Icons.Outlined.Gesture
        FeatureKind.PHOTO -> Icons.Outlined.PhotoCamera
        FeatureKind.SECTION -> Icons.Outlined.Layers
        FeatureKind.ROUTE -> Icons.Outlined.Hiking
        FeatureKind.BOREHOLE -> Icons.Outlined.VerticalAlignBottom
        else -> Icons.Outlined.Category
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A label/value row for detail panels. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
