package org.geocrew.feature.record

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.ui.focus.onFocusChanged
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.FieldType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import org.geocrew.core.model.photoInfo
import kotlinx.coroutines.launch
import org.geocrew.core.model.ElevationProfile
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import org.geocrew.core.model.TaskInfo
import org.geocrew.core.model.TaskStatus
import org.geocrew.core.model.task
import org.geocrew.core.model.areaSquareMeters
import org.geocrew.core.model.lengthMeters
import org.geocrew.core.model.string
import org.geocrew.core.model.double
import org.geocrew.core.model.AudioInfo
import org.geocrew.core.model.audio
import org.geocrew.core.model.audioInfo
import org.geocrew.core.data.team.CommentItem
import org.geocrew.core.ui.CommentBubble
import org.geocrew.core.ui.CommentComposer
import org.geocrew.core.ui.InfoRow
import org.geocrew.core.ui.KindUi
import org.geocrew.core.ui.SectionHeader
import org.geocrew.core.model.Sample
import org.geocrew.core.model.SampleTypes
import org.geocrew.core.model.samples
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
    /** Opens the cross-section analysis with this line as the section. */
    onSection: ((String) -> Unit)? = null,
    onSeek: ((String) -> Unit)? = null,
    viewModel: FeatureDetailViewModel = hiltViewModel(),
) {
    val feature by viewModel.feature.collectAsStateWithLifecycle()
    val format by viewModel.coordinateFormat.collectAsStateWithLifecycle()
    val shared by viewModel.shared.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val fieldError by viewModel.fieldError.collectAsStateWithLifecycle()
    val commentAccess by viewModel.commentAccess.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val commentError by viewModel.commentError.collectAsStateWithLifecycle()
    val assignees by viewModel.assignees.collectAsStateWithLifecycle()
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
                        if (onSeek != null && f.geometry is Geometry.Point) {
                            IconButton(onClick = { onSeek(f.id) }) { Icon(Icons.Default.NearMe, "寻点前往") }
                        }
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
            commentAccess?.let { access ->
                SectionHeader("任务")
                TaskSection(
                    task = f.task,
                    access = access,
                    choices = assignees,
                    onLoadChoices = viewModel::loadAssignees,
                    onAssign = viewModel::assign,
                    onStatus = viewModel::setTaskStatus,
                    onClear = viewModel::clearTask,
                )
            }
            if (f.kind == FeatureKind.GEO_POINT || f.attitude != null) {
                SectionHeader("产状")
                AttitudeEditor(f.attitude, viewModel::setAttitude, onMeasure = onMeasureAttitude?.let { { it(f.id) } })
            }
            val fields = form.filter { it.appliesTo(f.kind) }
            if (fields.isNotEmpty()) {
                SectionHeader("表单")
                FormFields(f, fields, viewModel::setField)
            }
            SectionHeader(if (fields.isEmpty()) "属性" else "其他属性")
            Attributes(f, form.map { it.key }.toSet(), viewModel::setAttribute, viewModel::removeAttribute)
            SectionHeader("照片")
            Photos(f, viewModel)
            val samples = f.samples
            SectionHeader(if (samples.isEmpty()) "样品" else "样品（${samples.size}）")
            Samples(f, samples, viewModel)
            SectionHeader(if (f.audio.isEmpty()) "录音" else "录音（${f.audio.size}）")
            VoiceNotes(f, viewModel)
            SectionHeader("信息")
            Info(f, format)
            if (onSection != null && (f.geometry is Geometry.LineString || f.geometry is Geometry.MultiLineString) && f.kind != FeatureKind.TRACK) {
                OutlinedButton(onClick = { onSection(f.id) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("作为剖面线，投影产状算视倾角") }
            }
            commentAccess?.let { access ->
                SectionHeader(if (comments.isEmpty()) "讨论" else "讨论（${comments.size}）")
                Discussion(comments, access, viewModel::postComment, viewModel::deleteComment)
            }
            if (shared) {
                SectionHeader("修改历史")
                History(history, viewModel::loadHistory, viewModel::restore)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    commentError?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::consumeCommentError,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::consumeCommentError) { Text("好") } },
        )
    }

    fieldError?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::consumeFieldError,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::consumeFieldError) { Text("好") } },
        )
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
            val used by vm.usedLithologies.collectAsStateWithLifecycle()
            val picks = remember(lithology, used) { org.geocrew.core.model.Lithology.suggest(lithology, used) }
            if (picks.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(picks) { name ->
                        androidx.compose.material3.SuggestionChip(onClick = { lithology = name }, label = { Text(name) })
                    }
                }
            }
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
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<File?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val photoMessage by vm.photoMessage.collectAsStateWithLifecycle()
    LaunchedEffect(photoMessage) {
        photoMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumePhotoMessage() }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending
        pending = null
        if (ok && file != null) vm.addCaptured(file) else file?.delete()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addPicked(uri)
    }
    val info = f.photoInfo
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val (file, uri) = vm.newCaptureTarget()
                pending = file
                // Note the camera's direction before the camera app takes over.
                scope.launch {
                    vm.takeAim()
                    camera.launch(uri)
                }
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
                    Box {
                        PhotoThumb(
                            vm.photos.file(sha),
                            Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(
                                onClick = { viewing = sha },
                                onLongClick = { removing = sha },
                            ),
                        )
                        info[sha]?.azimuth?.let { az ->
                            Text(
                                "%.0f°".format(java.util.Locale.ROOT, az),
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.align(Alignment.BottomStart)
                                    .padding(4.dp)
                                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
            Text("点照片看拍摄信息，长按移除；照片右下角是拍摄方向", style = MaterialTheme.typography.bodySmall)
        }
    }
    viewing?.let { sha ->
        val p = info[sha]
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text("照片") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PhotoThumb(vm.photos.file(sha), Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)), targetPx = 1080)
                    val lines = p?.stamp(null, null, java.time.ZoneId.systemDefault(), vm.coordinateFormat.value).orEmpty()
                    if (lines.isEmpty()) Text("这张照片没有记下拍摄方向和位置", style = MaterialTheme.typography.bodySmall)
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    TextButton(onClick = {
                        if (vm.photos.exists(sha)) {
                            val intent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(vm.photos.uri(sha), "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(intent) }
                        }
                    }) { Text("用其他应用查看原图") }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveStamped(context.applicationContext, sha); viewing = null }) { Text("另存带标注的照片") }
            },
            dismissButton = { TextButton(onClick = { viewing = null }) { Text("关闭") } },
        )
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

