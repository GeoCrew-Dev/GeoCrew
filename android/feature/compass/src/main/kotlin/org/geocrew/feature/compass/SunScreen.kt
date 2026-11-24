package org.geocrew.feature.compass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.SunDay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Sunrise, sunset and civil twilight for planning the day in the field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SunScreen(onBack: () -> Unit, viewModel: SunViewModel = hiltViewModel()) {
    val place by viewModel.place.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val locating by viewModel.locating.collectAsStateWithLifecycle()
    val zone = viewModel.zone

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日出日落") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = viewModel::locate, enabled = !locating) { Icon(Icons.Default.MyLocation, "重新定位") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (locating) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                val p = place
                Text(
                    when {
                        p == null -> "还不知道位置：请允许定位，或先在地图上打开测区"
                        p.fromGps -> "当前位置 " + CoordinateFormatter.format(p.position, CoordinateFormat.DMS)
                        else -> "地图视野中心 " + CoordinateFormatter.format(p.position, CoordinateFormat.DMS) + "（定位后会更新）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            days.firstOrNull()?.let { today -> item { Today(today, now, zone) } }
            if (days.size > 1) {
                item { Text("接下来几天", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp)) }
                items(days.drop(1), key = { it.date.toEpochDay() }) { d ->
                    ListItem(
                        headlineContent = { Text(DAY.format(d.date)) },
                        supportingContent = { Text(summary(d, zone)) },
                    )
                    HorizontalDivider()
                }
            }
            item {
                Text(
                    "按天文公式计算，未考虑地形遮挡；山谷里看到太阳会更晚、落得更早。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Today(d: SunDay, now: Instant, zone: ZoneId) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("今天", style = MaterialTheme.typography.titleMedium)
            Text(countdown(d, now), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            TimeRow("天亮（民用晨光）", d.dawn, zone)
            TimeRow("日出", d.sunrise, zone)
            TimeRow("正午", d.noon, zone)
            TimeRow("日落", d.sunset, zone)
            TimeRow("天黑（民用昏影）", d.dusk, zone)
            Text("白昼 " + hours(d.daylightMinutes), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TimeRow(label: String, at: Instant?, zone: ZoneId) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(at?.let { CLOCK.withZone(zone).format(it) } ?: "—", style = MaterialTheme.typography.bodyLarge)
    }
}

/** The one line that matters while out: how long the light lasts. */
internal fun countdown(d: SunDay, now: Instant): String {
    d.polar?.let { return if (it == SunDay.Polar.DAY) "今天太阳不落山" else "今天太阳不升起" }
    val sunset = d.sunset ?: return ""
    val dusk = d.dusk
    return when {
        d.sunrise != null && now < d.sunrise -> "还有 ${left(now, d.sunrise)} 日出"
        now < sunset -> "距日落还有 ${left(now, sunset)}" + (dusk?.let { "，天黑前 ${left(now, it)}" } ?: "")
        dusk != null && now < dusk -> "太阳已落，还有 ${left(now, dusk)} 天黑"
        else -> "天已经黑了"
    }
}

private fun left(from: Instant, to: Instant): String {
    val m = Duration.between(from, to).toMinutes().coerceAtLeast(0)
    return hours(m)
}

private fun hours(minutes: Long) = if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分" else "$minutes 分钟"

private fun summary(d: SunDay, zone: ZoneId): String {
    d.polar?.let { return if (it == SunDay.Polar.DAY) "极昼" else "极夜" }
    val f = CLOCK.withZone(zone)
    return "日出 ${d.sunrise?.let(f::format) ?: "—"} · 日落 ${d.sunset?.let(f::format) ?: "—"} · 天黑 ${d.dusk?.let(f::format) ?: "—"}"
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("M 月 d 日 EEEE", Locale.CHINA)
