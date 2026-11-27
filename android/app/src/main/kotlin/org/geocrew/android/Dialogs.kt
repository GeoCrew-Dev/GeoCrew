package org.geocrew.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The size of the window clear of the system bars, cutouts and the keyboard. */
@Composable
fun clearWindowSize(): DpSize {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val clear = WindowInsets.safeDrawing
    return with(density) {
        DpSize(
            (window.width - clear.getLeft(this, LayoutDirection.Ltr) - clear.getRight(this, LayoutDirection.Ltr)).toDp(),
            (window.height - clear.getTop(this) - clear.getBottom(this)).toDp(),
        )
    }
}

/**
 * Whether the window is on its side and short, as a phone in landscape: then
 * dialogs leave out their icon, and those of two panes put them side by side.
 */
@Composable
fun isShortLandscape(): Boolean {
    val clear = clearWindowSize()
    return clear.width > clear.height && clear.height < SHORT_HEIGHT
}

// A phone in landscape is shorter than this; a tablet or an unfolded phone keeps plain dialogs.
private val SHORT_HEIGHT = 560.dp

// How wide side-by-side panes grow, and how tall with a [side] that fills the height.
private val TWO_PANE_WIDTH = 720.dp
private val SIDE_HEIGHT = 440.dp

// Below this clear height (a phone on its side with the keyboard up) side-by-side panes keep the right one for the text.
private val TIGHT_HEIGHT = 250.dp

/**
 * GeoCrew's dialog, used everywhere in place of Material's AlertDialog and
 * looking the same, but never cut off: its [text] scrolls (unless [scrolls]
 * is false, for a text that scrolls itself) while the buttons stay.
 *
 * On a phone in landscape it leaves out the [icon], and a dialog of
 * [twoPane] puts its panes side by side (the owner's layout, 2026-11-27): on
 * the left the [side] (a picture, such as a colour field or a preview),
 * filling the height, or else the icon and the [title]; on the right the
 * title when the side took the left, the text, scrolling, and the buttons
 * under it. With the keyboard up, when there is little height left, the left
 * pane holds only the title and the buttons, and the side waits until the
 * keyboard goes. Elsewhere the side comes between the title and the text. The
 * side is told whether it stands beside the rest.
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    side: @Composable ((sideBySide: Boolean) -> Unit)? = null,
    twoPane: Boolean = side != null,
    scrolls: Boolean = true,
    properties: DialogProperties = DialogProperties(),
    text: @Composable (() -> Unit)? = null,
) {
    val clear = clearWindowSize()
    val short = clear.width > clear.height && clear.height < SHORT_HEIGHT
    if (short && twoPane && (text != null || side != null)) {
        // Side by side the dialog is wider than a dialog usually is, so it sets its own width and height.
        val wide = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = properties.decorFitsSystemWindows,
        )
        Dialog(onDismissRequest = onDismissRequest, properties = wide) {
            // With the keyboard up a phone on its side has little height left: then the left pane holds only the title
            // and the buttons, the side giving way, and the right one the text being typed into. (Told by the window,
            // which the keyboard takes its part of, not by what the dialog is offered: that follows its own size.)
            val tight = clear.height < TIGHT_HEIGHT
            val pictured = side != null && !tight
            // 16 dp clear above and below the dialog, 4 dp when tight. A side fills the height it is given; a form takes
            // the height it needs.
            val height = clear.height - if (tight) 8.dp else 32.dp
            val size = Modifier.width((clear.width * 0.9f).coerceAtMost(TWO_PANE_WIDTH))
                .then(if (pictured) Modifier.height(height.coerceAtMost(SIDE_HEIGHT)) else Modifier.heightIn(max = height))
            val margin = if (tight) 16.dp else 24.dp
            val buttons = @Composable { top: Dp ->
                Row(Modifier.fillMaxWidth().padding(top = top), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
            Surface(
                modifier.then(size),
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                    Row(Modifier.padding(margin)) {
                        // The left pane: the side, filling the height; or the icon and the title (and, when tight, the buttons).
                        Column(Modifier.weight(if (pictured) 1f else 0.4f).then(if (tight || pictured) Modifier.fillMaxHeight() else Modifier)) {
                            if (pictured) {
                                side?.invoke(true)
                            } else {
                                if (!tight) icon?.let {
                                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.iconContentColor) { it() }
                                    Spacer(Modifier.height(16.dp))
                                }
                                title?.let { DialogTitle(it) }
                            }
                            if (tight) {
                                Spacer(Modifier.weight(1f))
                                buttons(8.dp)
                            }
                        }
                        Spacer(Modifier.width(margin))
                        // The right pane: the title when the side took the left, the text, scrolling, and the buttons.
                        Column(Modifier.weight(if (pictured) 1f else 0.6f)) {
                            if (pictured) title?.let {
                                DialogTitle(it)
                                Spacer(Modifier.height(16.dp))
                            }
                            // Only the text scrolls: the buttons stay where they are.
                            Box(Modifier.weight(1f, fill = false).then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text?.invoke() }
                            }
                            if (!tight) buttons(16.dp)
                        }
                    }
                }
            }
        }
    } else {
        val body: @Composable (() -> Unit)? = if (text == null && side == null) {
            null
        } else {
            {
                Column(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
                    side?.invoke(false)
                    text?.invoke()
                }
            }
        }
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            // A short window has no height to spare for a picture.
            icon = icon.takeUnless { short },
            title = title,
            text = body,
            properties = properties,
        )
    }
}

// A title as Material's dialogs set it.
@Composable
private fun DialogTitle(title: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
    }
}