/** Samples taken at this record (protocol §6.4): tap to edit, long press to remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Samples(f: GeoFeature, samples: List<Sample>, vm: FeatureDetailViewModel) {
    val used by vm.usedSampleNumbers.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<Sample?, Sample>?>(null) }
    var removing by remember { mutableStateOf<Sample?>(null) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        samples.forEach { s ->
            Column(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { editing = s to s }, onLongClick = { removing = s })
                    .padding(vertical = 6.dp),
            ) {
                Text(listOfNotNull(s.no, s.type).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        OutlinedButton(onClick = { editing = null to Sample(SampleTypes.nextNumber(f.title, used)) }) {
            Icon(Icons.Default.Science, null)
            Text(" 添加样品")
        }
        if (samples.isNotEmpty()) Text("点一下修改，长按移除。导出「样品清单」可得到送样单", style = MaterialTheme.typography.bodySmall)
    }
    editing?.let { (original, start) ->
        SampleDialog(
            start = start,
            isNew = original == null,
            onSave = { vm.saveSample(original, it); editing = null },
            onDismiss = { editing = null },
        )
    }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除样品 ${s.no}？") },
            text = { Text("只是从记录上去掉这条登记，不影响已经送出的样品。") },
            confirmButton = { TextButton(onClick = { vm.removeSample(s.no); removing = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SampleDialog(start: Sample, isNew: Boolean, onSave: (Sample) -> Unit, onDismiss: () -> Unit) {
    var no by remember { mutableStateOf(start.no) }
    var type by remember { mutableStateOf(start.type.orEmpty()) }
    var note by remember { mutableStateOf(start.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加样品" else "修改样品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(no, { no = it }, label = { Text("样品编号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(type, { type = it }, label = { Text("样品类型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SampleTypes.common.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = if (type == t) "" else t }, label = { Text(t) })
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("备注（位置、新鲜程度、重量等）") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(start.copy(no = no.trim(), type = type.trim().ifBlank { null }, note = note.trim().ifBlank { null }))
            }, enabled = no.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Voice notes: record a spoken observation, play it back, long press to remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceNotes(f: GeoFeature, vm: FeatureDetailViewModel) {
    val context = LocalContext.current
    val recording by vm.recording.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else denied = true
    }
    val info = f.audioInfo
    val zone = java.time.ZoneId.systemDefault()
    val time = remember { java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm") }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = {
            val has = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (has) vm.startRecording() else mic.launch(android.Manifest.permission.RECORD_AUDIO)
        }) {
            Icon(Icons.Default.Mic, null)
            Text(" 录一段")
        }
        if (denied) Text("没有麦克风权限，可以在系统设置里给同勘打开", style = MaterialTheme.typography.bodySmall)
        f.audio.forEachIndexed { i, sha ->
            val a = info[sha]
            val playing = playback?.takeIf { it.sha == sha }
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { vm.togglePlay(sha) }, onLongClick = { removing = sha }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (playing != null) Icons.Default.Stop else Icons.Default.PlayArrow, if (playing != null) "停止" else "播放")
                Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                    Text(
                        listOfNotNull(
                            "录音 ${i + 1}",
                            a?.durationSeconds?.let(AudioInfo::clock),
                            a?.recordedAt?.let { time.withZone(zone).format(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (playing != null && playing.durationMillis > 0) {
                        LinearProgressIndicator(
                            progress = { playing.positionMillis.toFloat() / playing.durationMillis },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        if (f.audio.isNotEmpty()) Text("点一下播放，长按移除", style = MaterialTheme.typography.bodySmall)
    }
    recording?.let { r ->
        AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            icon = { Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("正在录音 " + AudioInfo.clock(r.elapsedMillis / 1000.0)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { r.level }, modifier = Modifier.fillMaxWidth())
                    Text("说出看到的现象，停止后附在这条记录上，同步时一起上传。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::stopRecording) { Text("停止并保存") } },
            dismissButton = { TextButton(onClick = vm::cancelRecording) { Text("放弃") } },
        )
    }
    removing?.let { sha ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除这段录音？") },
            confirmButton = { TextButton(onClick = { vm.removeAudio(sha); removing = null }) { Text("移除") } },
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
                f.properties.double(org.geocrew.core.model.Props.ACCURACY_M)?.let { acc ->
                    val n = f.properties.double(org.geocrew.core.model.Props.GPS_SAMPLES)?.toInt()
                    val spread = f.properties.double(org.geocrew.core.model.Props.GPS_SPREAD_M)
                    InfoRow(
                        "定位精度",
                        "约 %.0f m".format(acc) + if (n != null && n > 1) "（平均 $n 次" + (spread?.let { "，离散 %.1f m".format(it) } ?: "") + "）" else "",
                    )
                }
            }
            is Geometry.Polygon, is Geometry.MultiPolygon -> {
                InfoRow("面积", CoordinateFormatter.area(g.areaSquareMeters()))
                InfoRow("周长", CoordinateFormatter.distance(g.lengthMeters()))
            }
            null -> InfoRow("几何", "无")
            else -> {
                InfoRow("长度", CoordinateFormatter.distance(g.lengthMeters()))
                val path = (g as? Geometry.LineString)?.positions
                    ?: (g as? Geometry.MultiLineString)?.lines?.maxByOrNull { it.size }
                val profile = remember(path) { path?.let { ElevationProfile.of(it) } }
                if (profile != null) {
                    InfoRow("海拔", "%.0f – %.0f m".format(profile.min, profile.max))
                    InfoRow("累计爬升 / 下降", "%.0f m / %.0f m".format(profile.ascent, profile.descent))
                    ElevationChart(profile, Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp))
                }
            }
        }
        InfoRow("创建", fullTime.format(f.createdAt))
        InfoRow("修改", fullTime.format(f.sync.updatedAt))
        InfoRow("版本", f.sync.version.toString())
        InfoRow("同步", if (f.sync.dirty) "未同步" else "已同步")
    }
}

/**
 * The project's form fields, typed: numbers get a number keyboard and choices
 * a menu. Each field saves when it loses focus or a choice is picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormFields(f: GeoFeature, fields: List<FieldTemplate>, onSet: (FieldTemplate, String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { field ->
            val stored = field.display(f.properties[field.key])
            val suffix: (@Composable () -> Unit)? = field.unit.takeIf { it.isNotBlank() }?.let { u -> { Text(u) } }
            if (field.type == FieldType.CHOICE) {
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        value = stored, onValueChange = {}, readOnly = true, label = { Text(field.label) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        (field.options + listOfNotNull(stored.takeIf { it.isNotEmpty() && it !in field.options })).forEach { o ->
                            DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSet(field, o) })
                        }
                        if (stored.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("清空", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onSet(field, "") })
                        }
                    }
                }
            } else {
                var text by remember(f.id, field.key, stored) { mutableStateOf(stored) }
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text(field.label) }, singleLine = true,
                    suffix = suffix,
                    keyboardOptions = if (field.type == FieldType.NUMBER) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text != stored) onSet(field, text) },
                )
            }
        }
    }
}

/** Free key/value attributes, e.g. a sample number or a measured grade. */
@Composable
private fun Attributes(f: GeoFeature, formKeys: Set<String>, onSet: (String, String) -> Unit, onRemove: (String) -> Unit) {
    val entries = f.properties.entries.filter { it.key !in RESERVED_KEYS && it.key !in formKeys && it.value is kotlinx.serialization.json.JsonPrimitive }
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
        val bad = key.trim() in RESERVED_KEYS || key.trim() in formKeys
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (k0.isEmpty()) "添加属性" else "修改属性") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, label = { Text("名称") }, singleLine = true,
                        enabled = k0.isEmpty(), isError = bad,
                        supportingText = if (bad) ({ Text("这个名称已被应用或工程表单使用，请换一个") }) else null,
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

