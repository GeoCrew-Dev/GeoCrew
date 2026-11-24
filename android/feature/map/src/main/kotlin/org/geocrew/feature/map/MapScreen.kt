package org.geocrew.feature.map

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import org.geocrew.core.model.LngLat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.geocrew.core.map.GeoCrewMap
import org.geocrew.core.map.rememberMapCameraState
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.CoordinateFormatter
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.bounds

@Composable
fun MapScreen(
    onOpenSettings: () -> Unit,
    onOpenFeature: (String) -> Unit,
    onOpenRecords: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onOpenTeam: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    /** A feature another screen asked to show; consumed once. */
    focusFeatureId: String? = null,
    /** A position another screen asked to show (e.g. a team message's); consumed once. */
    focusPosition: LngLat? = null,
    onFocusHandled: () -> Unit = {},
    viewModel: MapViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val baseMap by viewModel.baseMap.collectAsStateWithLifecycle()
    val overlays by viewModel.overlays.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val recording by viewModel.isRecording.collectAsStateWithLifecycle()
    val tracking by viewModel.tracking.state.collectAsStateWithLifecycle()
    val problem by viewModel.tileProblem.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val team by viewModel.team.collectAsStateWithLifecycle()
    val format = settings?.coordinateFormat ?: CoordinateFormat.DECIMAL
    val camera = rememberMapCameraState()

    var showLayers by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    /** A position reached through "前往坐标", marked until the next one. */
    var goneTo by remember { mutableStateOf<LngLat?>(null) }
    var goneLabel by remember { mutableStateOf(GONE_TO_LABEL) }
    var followOnFirstFix by remember { mutableStateOf(true) }
    val project by viewModel.project.collectAsStateWithLifecycle()

    // Each project opens where it was left; a new one follows the first fix instead.
    var restoredFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(project?.id) {
        val p = project ?: return@LaunchedEffect
        if (restoredFor == p.id) return@LaunchedEffect
        restoredFor = p.id
        p.config.view?.let { v ->
            camera.jumpTo(LngLat(v.lng, v.lat), v.zoom, v.bearing)
            followOnFirstFix = false
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.saveView(camera.center, camera.zoom, camera.bearing)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) viewModel.startLocation()
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(wanted.toTypedArray())
        viewModel.startLocation()
    }
    LaunchedEffect(fix) {
        val f = fix ?: return@LaunchedEffect
        if (followOnFirstFix) {
            followOnFirstFix = false
            camera.animateTo(f.position, 15.0)
        }
    }

    val featureList by viewModel.featureList.collectAsStateWithLifecycle()
    LaunchedEffect(focusFeatureId, featureList) {
        val id = focusFeatureId ?: return@LaunchedEffect
        val f = featureList.firstOrNull { it.id == id } ?: return@LaunchedEffect
        viewModel.select(id)
        f.geometry?.bounds()?.let { camera.fit(it) }
        followOnFirstFix = false
        onFocusHandled()
    }
    LaunchedEffect(focusPosition) {
        val p = focusPosition ?: return@LaunchedEffect
        goneTo = p
        goneLabel = "◎ 消息位置"
        camera.animateTo(p, maxOf(camera.zoom, 15.0))
        followOnFirstFix = false
        onFocusHandled()
    }

    val bm = baseMap ?: return
    Box(Modifier.fillMaxSize()) {
        GeoCrewMap(
            source = bm.source,
            keys = bm.keys,
            overlays = overlays,
            camera = camera,
            modifier = Modifier.fillMaxSize(),
            onClick = viewModel::onMapClick,
            onLongClick = { if (mode == MapMode.Browse) viewModel.addPoint(it) },
            labels = overlays.team.map { org.geocrew.core.map.MapLabel(it.position, it.nickname, faded = it.stale) } +
                listOfNotNull(goneTo?.let { org.geocrew.core.map.MapLabel(it, goneLabel) }),
        )

        if (mode is MapMode.Draw) Crosshair(Modifier.align(Alignment.Center))

        // Top: notices about the base map.
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            project?.let { p -> ProjectChip(p.name, p.team != null, onOpenProjects) }
            when {
                bm.showKeyPrompt -> KeyPromptCard(
                    onEnterKey = { viewModel.keyPromptSeen(); onOpenSettings() },
                    onSkip = { viewModel.skipKeyPrompt() },
                )
                problem != null -> TileProblemCard(
                    isTianditu = bm.source.keyId != null,
                    host = problem!!.host,
                    code = problem!!.httpCode,
                    onSwitch = { showLayers = true },
                    onCheckKey = onOpenSettings,
                    onDismiss = viewModel::dismissTileProblem,
                )
                bm.usingFallback -> Notice("所选底图还没有 Key，暂时显示「${bm.source.name}」。", "填写 Key", onOpenSettings)
            }
            if (tracking is org.geocrew.core.sensors.TrackingState.Recording) {
                val t = tracking as org.geocrew.core.sensors.TrackingState.Recording
                Notice("正在记录轨迹：${t.points} 个点，${CoordinateFormatter.distance(t.lengthMeters)}", null, null)
            }
        }

        // Right: map controls.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallFloatingActionButton(onClick = { showLayers = true }) { Icon(Icons.Default.Layers, "图层") }
            SmallFloatingActionButton(onClick = { camera.resetNorth() }) {
                Icon(Icons.Default.Navigation, "指北", Modifier.rotate(-camera.bearing.toFloat()))
            }
            SmallFloatingActionButton(onClick = {
                val f = fix
                if (f != null) camera.animateTo(f.position, maxOf(camera.zoom, 16.0)) else viewModel.startLocation()
            }) { Icon(Icons.Default.MyLocation, "定位") }
            SmallFloatingActionButton(onClick = onOpenRecords) { Icon(Icons.AutoMirrored.Filled.List, "记录") }
            SmallFloatingActionButton(onClick = onOpenTools) { Icon(Icons.Default.Build, "工具") }
            if (team.linked) {
                SmallFloatingActionButton(onClick = onOpenChat) { Icon(Icons.AutoMirrored.Filled.Chat, "组内消息") }
            }
            SmallFloatingActionButton(onClick = onOpenTeam) {
                androidx.compose.material3.BadgedBox(
                    badge = {
                        if (team.linked && team.online.size > 1) androidx.compose.material3.Badge { Text("${team.online.size}") }
                    },
                ) {
                    Icon(
                        Icons.Default.Groups,
                        "工作组",
                        tint = when {
                            !team.linked -> androidx.compose.material3.LocalContentColor.current
                            team.needsRejoin || team.error != null -> androidx.compose.material3.MaterialTheme.colorScheme.error
                            team.connection == org.geocrew.core.network.RealtimeState.Connected -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                            else -> androidx.compose.material3.MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
            SmallFloatingActionButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "设置") }
        }

        // Bottom: status line, selection card and the tool bar.
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusLine(
                onClick = { showGoTo = true },
                text = buildString {
                    val f = fix
                    if (f != null) {
                        append(CoordinateFormatter.format(f.position, format))
                        f.accuracy?.let { append("  ±%.0f m".format(it)) }
                        f.position.alt?.let { append("  海拔 %.0f m".format(it)) }
                    } else {
                        append(if (viewModel.locationEnabled()) "等待定位…" else "定位未开启")
                    }
                    if (mode is MapMode.Draw) append("\n十字中心 " + CoordinateFormatter.format(camera.center, format))
                },
            )
            val sel = selected
            if (sel != null && mode == MapMode.Browse) {
                SelectedFeatureCard(sel, format, onOpen = { onOpenFeature(sel.id) }, onDelete = viewModel::deleteSelected, onClose = viewModel::clearSelection)
            }
            when (val m = mode) {
                is MapMode.Draw -> DrawBar(
                    m,
                    onAddCenter = { viewModel.addVertex(camera.center) },
                    onUndo = viewModel::undoVertex,
                    onFinish = viewModel::finishDraw,
                    onCancel = viewModel::cancelDraw,
                )
                MapMode.Browse -> ToolBar(
                    recording = recording,
                    hasFix = fix != null,
                    onPointHere = viewModel::addPointAtFix,
                    onPointCenter = { viewModel.addPoint(camera.center) },
                    onTrack = { if (recording) viewModel.tracking.stop(context) else viewModel.tracking.start(context) },
                    onLine = { viewModel.startDraw(FeatureKind.LINE) },
                    onPolygon = { viewModel.startDraw(FeatureKind.POLYGON) },
                )
            }
        }

        if (showGoTo) {
            GoToDialog(
                format = format,
                onGo = { p, add ->
                    showGoTo = false
                    goneTo = if (add) null else p
                    goneLabel = GONE_TO_LABEL
                    camera.animateTo(p, maxOf(camera.zoom, 15.0))
                    if (add) viewModel.addPoint(p)
                },
                onDismiss = { showGoTo = false },
            )
        }

        if (showLayers) {
            LayerSheet(
                base = bm,
                onChoose = { viewModel.chooseSource(it); showLayers = false },
                onManage = { showLayers = false; onOpenSettings() },
                onDismiss = { showLayers = false },
            )
        }
    }
}

