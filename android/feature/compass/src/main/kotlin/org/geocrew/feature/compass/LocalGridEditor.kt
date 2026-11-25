package org.geocrew.feature.compass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.geocrew.core.model.CommonPoint
import org.geocrew.core.model.DatumShift
import org.geocrew.core.model.Ellipsoid
import org.geocrew.core.model.GaussKruger
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.LocalGrid
import java.util.Locale

private enum class Method(val label: String) { NONE("不转换"), PLANE("四参数"), BURSA("七参数") }

/** "七参数 ΔX −14.2 m ..." for a saved shift. */
fun DatumShift?.describe(): String = when (this) {
    null -> "只换椭球，不做基准转换"
    is DatumShift.Plane -> "四参数：ΔX %.3f m，ΔY %.3f m，旋转 %.4f″，尺度 %.4f ppm".format(Locale.ROOT, dx, dy, rotationSeconds, ppm)
    is DatumShift.Bursa -> "七参数：ΔX %.3f，ΔY %.3f，ΔZ %.3f m；旋转 %.4f″ %.4f″ %.4f″；尺度 %.4f ppm".format(Locale.ROOT, dx, dy, dz, rx, ry, rz, ppm)
}

/**
 * Sets up the project's local map grid: which ellipsoid and zone, and how
 * GPS positions get there. Parameters can be typed in from a survey
 * office's sheet, or fitted from a few points known in both systems.
 */
