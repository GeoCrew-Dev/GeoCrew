package org.geocrew.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val dated = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/** "14:05" today, "11月3日 14:05" otherwise. */
fun commentTime(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = at.atZone(zone)
    return if (t.toLocalDate() == LocalDate.now(zone)) clock.format(t) else dated.format(t)
}

/**
 * One message in a discussion: own messages on the right. [context] is an
 * optional line above the text, e.g. which record it is about. A long press
 * calls [onLongPress] (used for deleting).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommentBubble(
    nickname: String,
    body: String,
    time: Instant,
    mine: Boolean,
    pending: Boolean,
    modifier: Modifier = Modifier,
    context: String? = null,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    /** An urgent team message: drawn in the error colours with a label. */
    urgent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Text(
            (if (urgent) "紧急 · " else "") + (if (mine) "我" else nickname.ifBlank { "组员" }) + " · " + if (pending) "待发送" else commentTime(time),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                urgent -> colors.error
                pending -> colors.tertiary
                else -> colors.onSurfaceVariant
            },
        )
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    when {
                        urgent -> colors.errorContainer
                        mine -> colors.primaryContainer
                        else -> colors.surfaceVariant
                    },
                    RoundedCornerShape(12.dp),
                )
                .combinedClickable(enabled = onClick != null || onLongPress != null, onClick = { onClick?.invoke() }, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            context?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    urgent -> colors.onErrorContainer
                    mine -> colors.onPrimaryContainer
                    else -> colors.onSurface
                },
            )
        }
    }
}

/** Text field with a send button; the text is cleared once [onSend] accepts it. */
@Composable
fun CommentComposer(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "说点什么…",
    maxChars: Int = 2000,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onSend(text.trim())
            text = ""
        }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= maxChars) text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        IconButton(onClick = send, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
    }
}
