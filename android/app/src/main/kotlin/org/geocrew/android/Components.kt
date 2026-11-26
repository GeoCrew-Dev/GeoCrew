package org.geocrew.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How much of the map shows through the floating buttons. */
const val TRANSLUCENT = 0.72f

/**
 * A round icon button with a ripple, floating over the map: translucent by
 * default; [solid] for the buttons that must always stand out (the menu and the
 * AI). A long press shows what it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
    solid: Boolean = false,
    enabled: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = if (solid) container else container.copy(alpha = TRANSLUCENT),
            contentColor = content,
            shadowElevation = if (solid) 3.dp else 1.dp,
            modifier = Modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = if (enabled) content else content.copy(alpha = 0.38f),
                        modifier = Modifier.size(size * 0.5f),
                    )
                }
            }
        }
    }
}