@Composable
internal fun LocalGridEditor(
    saved: LocalGrid?,
    onSave: (LocalGrid) -> Unit,
    onClear: () -> Unit,
    onLocate: ((LngLat) -> Unit) -> Unit,
    locating: Boolean,
) {
    var ellipsoid by rememberSaveable(saved) { mutableStateOf(saved?.ellipsoid ?: Ellipsoid.BEIJING54) }
    var width by rememberSaveable(saved) { mutableStateOf(saved?.width ?: 3) }
    var zoneText by rememberSaveable(saved) { mutableStateOf(saved?.zone?.toString() ?: "") }
    var name by rememberSaveable(saved) { mutableStateOf(saved?.name ?: "") }
    var method by rememberSaveable(saved) {
        mutableStateOf(when (saved?.shift) { is DatumShift.Plane -> Method.PLANE; is DatumShift.Bursa -> Method.BURSA; null -> Method.NONE })
    }
    val initial = when (val s = saved?.shift) {
        is DatumShift.Plane -> listOf(s.dx, s.dy, s.rotationSeconds, s.ppm)
        is DatumShift.Bursa -> listOf(s.dx, s.dy, s.dz, s.rx, s.ry, s.rz, s.ppm)
        null -> emptyList()
    }.map { "%.6f".format(Locale.ROOT, it).trimEnd('0').trimEnd('.') }
    var planeText by rememberSaveable(saved) { mutableStateOf(if (saved?.shift is DatumShift.Plane) initial else List(4) { "" }) }
    var bursaText by rememberSaveable(saved) { mutableStateOf(if (saved?.shift is DatumShift.Bursa) initial else List(7) { "" }) }
    var pointsText by rememberSaveable { mutableStateOf("") }
    var fitNote by rememberSaveable { mutableStateOf<String?>(null) }

    val zone = zoneText.toIntOrNull()?.takeIf { it in 1..GaussKruger.maxZone(width) }
    val shift: DatumShift? = when (method) {
        Method.NONE -> null
        Method.PLANE -> planeText.map { it.toDoubleOrNull() }.takeIf { v -> v.all { it != null } }?.let { v -> DatumShift.Plane(v[0]!!, v[1]!!, v[2]!!, v[3]!!) }
        Method.BURSA -> bursaText.map { it.toDoubleOrNull() }.takeIf { v -> v.all { it != null } }?.let { v -> DatumShift.Bursa(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!, v[5]!!, v[6]!!) }
    }
    val ready = zone != null && (method == Method.NONE || shift != null)

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "旧图、旧资料用北京 54、西安 80 坐标时，在这里设好坐标系和当地转换参数，换算页和坐标表导出就能直接给出旧坐标。参数可以抄当地测绘部门给的，也可以用几个两套坐标都已知的点（控制点、三角点）求出来。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (w in GaussKruger.WIDTHS) FilterChip(selected = width == w, onClick = { width = w }, label = { Text("$w° 带") })
            GridEllipsoidMenu(ellipsoid) { ellipsoid = it }
        }
        OutlinedTextField(
            value = zoneText,
            onValueChange = { t -> if (t.length <= 3 && t.all { it.isDigit() }) zoneText = t },
            label = { Text("带号") },
            supportingText = { Text("${width}° 带：1–${GaussKruger.maxZone(width)}，整个工区用同一带") },
            isError = zoneText.isNotEmpty() && zone == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称（可不填）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in Method.entries) FilterChip(selected = method == m, onClick = { method = m; fitNote = null }, label = { Text(m.label) })
        }
        when (method) {
            Method.NONE -> Text("只换椭球做投影。北京 54、西安 80 与 GPS 之间通常差几十到一百多米，只适合看个大概。", style = MaterialTheme.typography.bodySmall)
            Method.PLANE -> {
                Text("四参数在平面上平移、旋转、缩放，适合几十公里以内的工区；至少 2 个公共点。", style = MaterialTheme.typography.bodySmall)
                ParamFields(listOf("ΔX（m）", "ΔY（m）", "旋转（″）", "尺度（ppm）"), planeText) { planeText = it }
            }
            Method.BURSA -> {
                Text("七参数（布尔莎模型，坐标框架旋转）适合较大范围；至少 3 个带高程的公共点，点要分散。", style = MaterialTheme.typography.bodySmall)
                ParamFields(listOf("ΔX（m）", "ΔY（m）", "ΔZ（m）", "εX（″）", "εY（″）", "εZ（″）", "尺度（ppm）"), bursaText) { bursaText = it }
            }
        }
        if (method != Method.NONE) {
            Text("用公共点求参数", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = pointsText,
                onValueChange = { pointsText = it; fitNote = null },
                label = { Text("每行一个点：名称, 经度, 纬度, X, Y, 高程") },
                placeholder = { Text("G01, 117.1, 40.1, 4440123.45, 39508765.43, 152.3") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onLocate { p ->
                        val n = pointsText.lines().count { it.isNotBlank() } + 1
                        val line = "点$n, %.8f, %.8f, , ".format(Locale.ROOT, p.lng, p.lat) + (p.alt?.let { "%.2f".format(Locale.ROOT, it) } ?: "")
                        pointsText = (pointsText.trimEnd().takeIf { it.isNotEmpty() }?.plus("\n") ?: "") + line
                    }
                }, enabled = !locating) { Text("加当前位置") }
                Button(onClick = {
                    val (pts, problems) = CommonPoint.parse(pointsText)
                    val z = zone
                    fitNote = when {
                        z == null -> "先填带号"
                        problems.isNotEmpty() -> problems.joinToString("\n")
                        method == Method.PLANE -> LocalGrid.fitPlane(pts, width, z)?.let { fit ->
                            val s = fit.shift
                            planeText = listOf(s.dx, s.dy, s.rotationSeconds, s.ppm).map { "%.4f".format(Locale.ROOT, it) }
                            residualText(pts, fit.residuals, fit.rms)
                        } ?: "至少要 2 个不重合的公共点"
                        else -> LocalGrid.fitBursa(pts, ellipsoid, width, z)?.let { fit ->
                            val s = fit.shift
                            bursaText = listOf(s.dx, s.dy, s.dz, s.rx, s.ry, s.rz, s.ppm).map { "%.4f".format(Locale.ROOT, it) }
                            residualText(pts, fit.residuals, fit.rms) +
                                if (pts.any { it.h == null }) "\n有的点没有高程，按 0 m 算，七参数会偏；高程不全时建议用四参数。" else ""
                        } ?: "至少要 3 个分散的公共点；点太集中时请改用四参数"
                    }
                }, enabled = pointsText.isNotBlank()) { Text("求解") }
            }
            fitNote?.let { Card { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val z = zone ?: return@Button
                onSave(LocalGrid(name.trim().ifEmpty { defaultName(ellipsoid, width, z) }, ellipsoid, width, z, shift))
            }, enabled = ready) { Text("保存到本工程") }
            if (saved != null) TextButton(onClick = onClear) { Text("不再使用") }
        }
        if (saved != null) Text("当前：${saved.name}。${saved.shift.describe()}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun defaultName(e: Ellipsoid, width: Int, zone: Int) = e.label.substringBefore('（') + " · ${width}° 带第 $zone 带"

private fun residualText(points: List<CommonPoint>, residuals: List<Double>, rms: Double): String =
    points.zip(residuals).joinToString("\n") { (p, r) -> "%-8s 残差 %.3f m".format(Locale.ROOT, p.name, r) } +
        "\n中误差 %.3f m".format(Locale.ROOT, rms) + if (rms > 1) "\n残差偏大：检查是否有点抄错、X/Y 是否带错带号" else ""

@Composable
private fun ParamFields(labels: List<String>, values: List<String>, onChange: (List<String>) -> Unit) {
    labels.indices.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in pair) {
                OutlinedTextField(
                    value = values[i],
                    onValueChange = { t -> if (t.all { it.isDigit() || it == '.' || it == '-' }) onChange(values.toMutableList().also { it[i] = t }) },
                    label = { Text(labels[i]) },
                    isError = values[i].isNotEmpty() && values[i].toDoubleOrNull() == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            if (pair.size == 1) Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GridEllipsoidMenu(value: Ellipsoid, onChange: (Ellipsoid) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = true, onClick = { open = true }, label = { Text(value.label.substringBefore('（')) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (e in Ellipsoid.entries) DropdownMenuItem(text = { Text(e.label) }, onClick = { onChange(e); open = false })
        }
    }
}
