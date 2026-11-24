package org.geocrew.feature.record

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.lengthMeters
import org.geocrew.core.model.string
import org.geocrew.core.ui.InfoRow
import org.geocrew.core.ui.KindUi
import org.geocrew.core.ui.SectionHeader
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fullTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureDetailScreen(
    onBack: () -> Unit,
    onShowOnMap: (String) -> Unit,
    onMeasureAttitude: ((String) -> Unit)? = null,
    viewModel: FeatureDetailViewModel = hiltViewModel(),
) {
    val feature by viewModel.feature.collectAsStateWithLifecycle()
    val format by viewModel.coordinateFormat.collectAsStateWithLifecycle()
    val f = feature
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(f?.let { KindUi.label(it.kind) } ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (f != null) {
                        IconButton(onClick = { onShowOnMap(f.id) }) { Icon(Icons.Default.Map, "在地图上查看") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "删除") }
                    }
                },
            )
        },
    ) { padding ->
        if (f == null || f.deleted) {
            Text("这条记录已删除。", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            TextFields(f, viewModel)
            if (f.geometry is Geometry.Point) KindSwitch(f.kind, viewModel::setKind)
            if (f.kind == FeatureKind.GEO_POINT || f.attitude != null) {
                SectionHeader("产状")
                AttitudeEditor(f.attitude, viewModel::setAttitude, onMeasure = onMeasureAttitude?.let { { it(f.id) } })
            }
            SectionHeader("属性")
            Attributes(f, viewModel::setAttribute, viewModel::removeAttribute)
            SectionHeader("照片")
            Photos(f, viewModel)
            SectionHeader("信息")
            Info(f, format)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("加入工作组后，删除会同步给其他组员。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(); onBack() }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** Text is edited locally and saved when the screen goes away, so typing never creates a revision per key. */
@Composable
private fun TextFields(f: GeoFeature, vm: FeatureDetailViewModel) {
    var title by rememberSaveable(f.id) { mutableStateOf(f.title.orEmpty()) }
    var note by rememberSaveable(f.id) { mutableStateOf(f.note.orEmpty()) }
    var lithology by rememberSaveable(f.id) { mutableStateOf(f.properties.string(Props.LITHOLOGY).orEmpty()) }
    val latest by rememberUpdatedState(Triple(title, note, lithology))
    DisposableEffect(f.id) {
        onDispose { vm.saveText(latest.first, latest.second, latest.third) }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("名称 / 编号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (f.kind == FeatureKind.GEO_POINT) {
            OutlinedTextField(lithology, { lithology = it }, label = { Text("岩性") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(note, { note = it }, label = { Text("描述 / 备注") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.saveText(title, note, lithology) }) { Text("保存") }
        }
    }
}

@Composable
private fun KindSwitch(kind: FeatureKind, onChange: (FeatureKind) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(FeatureKind.POINT, FeatureKind.GEO_POINT, FeatureKind.PHOTO).forEach { k ->
            FilterChip(selected = kind == k, onClick = { onChange(k) }, label = { Text(KindUi.label(k)) })
        }
    }
}

@Composable
private fun AttitudeEditor(current: Attitude?, onSave: (Attitude?) -> Unit, onMeasure: (() -> Unit)?) {
    var useStrike by rememberSaveable { mutableStateOf(false) }
    var azimuth by remember(current, useStrike) {
        mutableStateOf(current?.let { "%.0f".format(if (useStrike) it.strike else it.dipDirection) } ?: "")
    }
    var dip by remember(current) { mutableStateOf(current?.let { "%.0f".format(it.dip) } ?: "") }
    val az = azimuth.toDoubleOrNull()?.takeIf { it in 0.0..360.0 }
    val d = dip.toDoubleOrNull()?.takeIf { it in 0.0..90.0 }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !useStrike, onClick = { useStrike = false }, label = { Text("倾向") })
            FilterChip(selected = useStrike, onClick = { useStrike = true }, label = { Text("走向（右手法则）") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                azimuth, { azimuth = it }, label = { Text(if (useStrike) "走向 °" else "倾向 °") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                dip, { dip = it }, label = { Text("倾角 °") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                enabled = az != null && d != null,
                onClick = { onSave(if (useStrike) Attitude.fromStrike(az!!, d!!) else Attitude.of(az!!, d!!)) },
            ) { Text("保存产状") }
            if (onMeasure != null) {
                OutlinedButton(onClick = onMeasure) {
                    Icon(Icons.Default.Explore, null)
                    Text(" 罗盘测量")
                }
            }
            if (current != null) TextButton(onClick = { onSave(null) }) { Text("清除") }
        }
        current?.let {
            Text("当前：${it.format()}（走向 %.0f°）".format(it.strike), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Photos(f: GeoFeature, vm: FeatureDetailViewModel) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<File?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending
        pending = null
        if (ok && file != null) vm.addCaptured(file) else file?.delete()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addPicked(uri)
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val (file, uri) = vm.newCaptureTarget()
                pending = file
                camera.launch(uri)
            }) {
                Icon(Icons.Default.PhotoCamera, null)
                Text(" 拍照")
            }
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Icon(Icons.Default.AddPhotoAlternate, null)
                Text(" 相册")
            }
        }
        if (f.photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(f.photos) { sha ->
                    PhotoThumb(
                        vm.photos.file(sha),
                        Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(
                            onClick = {
                                if (vm.photos.exists(sha)) {
                                    val intent = Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(vm.photos.uri(sha), "image/jpeg")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    runCatching { context.startActivity(intent) }
                                }
                            },
                            onLongClick = { removing = sha },
                        ),
                    )
                }
            }
            Text("长按照片可移除", style = MaterialTheme.typography.bodySmall)
        }
    }
    removing?.let { sha ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除这张照片？") },
            confirmButton = { TextButton(onClick = { vm.removePhoto(sha); removing = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Info(f: GeoFeature, format: org.geocrew.core.model.CoordinateFormat) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        when (val g = f.geometry) {
            is Geometry.Point -> {
                InfoRow("坐标", CoordinateFormatter.format(g.position, format))
                g.position.alt?.let { InfoRow("海拔", "%.1f m".format(it)) }
            }
            is Geometry.Polygon, is Geometry.MultiPolygon -> {
                InfoRow("面积", CoordinateFormatter.area(g.areaSquareMeters()))
                InfoRow("周长", CoordinateFormatter.distance(g.lengthMeters()))
            }
            null -> InfoRow("几何", "无")
            else -> InfoRow("长度", CoordinateFormatter.distance(g.lengthMeters()))
        }
        InfoRow("创建", fullTime.format(f.createdAt))
        InfoRow("修改", fullTime.format(f.sync.updatedAt))
        InfoRow("版本", f.sync.version.toString())
        InfoRow("同步", if (f.sync.dirty) "未同步" else "已同步")
    }
}

/** Free key/value attributes, e.g. a sample number or a measured grade. */
@Composable
private fun Attributes(f: GeoFeature, onSet: (String, String) -> Unit, onRemove: (String) -> Unit) {
    val entries = f.properties.entries.filter { it.key !in RESERVED_KEYS && it.value is kotlinx.serialization.json.JsonPrimitive }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { (k, v) ->
            val text = (v as kotlinx.serialization.json.JsonPrimitive).content
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { editing = k to text }) { Text("改") }
                IconButton(onClick = { onRemove(k) }) { Icon(Icons.Default.Delete, "删除属性 $k") }
            }
        }
        if (entries.isEmpty()) {
            Text("没有其他属性。数值属性（如品位、厚度）可用于插值和统计。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { editing = "" to "" }) { Text("添加属性") }
    }
    editing?.let { (k0, v0) ->
        var key by remember(k0) { mutableStateOf(k0) }
        var value by remember(k0) { mutableStateOf(v0) }
        val bad = key.trim() in RESERVED_KEYS
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (k0.isEmpty()) "添加属性" else "修改属性") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, label = { Text("名称") }, singleLine = true,
                        enabled = k0.isEmpty(), isError = bad,
                        supportingText = if (bad) ({ Text("这个名称由应用使用，请换一个") }) else null,
                    )
                    OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("值") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = key.isNotBlank() && !bad, onClick = { onSet(key, value); editing = null }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}
