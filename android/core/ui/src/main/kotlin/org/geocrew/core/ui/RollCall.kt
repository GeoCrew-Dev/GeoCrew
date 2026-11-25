package org.geocrew.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant

/**
 * A leader's roll call waiting for this user's answer: one tap to say all
 * is well, one to ask for help. [answered] is shown instead of the question
 * prompt once the user has answered, e.g. "你已回应：平安 · 14:02".
 */
@Composable
fun RollCallPrompt(
    caller: String,
    question: String,
    time: Instant,
    onOk: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    answered: String? = null,
    busy: Boolean = false,
    /** Hides the prompt for now, where it would cover something (the map). */
    onLater: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("点名 · ${caller.ifBlank { "组长" }} · ${commentTime(time)}", style = MaterialTheme.typography.labelMedium)
            Text(question, style = MaterialTheme.typography.bodyLarge)
            answered?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOk, enabled = !busy) { Text(if (answered == null) "报平安" else "再报一次平安") }
                OutlinedButton(
                    onClick = onHelp,
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("需要帮助") }
                onLater?.let { androidx.compose.material3.TextButton(onClick = it) { Text("稍后") } }
            }
        }
    }
}
