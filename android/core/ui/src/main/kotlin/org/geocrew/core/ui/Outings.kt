package org.geocrew.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Outing
import java.time.Instant

/** Someone out on their own (protocol v0 §8.3), as a line on the board. */
data class OutingRow(
    val id: String,
    val nickname: String,
    /** Where they said they were going. */
    val where: String,
    val backBy: Instant,
    val mine: Boolean,
    /** This user may check them back in: their own, or as a leader. */
    val canClose: Boolean,
    /** Where they set off from. */
    val at: LngLat?,
)

/** The current time, ticking every [everyMillis] so countdowns stay true. */
@Composable
fun ticking(everyMillis: Long = 30_000): Instant {
    val now by produceState(Instant.now(), everyMillis) {
        while (true) {
            delay(everyMillis)
            value = Instant.now()
        }
    }
    return now
}

/**
 * Who is out and when they are due back, overdue ones in red. A tap on a
 * line shows where they set off; "已返回" checks them back in.
 */
@Composable
fun OutingBoard(
    rows: List<OutingRow>,
    onBack: (String) -> Unit,
    onShow: (LngLat) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    maxRows: Int = 6,
) {
    if (rows.isEmpty()) return
    val now = ticking()
    val late = rows.count { it.backBy < now }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (late > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "外出 ${rows.size} 人" + if (late > 0) "，$late 人超时未返回" else "",
                style = MaterialTheme.typography.labelMedium,
            )
            rows.take(maxRows).forEach { r ->
                val overdue = r.backBy < now
                Row(
                    Modifier.fillMaxWidth().then(if (r.at != null) Modifier.clickable { onShow(r.at) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (r.mine) "你" else r.nickname.ifBlank { "组员" }) + " · " + r.where + if (r.at != null) " 📍" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "预计 ${commentTime(r.backBy)} 回 · " + Outing.due(r.backBy, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (r.canClose) TextButton(onClick = { onBack(r.id) }, enabled = !busy) { Text(if (r.mine) "我已返回" else "已返回") }
                }
            }
            if (rows.size > maxRows) Text("还有 ${rows.size - maxRows} 人", style = MaterialTheme.typography.bodySmall)
        }
    }
}