/** The open project's name; tapping it goes back to the project list. */
@Composable
private fun ProjectChip(name: String, shared: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(MaterialTheme.shapes.extraLarge).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (shared) Icons.Default.Groups else Icons.Default.Folder, null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun Crosshair(modifier: Modifier) {
    Box(modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
    }
}

/** Coordinates of the fix; tapping it opens "前往坐标". */
@Composable
private fun StatusLine(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 2.dp,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
internal fun Notice(text: String, action: String?, onAction: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun KeyPromptCard(onEnterKey: () -> Unit, onSkip: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("使用天地图需要你自己的 Key", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "同勘不内置任何 Key。在天地图开放平台免费注册后申请一个「服务端」类型的 Key，填到设置里即可。" +
                    "也可以先用其他图源，或在设置里添加自己的图源。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSkip) { Text("先用其他图源") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onEnterKey) { Text("填写 Key") }
            }
        }
    }
}

@Composable
private fun TileProblemCard(isTianditu: Boolean, host: String, code: Int, onSwitch: () -> Unit, onCheckKey: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isTianditu) "天地图 Key 无效或今日调用已达上限" else "图源拒绝了请求",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
            }
            Text(
                if (isTianditu) "可以检查 Key 是否填对、类型是否为「服务端」，或先切换到其他图源。（HTTP $code）"
                else "$host 返回 HTTP $code。可以换一个图源试试。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (isTianditu) TextButton(onClick = onCheckKey) { Text("检查 Key") }
                TextButton(onClick = onSwitch) { Text("换图源") }
            }
        }
    }
}

private const val GONE_TO_LABEL = "◎ 目标坐标"