@Composable
private fun History(state: HistoryState, onLoad: () -> Unit, onRestore: (Revision) -> Unit) {
    var confirm by remember { mutableStateOf<Revision?>(null) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            HistoryState.Idle -> TextButton(onClick = onLoad) { Text("查看组内的修改记录") }
            HistoryState.Loading -> Text("读取中…", style = MaterialTheme.typography.bodySmall)
            is HistoryState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onLoad) { Text("重试") }
            }
            is HistoryState.Loaded -> if (state.revisions.isEmpty()) {
                Text("服务器上没有这条记录的历史", style = MaterialTheme.typography.bodySmall)
            } else state.revisions.forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "第 ${r.feature.sync.version} 版 · ${r.author}" + if (r.feature.deleted) " · 删除" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            fullTime.format(r.feature.sync.updatedAt) + (r.feature.title?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (i > 0 && !r.feature.deleted) TextButton(onClick = { confirm = r }) { Text("恢复") }
                }
            }
        }
    }
    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("恢复到第 ${r.feature.sync.version} 版？") },
            text = { Text("会用这一版的位置和属性生成一个新版本，之前的版本仍保留在历史里。") },
            confirmButton = { TextButton(onClick = { onRestore(r); confirm = null }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

/** Elevation against distance along the path, with the lowest and highest values marked on the axis. */
@Composable
private fun ElevationChart(profile: ElevationProfile, modifier: Modifier) {
    val pts = remember(profile) { profile.downsampled(240) }
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.15f)
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Canvas(modifier) {
            val span = (profile.max - profile.min).coerceAtLeast(10.0)
            val lo = profile.min - span * 0.05
            val hi = lo + span * 1.1
            val len = profile.length.coerceAtLeast(1.0)
            fun x(d: Double) = (d / len * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - lo) / (hi - lo) * size.height).toFloat()
            val path = Path().apply {
                pts.forEachIndexed { i, s -> if (i == 0) moveTo(x(s.distance), y(s.elevation)) else lineTo(x(s.distance), y(s.elevation)) }
            }
            val area = Path().apply {
                addPath(path)
                lineTo(x(pts.last().distance), size.height)
                lineTo(x(pts.first().distance), size.height)
                close()
            }
            drawPath(area, fill)
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.labelSmall, color = axis)
            Text(CoordinateFormatter.distance(profile.length), style = MaterialTheme.typography.labelSmall, color = axis)
        }
    }
}

