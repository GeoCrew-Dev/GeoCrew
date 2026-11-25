package org.geocrew.feature.compass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.ThicknessResult
import org.geocrew.core.model.TraverseLeg
import org.geocrew.core.ui.SectionHeader

/** Text input of one traverse leg. */
private data class LegInput(val azimuth: String = "", val slope: String = "0", val distance: String = "", val fromPoints: Boolean = false) {
    fun parse(): TraverseLeg? {
        val a = azimuth.toDoubleOrNull()?.takeIf { it in 0.0..360.0 } ?: return null
        val s = slope.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
        val d = distance.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return TraverseLeg(a, s, d)
    }

    companion object {
        fun of(l: TraverseLeg) = LegInput("%.1f".format(l.azimuth), "%.1f".format(l.slopeAngle), "%.2f".format(l.slopeDistance), fromPoints = true)
    }
}

private enum class PickFor { ATTITUDE, FROM, TO }

/**
 * Bed thickness from a measured traverse between the two contacts: the
 * attitude of the bed plus one or more legs (azimuth, slope angle, slope
 * distance), typed in or taken from two recorded points.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThicknessScreen(onBack: () -> Unit, viewModel: ThicknessViewModel = hiltViewModel()) {
    val picks by viewModel.picks.collectAsStateWithLifecycle()
    var dipDirection by rememberSaveable { mutableStateOf("") }
    var dip by rememberSaveable { mutableStateOf("") }
    val legs = remember { mutableStateListOf(LegInput()) }
    var picking by remember { mutableStateOf<PickFor?>(null) }
    var fromPoint by remember { mutableStateOf<ThicknessPick?>(null) }

    val attitude = run {
        val dd = dipDirection.toDoubleOrNull()?.takeIf { it in 0.0..360.0 }
        val d = dip.toDoubleOrNull()?.takeIf { it in 0.0..90.0 }
        if (dd != null && d != null) Attitude.of(dd, d) else null
    }
    val parsed = legs.map { it.parse() }
    val result = if (attitude != null && parsed.isNotEmpty() && parsed.all { it != null }) ThicknessResult.of(attitude, parsed.filterNotNull()) else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("地层厚度") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("岩层产状") }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField(dipDirection, { dipDirection = it }, "倾向 °", Modifier.weight(1f))
                    NumberField(dip, { dip = it }, "倾角 °", Modifier.weight(1f))
                }
            }
            if (picks.any { it.attitude != null }) {
                item {
                    TextButton(onClick = { picking = PickFor.ATTITUDE }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("用记录里的产状") }
                }
            }

            item { SectionHeader("导线（从一侧界线量到另一侧）") }
            item {
                Text(
                    "每段填方位、坡角和斜距。坡角上坡为正、下坡为负；导线不必垂直走向，也可以分几段，结果按向量相加。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            itemsIndexed(legs) { i, leg ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NumberField(leg.azimuth, { legs[i] = leg.copy(azimuth = it, fromPoints = false) }, "方位 °", Modifier.weight(1f))
                    NumberField(leg.slope, { legs[i] = leg.copy(slope = it, fromPoints = false) }, "坡角 °", Modifier.weight(1f), signed = true)
                    NumberField(leg.distance, { legs[i] = leg.copy(distance = it, fromPoints = false) }, "斜距 m", Modifier.weight(1f))
                    IconButton(onClick = { legs.removeAt(i) }, enabled = legs.size > 1) { Icon(Icons.Default.Close, "删除这一段") }
                }
                result?.perLeg?.getOrNull(i)?.let { part ->
                    Text(
                        "这一段贡献 %+.2f m".format(part) + if (leg.fromPoints) "（由两个记录点算出）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { legs.add(LegInput()) }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("加一段")
                    }
                    if (picks.count { it.position != null } >= 2) {
                        TextButton(onClick = { picking = PickFor.FROM }) {
                            Icon(Icons.Default.Place, null)
                            Spacer(Modifier.width(4.dp))
                            Text("用两个记录点")
                        }
                    }
                }
            }

            item { SectionHeader("结果") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        if (result == null) {
                            Text(
                                if (attitude == null) "填好倾向（0–360）和倾角（0–90）" else "把每一段的方位、坡角和斜距填完整",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text("真厚度 %.2f m".format(result.trueThickness), style = MaterialTheme.typography.headlineSmall)
                            result.verticalThickness?.let { Text("铅直厚度 %.2f m".format(it)) }
                            result.horizontalWidth?.let { Text("水平地面上的出露宽度 %.2f m".format(it)) }
                            Spacer(Modifier.heightIn(min = 6.dp))
                            Text(
                                if (result.towardsDip) "导线朝倾向一侧，正常层序下是从老地层量向新地层。" else "导线朝反倾向一侧，正常层序下是从新地层量向老地层。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (result.mixedDirections) {
                                Text(
                                    "有的段朝倾向、有的段背向倾向，说明导线来回穿过了部分地层，已按向量抵消。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.heightIn(min = 32.dp)) }
        }
    }

    when (val p = picking) {
        null -> Unit
        PickFor.ATTITUDE -> PickDialog("选择产状", picks.filter { it.attitude != null }, { it.attitude!!.format() }, onDismiss = { picking = null }) { pick ->
            pick.attitude?.let {
                dipDirection = "%.0f".format(it.dipDirection)
                dip = "%.0f".format(it.dip)
            }
            picking = null
        }
        PickFor.FROM, PickFor.TO -> PickDialog(
            if (p == PickFor.FROM) "起点（一侧界线上的点）" else "终点（另一侧界线上的点）",
            picks.filter { it.position != null && it.id != fromPoint?.id },
            { pick -> pick.position!!.alt?.let { "海拔 %.0f m".format(it) } ?: "无海拔，按水平计" },
            onDismiss = { picking = null; fromPoint = null },
        ) { pick ->
            if (p == PickFor.FROM) {
                fromPoint = pick
                picking = PickFor.TO
            } else {
                val a = fromPoint?.position
                val b = pick.position
                if (a != null && b != null) {
                    val leg = LegInput.of(TraverseLeg.between(a, b))
                    // Replace the untouched empty first leg instead of adding next to it.
                    if (legs.size == 1 && legs[0].parse() == null && legs[0].azimuth.isBlank()) legs[0] = leg else legs.add(leg)
                }
                fromPoint = null
                picking = null
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, signed: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> if (t.all { it.isDigit() || it == '.' || (signed && it == '-') }) onChange(t) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun PickDialog(
    title: String,
    items: List<ThicknessPick>,
    detail: (ThicknessPick) -> String,
    onDismiss: () -> Unit,
    onPick: (ThicknessPick) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(items, key = { it.id }) { pick ->
                    ListItem(
                        headlineContent = { Text(pick.title) },
                        supportingContent = { Text(detail(pick)) },
                        modifier = Modifier.clickable { onPick(pick) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