/** Who this record is handed to and how far they got; synced like any other property. */
@Composable
private fun TaskSection(
    task: TaskInfo?,
    access: FeatureDetailViewModel.CommentAccess,
    choices: FeatureDetailViewModel.AssigneeChoices,
    onLoadChoices: () -> Unit,
    onAssign: (FeatureDetailViewModel.Assignee) -> Unit,
    onStatus: (TaskStatus) -> Unit,
    onClear: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val canEdit = access.canPost
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (task == null) {
            Text(
                if (canEdit) "未指派。可以把这条记录交给某位组员去完成，例如去这个点采样。" else "未指派。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canEdit) {
                OutlinedButton(onClick = { picking = true; onLoadChoices() }) { Text("指派给…") }
            }
        } else {
            val who = task.assigneeName.ifBlank { "组员" } + if (task.assignee == access.memberId) "（我）" else ""
            Text("指派给 $who", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskStatus.entries.forEach { s ->
                    FilterChip(
                        selected = task.status == s,
                        onClick = { onStatus(s) },
                        enabled = canEdit,
                        label = { Text(s.label) },
                    )
                }
            }
            if (canEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { picking = true; onLoadChoices() }) { Text("改派") }
                    TextButton(onClick = onClear) { Text("取消指派") }
                }
            }
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("指派给") },
            text = {
                Column {
                    when (choices) {
                        FeatureDetailViewModel.AssigneeChoices.Idle,
                        FeatureDetailViewModel.AssigneeChoices.Loading -> Text("正在读取组员…")
                        is FeatureDetailViewModel.AssigneeChoices.Loaded -> {
                            if (choices.offline) {
                                Text(
                                    "没有连上服务器，现在只能指派给自己；联网后可以选其他组员。",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            choices.members.forEach { m ->
                                TextButton(onClick = { onAssign(m); picking = false }, modifier = Modifier.fillMaxWidth()) {
                                    Text(m.nickname + if (m.me) "（我）" else "", Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("取消") } },
        )
    }
}

/** Comments on this record; everyone in the workspace sees them when they open it. */
@Composable
private fun Discussion(
    comments: List<CommentItem>,
    access: FeatureDetailViewModel.CommentAccess,
    onPost: (String) -> Unit,
    onDelete: (CommentItem) -> Unit,
) {
    var confirm by remember { mutableStateOf<CommentItem?>(null) }
    Column {
        if (comments.isEmpty()) {
            Text(
                "还没有人讨论这条记录。" + if (access.canPost) "写下的内容会同步给全组。" else "",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        comments.forEach { c ->
            CommentBubble(
                nickname = c.nickname, body = c.body, time = c.createdAt, mine = c.mine, pending = c.pending,
                onLongPress = if (c.mine || access.isLeader) ({ confirm = c }) else null,
            )
        }
        if (access.canPost) {
            CommentComposer(onPost, placeholder = "讨论这条记录…")
        } else {
            Text("只读成员不能发言", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("删除这条发言？") },
            text = { Text(if (c.pending) "还没有发出，删除后不会再发送。" else "全组都会看不到这条发言。") },
            confirmButton = { TextButton(onClick = { onDelete(c); confirm = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}
