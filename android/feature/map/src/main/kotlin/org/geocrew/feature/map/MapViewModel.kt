package org.geocrew.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.geocrew.core.data.di.ApplicationScope
import org.geocrew.core.data.repo.AppSettings
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.geocrew.core.map.MapImage
import org.geocrew.core.map.MapOverlays
import org.geocrew.core.model.Bounds
import org.geocrew.core.model.ImageOverlay
import org.geocrew.core.model.OverlayAlign
import org.geocrew.core.model.WorldFile
import org.geocrew.core.map.MyLocation
import org.geocrew.core.map.StyleBuilder
import org.geocrew.core.map.TileNetwork
import org.geocrew.core.map.TileProblem
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.lengthMeters
import org.geocrew.core.model.Legend
import org.geocrew.core.model.MapDisplay
import org.geocrew.core.model.MapViewport
import org.geocrew.core.model.PositionAverage
import org.geocrew.core.model.Project
import org.geocrew.core.model.Props
import org.geocrew.core.model.VertexEdit
import org.geocrew.core.model.tiles.BuiltInSources
import org.geocrew.core.model.tiles.TileKeys
import org.geocrew.core.model.tiles.TileSource
import org.geocrew.core.sensors.Fix
import org.geocrew.core.sensors.LocationSource
import org.geocrew.core.sensors.TrackingController
import org.geocrew.core.sensors.TrackingState
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.data.team.TeamStatus
import org.geocrew.core.map.TeamMarker
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** What a tap on the map does right now. */
sealed interface MapMode {
    data object Browse : MapMode

    /**
     * Drawing a line or polygon vertex by vertex. With [measureOnly] the sketch
     * only shows its length or area; finishing it is optional and saves a feature.
     */
    data class Draw(
        val kind: FeatureKind,
        val vertices: List<LngLat> = emptyList(),
        val measureOnly: Boolean = false,
        /** Reshaping this existing record instead of drawing a new one. */
        val editing: String? = null,
        /** Moving a point record: the one vertex is its new position. */
        val single: Boolean = false,
        /** Vertex chosen to be moved by the next tap or by the crosshair. */
        val picked: Int? = null,
        /** Earlier vertex lists, newest last, for undo. */
        val undo: List<List<LngLat>> = emptyList(),
        /** Polygon kept closed while reshaping, whatever its record kind. */
        val ring: Boolean = kind == FeatureKind.POLYGON,
    ) : MapMode {
        val closed get() = ring
        val canFinish get() = if (single) vertices.size == 1 else vertices.size >= VertexEdit.minimum(closed)
        val canRemovePicked get() = picked != null && !single && vertices.size > VertexEdit.minimum(closed)

        /** The next state with [next] vertices, remembering this one for undo. */
        fun change(next: List<LngLat>, picked: Int? = null) = copy(vertices = next, picked = picked, undo = (undo + listOf(vertices)).takeLast(50))
    }
}

/** An image overlay as the layer list shows it. */
data class OverlayItem(val overlay: ImageOverlay, val missing: Boolean, val team: OverlayTeam = OverlayTeam.NONE) {
    /** Shared by a leader and seen by a member: only showing it and its opacity are up to this phone. */
    val readOnly: Boolean get() = overlay.shared && team == OverlayTeam.MEMBER
}

/** Whether the open project is in a workspace, and as what. */
enum class OverlayTeam { NONE, LEADER, MEMBER }

/**
 * Lining an image overlay up with the map: tap a spot on the picture, then
 * where it really is, as often as needed. [original] are the corners before
 * aligning; [pairs] are kept in that frame so each new one refines the fit.
 */
data class AlignMode(
    val overlayId: String,
    val name: String,
    val original: List<LngLat>,
    val pairs: List<OverlayAlign.Pair> = emptyList(),
    /** The picture spot of the next pair (in the original frame), and where it was tapped. */
    val pending: LngLat? = null,
    val pendingShown: LngLat? = null,
) : MapMode {
    /** Corners with the pairs so far applied; null while they cannot define a fit. */
    val preview: List<LngLat>? get() = OverlayAlign.corners(original, pairs)
    /** Largest misfit of a control point after the fit, in metres, once there are three or more. */
    val worstResidual: Double? get() = if (pairs.size < 3) null else OverlayAlign.residuals(pairs).maxOrNull()
}

/** Base map state for the screen. */
data class BaseMapUi(
    val source: TileSource,
    val keys: TileKeys,
    val chosenId: String,
    val sources: List<TileSource>,
    /** Sources that cannot be drawn yet because their key is missing. */
    val locked: Set<String>,
    /** The chosen source needs a key the user has not entered, so [source] is a fallback. */
    val usingFallback: Boolean,
    val showKeyPrompt: Boolean,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    private val location: LocationSource,
    val tracking: TrackingController,
    private val teamSession: TeamSession,
    private val teamComments: org.geocrew.core.data.team.TeamComments,
    @ApplicationScope private val appScope: CoroutineScope,
    private val downloads: org.geocrew.core.data.repo.BasemapDownloads,
    private val overlayImages: org.geocrew.core.data.repo.OverlayImages,
    private val rollCalls: org.geocrew.core.sensors.RollCallActions,
    private val outingActions: org.geocrew.core.sensors.OutingActions,
    private val terrain: org.geocrew.core.data.repo.TerrainTiles,
    private val teamForms: org.geocrew.core.data.team.TeamForms,
    private val teamRepo: org.geocrew.core.data.team.TeamRepository,
) : ViewModel() {

    /** Downloading an area of the base map for offline use; survives leaving the screen. */
    val download: StateFlow<org.geocrew.core.data.repo.BasemapDownload?> = downloads.current

    /** Starts downloading [plan] of the base map on screen; returns a Chinese reason when it can't. */
    fun startDownload(plan: org.geocrew.core.model.tiles.TilePlan, name: String): String? {
        val b = baseMap.value ?: return "地图还没准备好"
        return downloads.start(b.source, b.source.keyId?.let { b.keys[it] }, plan, name)
    }

    /** Downloads that stopped part way and can go on. */
    val pausedDownloads: StateFlow<List<org.geocrew.core.data.repo.PausedDownload>> = downloads.paused

    fun cancelDownload() = downloads.cancel()
    fun pauseDownload() = downloads.pause()
    fun dismissDownload() = downloads.dismiss()
    fun discardDownload(id: String) = downloads.discard(id)

    fun resumeDownload(id: String) {
        viewModelScope.launch { downloads.resume(id)?.let { _notice.value = it } }
    }


    val team: StateFlow<TeamStatus> = teamSession.status

    /** Teammates' messages not yet seen in the team chat, for the button's badge. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val unreadMessages: StateFlow<Int> = projects.currentId
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(0) else teamComments.unread(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Teammates on the map; a position older than [STALE_AFTER] is drawn faded. */
    private val teamMarkers = combine(teamSession.positions, teamSession.trails, ticker()) { positions, trails, now ->
        positions.values.mapNotNull { p ->
            // Skip anything out of range rather than trusting relayed data.
            val at = runCatching { LngLat(p.lon, p.lat, p.alt) }.getOrNull() ?: return@mapNotNull null
            TeamMarker(
                memberId = p.memberId,
                nickname = p.nickname,
                position = at,
                accuracy = p.accuracy?.toFloat(),
                stale = p.time?.let { Duration.between(it, now) > STALE_AFTER } ?: true,
                trail = trails[p.memberId].orEmpty().map { it.position },
            )
        }
    }

    val settings: StateFlow<AppSettings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The base map is a project setting; projects without one use the app-wide default. */
    val baseMap: StateFlow<BaseMapUi?> = combine(
        settingsRepo.settings,
        projects.current.map { it.config.baseSourceId }.distinctUntilChanged(),
    ) { s, own ->
        val keys = settingsRepo.keysOf(s)
        val catalog = settingsRepo.catalogOf(s)
        val chosen = own ?: s.baseSourceId
        val effective = catalog.effective(chosen, keys)
        BaseMapUi(
            source = effective,
            keys = keys,
            chosenId = chosen,
            sources = catalog.sources,
            locked = catalog.sources.filterNot(keys::has).map { it.id }.toSet(),
            usingFallback = effective.id != chosen,
            showKeyPrompt = !s.keyPromptDone && settingsRepo.needsTiandituKey(s),
        )
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A tile refusal from the source on screen, if any. */
    val tileProblem: StateFlow<TileProblem?> = combine(TileNetwork.problem, baseMap) { p, b ->
        if (p == null || b == null) null else p.takeIf { it.host in StyleBuilder.hosts(b.source) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val project: StateFlow<Project?> = projects.current.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val dismissedAlerts = MutableStateFlow<Set<String>>(emptySet())

    /** A teammate's newest urgent message from the last hours, until dismissed here. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val alert: StateFlow<org.geocrew.core.data.team.CommentItem?> = project
        .map { p -> p?.team?.let { p.id to it.memberId } }
        .distinctUntilChanged()
        .flatMapLatest { k ->
            if (k == null) kotlinx.coroutines.flow.flowOf(emptyList()) else teamComments.alerts(k.first, k.second)
        }
        .combine(dismissedAlerts) { list, gone -> list.firstOrNull { it.id !in gone } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissAlert(id: String) = dismissedAlerts.update { it + id }

    private val dismissedCalls = MutableStateFlow<Set<String>>(emptySet())

    /** A teammate's roll call this user has not answered yet, until put off here. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val rollCall: StateFlow<org.geocrew.core.data.team.CommentItem?> = project
        .map { p -> p?.team?.let { p.id to it.memberId } }
        .distinctUntilChanged()
        .flatMapLatest { k ->
            if (k == null) kotlinx.coroutines.flow.flowOf(null) else teamComments.rollCall(k.first, k.second)
        }
        .combine(dismissedCalls) { rc, gone ->
            rc?.takeIf { !it.call.mine && it.call.id !in gone && it.answers.none { a -> a.mine } }?.call
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Check-outs not closed yet (protocol v0 §8.3); the map shows one's own and overdue ones. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val outings: StateFlow<List<org.geocrew.core.ui.OutingRow>> = project
        .map { p -> p?.team?.let { Triple(p.id, it.memberId, it.role == org.geocrew.core.data.team.TeamRepository.ROLE_LEADER) } }
        .distinctUntilChanged()
        .flatMapLatest { k ->
            if (k == null) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                teamComments.outings(k.first, k.second).map { list ->
                    list.map {
                        org.geocrew.core.ui.OutingRow(it.id, it.nickname, it.body, it.backBy ?: it.createdAt, it.mine, it.mine || k.third, it.at)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Checks an outing back in from the map. */
    fun checkIn(id: String) {
        val p = project.value ?: return
        val me = p.team?.memberId ?: return
        if (_answering.value) return
        _answering.value = true
        val center = p.config.view?.let { runCatching { LngLat(it.lng, it.lat) }.getOrNull() }
        appScope.launch {
            try {
                val outing = teamComments.openOutings(p.id, me).firstOrNull { it.id == id } ?: return@launch
                outingActions.checkIn(p.id, outing, center)?.let { _notice.value = it }
            } catch (e: java.io.IOException) {
                _notice.value = if (e is org.geocrew.core.network.ApiException && e.isForbidden) "只有本人和组长能报返回" else "需要联网：${e.message}"
            } finally {
                _answering.value = false
            }
        }
    }

    private val _answering = MutableStateFlow(false)
    val answering: StateFlow<Boolean> = _answering.asStateFlow()

    fun dismissRollCall(id: String) = dismissedCalls.update { it + id }

    /** Answers the roll call on screen with this phone's position (the map centre without a fix). */
    fun answerRollCall(ok: Boolean) {
        val call = rollCall.value ?: return
        val pid = project.value?.id ?: return
        if (_answering.value) return
        _answering.value = true
        val center = project.value?.config?.view?.let { runCatching { LngLat(it.lng, it.lat) }.getOrNull() }
        appScope.launch {
            try {
                rollCalls.answer(pid, call.id, ok, center)?.let { _notice.value = it }
            } finally {
                _answering.value = false
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val featureList: StateFlow<List<GeoFeature>> = projects.current
        .map { it.id }
        .distinctUntilChanged()
        .flatMapLatest { features.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    /** Ticks twice a minute so countdowns and the next meeting move on without new data. */
    private val clock = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(java.time.Instant.now())
            kotlinx.coroutines.delay(30_000)
        }
    }

    private val dismissedMeetings = MutableStateFlow<Set<String>>(emptySet())

    /** The group's next meeting point, until put away here (a new time brings it back). */
    val meeting: StateFlow<Pair<org.geocrew.core.model.Rendezvous.Next, java.time.Instant>?> =
        combine(featureList, clock, dismissedMeetings) { list, now, gone ->
            org.geocrew.core.model.Rendezvous.next(list, now)?.takeIf { meetingKey(it) !in gone }?.let { it to now }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissMeeting(next: org.geocrew.core.model.Rendezvous.Next) = dismissedMeetings.update { it + meetingKey(next) }

    private fun meetingKey(n: org.geocrew.core.model.Rendezvous.Next) = n.feature.id + "@" + n.at

    private val _mode = MutableStateFlow<MapMode>(MapMode.Browse)
    val mode: StateFlow<MapMode> = _mode.asStateFlow()

    /**
     * Walking time there and back along the distance being measured, from
     * the public DEM (cached, so it works again offline); null unless a
     * line is being measured.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val measureWalk: StateFlow<String?> = _mode
        .map { m -> (m as? MapMode.Draw)?.takeIf { it.measureOnly && !it.closed && it.vertices.size >= 2 }?.vertices }
        .distinctUntilChanged()
        .mapLatest { v ->
            if (v == null) return@mapLatest null
            kotlinx.coroutines.delay(400) // wait for the user to stop adding vertices
            walkText(v)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun walkText(v: List<LngLat>): String = withContext(Dispatchers.IO) {
        val length = Geometry.LineString(v).lengthMeters()
        // Some 800 samples at most: a long line needs no 30 m detail for a time estimate.
        val pts = org.geocrew.core.model.Hiking.densify(v, maxOf(org.geocrew.core.model.Hiking.STEP_M, length / 800))
        val heights = runCatching { terrain.elevation().at(pts) }.getOrElse { List(pts.size) { null } }
        val go = org.geocrew.core.model.Hiking.estimate(pts, heights)
        if (heights.all { it == null }) return@withContext "步行${org.geocrew.core.model.Hiking.format(go.hours)}（取不到地形，按平路估算）"
        val back = org.geocrew.core.model.Hiking.estimate(pts.asReversed(), heights.asReversed())
        "步行：去${org.geocrew.core.model.Hiking.format(go.hours)}，回${org.geocrew.core.model.Hiking.format(back.hours)} · " +
            "爬升 ${go.ascent.roundToInt()} m，下降 ${go.descent.roundToInt()} m"
    }

    private val _selected = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selected.asStateFlow()

    val selected: StateFlow<GeoFeature?> = combine(featureList, _selected) { list, id -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** What this project's map draws; see [MapDisplay]. */
    val display: StateFlow<MapDisplay> = project.map { it?.config?.display ?: MapDisplay() }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, MapDisplay())

    /** How records are coloured by attribute; see [Legend]. */
    val legend: StateFlow<Legend> = project.map { it?.config?.legend ?: Legend() }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Legend())

    private data class Drawn(val list: List<GeoFeature>, val me: String?, val display: MapDisplay, val legend: Legend)

    private val featuresWithMe = combine(
        featureList,
        project.map { it?.team?.memberId }.distinctUntilChanged(),
        display,
        legend,
    ) { l, me, d, lg -> Drawn(l, me, d, lg) }

    val overlays: StateFlow<MapOverlays> = combine(featuresWithMe, _selected, _fix, _mode, teamMarkers) { (list, me, d, lg), sel, fix, mode, mates ->
        MapOverlays(
            legend = lg,
            memberId = me,
            features = d.filter(list, me, keep = sel).let { l ->
                val editing = (mode as? MapMode.Draw)?.editing
                if (editing == null) l else l.filterNot { it.id == editing }
            },
            selectedId = sel,
            me = fix?.let { MyLocation(it.position, it.accuracy, it.bearing) },
            team = if (d.team) mates else emptyList(),
            sketch = (mode as? MapMode.Draw)?.vertices.orEmpty(),
            sketchClosed = (mode as? MapMode.Draw)?.closed == true,
            sketchPicked = (mode as? MapMode.Draw)?.picked,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MapOverlays())

    /** The project's image overlays, each with whether its file is on this device. */
    val overlayItems: StateFlow<List<OverlayItem>> = project.map { p ->
        val team = when (p?.team?.role) {
            null -> OverlayTeam.NONE
            org.geocrew.core.data.team.TeamRepository.ROLE_LEADER -> OverlayTeam.LEADER
            else -> OverlayTeam.MEMBER
        }
        p?.config?.overlays.orEmpty() to team
    }.distinctUntilChanged()
        .map { (list, team) -> withContext(Dispatchers.IO) { list.map { OverlayItem(it, overlayImages.file(it.file)?.isFile != true, team) } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val bitmaps = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    /** Decoded pictures for the visible overlays, kept while they stay in use. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decoded = overlayItems.mapLatest { list ->
        withContext(Dispatchers.IO) {
            val shown = list.filter { it.overlay.visible && !it.missing }.map { it.overlay }
            bitmaps.keys.retainAll(shown.map { it.file }.toSet())
            shown.mapNotNull { o ->
                val b = bitmaps[o.file] ?: overlayImages.load(o.file)?.also { bitmaps[o.file] = it } ?: return@mapNotNull null
                MapImage(o.id, b, o.quad, o.opacity)
            }
        }
    }

    /** Pictures to draw; the one being aligned shows its fit so far. */
    val images: StateFlow<List<MapImage>> = combine(decoded, _mode) { list, mode ->
        if (mode !is AlignMode) list
        else list.map { if (it.id == mode.overlayId) it.copy(quad = mode.preview ?: it.quad) else it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Starts lining up an overlay by control points; the layer sheet's "对齐". */
    fun startAlign(overlayId: String) {
        val o = project.value?.config?.overlays?.firstOrNull { it.id == overlayId } ?: return
        _selected.value = null
        _mode.value = AlignMode(o.id, o.name, o.quad)
        if (!o.visible) setOverlay(o.id) { it.copy(visible = true) }
    }

    private fun alignTap(m: AlignMode, p: LngLat): AlignMode {
        val pending = m.pending ?: run {
            // The picture is drawn at its fit so far: take the spot back to the original frame.
            val shown = m.preview ?: m.original
            val back = if (m.pairs.isEmpty()) p
            else OverlayAlign.corners(listOf(p), shown.zip(m.original).map { (a, b) -> OverlayAlign.Pair(a, b) })?.single() ?: p
            return m.copy(pending = back, pendingShown = p)
        }
        return m.copy(pairs = m.pairs + OverlayAlign.Pair(pending, p), pending = null, pendingShown = null)
    }

    /** Takes back the half-made pair, or else the last pair. */
    fun undoAlign() = _mode.update {
        when {
            it !is AlignMode -> it
            it.pending != null -> it.copy(pending = null, pendingShown = null)
            else -> it.copy(pairs = it.pairs.dropLast(1))
        }
    }

    fun applyAlign() {
        val m = _mode.value as? AlignMode ?: return
        val fit = m.preview
        if (fit == null) {
            _notice.value = "这几处点几乎在一条直线上，定不了方向；撤销一对，换一处不在一线上的点"
            return
        }
        setOverlay(m.overlayId) { it.copy(corners = ImageOverlay.corners(fit), placement = ImageOverlay.Placement.VIEW, controlPoints = m.pairs.size) }
        _mode.value = MapMode.Browse
        _notice.value = "已按 ${m.pairs.size} 对控制点对齐「${m.name}」" + (m.worstResidual?.let { "，最大偏差约 ${Math.round(it)} 米" } ?: "")
    }

    fun cancelAlign() {
        if (_mode.value is AlignMode) _mode.value = MapMode.Browse
    }

    /**
     * Adds the picked image (and world file, if one was picked with it) as an
     * overlay. With a world file it goes where the file says and [onPlaced]
     * gets its extent; otherwise it is laid over [view].
     */
    fun addOverlay(uris: List<android.net.Uri>, view: Bounds?, onPlaced: (Bounds) -> Unit) = viewModelScope.launch {
        val id = project.value?.id ?: return@launch
        val imported = runCatching { overlayImages.import(uris) }.getOrElse { e ->
            _notice.value = (e as? IllegalArgumentException)?.message ?: "图片导入失败"
            return@launch
        }
        val world = imported.worldFile
        val crs = world?.let(WorldFile::guess)
        val placed = if (world != null && crs != null) WorldFile.corners(world, imported.sourceWidth, imported.sourceHeight, crs) else null
        val overlay = ImageOverlay(
            id = java.util.UUID.randomUUID().toString(),
            name = imported.name,
            file = imported.file,
            width = imported.width,
            height = imported.height,
            corners = ImageOverlay.corners(
                placed ?: view?.let { ImageOverlay.fit(it, imported.width, imported.height) } ?: run {
                    _notice.value = "地图还没准备好，稍后再添加"
                    return@launch
                },
            ),
            placement = if (placed != null) ImageOverlay.Placement.WORLD_FILE else ImageOverlay.Placement.VIEW,
        )
        projects.updateConfig(id) { it.copy(overlays = it.overlays + overlay) }
        _notice.value = when {
            placed != null -> "已按 world 文件放置「${overlay.name}」" + ((crs as? WorldFile.Crs.Plane)?.let { "（${it.width}° 带高斯坐标）" } ?: "")
            world != null -> "world 文件的坐标看不出是经纬度还是带号高斯坐标，已先铺在当前视野里"
            else -> "已把「${overlay.name}」铺在当前视野里，可在图层里调透明度"
        }
        placed?.let(Bounds::of)?.let(onPlaced)
    }

    /** Changes one overlay of the open project. */
    fun setOverlay(overlayId: String, change: (ImageOverlay) -> ImageOverlay) = editOverlays { list -> list.map { if (it.id == overlayId) change(it) else it } }

    /** Shares an overlay with the workspace, or stops sharing it (leaders). */
    fun toggleShared(overlayId: String) = setOverlay(overlayId) { it.copy(shared = !it.shared) }

    /**
     * Changes the open project's overlays; when a leader's change touches
     * what the workspace shares, it goes to every member (protocol v0 §2.2).
     */
    private fun editOverlays(edit: (List<ImageOverlay>) -> List<ImageOverlay>) = viewModelScope.launch {
        val p = project.value ?: return@launch
        var before: List<org.geocrew.core.model.SharedOverlay> = emptyList()
        var after: List<org.geocrew.core.model.SharedOverlay> = emptyList()
        projects.updateConfig(p.id) { c ->
            val next = edit(c.overlays)
            before = org.geocrew.core.model.SharedOverlay.published(c.overlays)
            after = org.geocrew.core.model.SharedOverlay.published(next)
            c.copy(overlays = next)
        }
        if (before == after || p.team?.role != org.geocrew.core.data.team.TeamRepository.ROLE_LEADER) return@launch
        val h = teamRepo.handle(p.id) ?: return@launch
        val msg = try {
            teamForms.publishOverlays(h) ?: if (after.size > before.size) "已共享给组员，图片会随同步传给他们" else null
        } catch (e: java.io.IOException) {
            "已保存在本机，联网后会同步给组员"
        }
        msg?.let { _notice.value = it }
    }

    /** Lays an overlay over [view] again, e.g. when its world file put it in the wrong place. */
    fun fitOverlay(overlayId: String, view: Bounds?) {
        if (view == null) return
        setOverlay(overlayId) {
            it.copy(corners = ImageOverlay.corners(ImageOverlay.fit(view, it.width, it.height)), placement = ImageOverlay.Placement.VIEW, controlPoints = 0)
        }
    }

    /** Moves an overlay one step up (drawn later) or down in the list. */
    fun moveOverlay(overlayId: String, up: Boolean) = editOverlays { list ->
        val i = list.indexOfFirst { it.id == overlayId }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun removeOverlay(overlayId: String) = editOverlays { list -> list.filterNot { it.id == overlayId } }

    private var locationJob: Job? = null

    init {
        viewModelScope.launch { projects.ensureCurrent() }
        // A new key or source deserves a fresh chance: drop the old refusal.
        viewModelScope.launch {
            baseMap.map { it?.source?.id to it?.keys }.distinctUntilChanged().collectLatest { TileNetwork.clear() }
        }
    }

    /** Called when location permission is known to be granted. */
    fun startLocation() {
        if (locationJob?.isActive == true || !location.hasPermission()) return
        locationJob = viewModelScope.launch {
            location.fixes(intervalMillis = 1000).collectLatest {
                _fix.value = it
                teamSession.reportPosition(it.position, it.accuracy, it.bearing, it.speed)
            }
        }
    }

    fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
    }

    fun locationEnabled() = location.isEnabled()

    /** Sets this project's base map; the choice also becomes the default for new projects. */
    fun chooseSource(id: String) = viewModelScope.launch {
        settingsRepo.setBaseSource(id)
        projects.ensureCurrent().let { p -> projects.updateConfig(p.id) { it.copy(baseSourceId = id) } }
    }

    /** "Use another map for now": remember the choice and switch to a keyless source. */
    fun skipKeyPrompt() = viewModelScope.launch {
        settingsRepo.setKeyPromptDone()
        if (settingsRepo.needsTiandituKey(settingsRepo.current())) chooseSource(BuiltInSources.OSM.id)
    }

    /** Changes what the map draws for the open project. */
    fun setDisplay(change: (MapDisplay) -> MapDisplay) = viewModelScope.launch {
        val id = project.value?.id ?: return@launch
        projects.updateConfig(id) { it.copy(display = change(it.display)) }
    }

    /** Remembers where the map was, so the project opens there next time. */
    fun saveView(center: LngLat, zoom: Double, bearing: Double) {
        val id = project.value?.id ?: return
        appScope.launch { projects.updateConfig(id) { it.copy(view = MapViewport(center.lng, center.lat, zoom, bearing)) } }
    }

    fun keyPromptSeen() = viewModelScope.launch { settingsRepo.setKeyPromptDone() }

    fun dismissTileProblem() = TileNetwork.clear()

    /**
     * A tap on the map. While drawing, [toleranceM] is how close (in metres on
     * the ground) a tap must be to a vertex to pick it up for moving.
     */
    fun onMapClick(position: LngLat, featureId: String?, toleranceM: Double = 0.0) {
        when (val m = _mode.value) {
            is MapMode.Draw -> _mode.value = tap(m, position, toleranceM)
            is AlignMode -> _mode.value = alignTap(m, position)
            MapMode.Browse -> _selected.value = featureId
        }
    }

    private fun tap(m: MapMode.Draw, p: LngLat, toleranceM: Double): MapMode.Draw {
        if (m.single) return m.change(listOf(p))
        val hit = VertexEdit.near(m.vertices, p, toleranceM)
        return when {
            m.picked != null && hit == m.picked -> m.copy(picked = null)
            m.picked != null -> m.change(m.vertices.toMutableList().also { it[m.picked] = p })
            hit != null -> m.copy(picked = hit)
            m.editing != null -> m.change(VertexEdit.insert(m.vertices, p, m.closed))
            else -> m.change(m.vertices + p)
        }
    }

    fun clearSelection() {
        _selected.value = null
    }

    fun select(id: String) {
        _selected.value = id
    }

    /** Adds a point at [position], e.g. the crosshair or a long press. */
    fun addPoint(position: LngLat, kind: FeatureKind = FeatureKind.POINT): Job = addPoint(position, kind) {}

    private fun addPoint(
        position: LngLat,
        kind: FeatureKind,
        extra: org.geocrew.core.model.PropertiesBuilder.() -> Unit,
    ): Job = viewModelScope.launch {
        val p = project.value ?: projects.ensureCurrent()
        val name = p.config.numbering.next(kind, features.all(p.id).map { it.title })
        val f = features.create(p.id, kind, Geometry.Point(position)) {
            this[Props.TITLE] = name
            extra()
        }
        _selected.value = f.id
    }

    /** Collecting fixes for an averaged point; null when not averaging. */
    data class Averaging(
        val seconds: Int,
        val elapsed: Int,
        val result: PositionAverage.Result?,
        val seen: Int,
    )

    private val _averaging = MutableStateFlow<Averaging?>(null)
    val averaging: StateFlow<Averaging?> = _averaging.asStateFlow()
    private var averageJob: Job? = null
    private val _notice = MutableStateFlow<String?>(null)
    /** A one-off message for the map, e.g. why an averaged point was not placed. */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    /**
     * Adds a point at the current GPS fix, keeping its altitude and accuracy;
     * with averaging on, collects fixes for the set time first.
     */
    fun addPointAtFix() {
        val seconds = settings.value?.pointAverageSeconds ?: 0
        if (seconds > 0) return startAveraging(seconds)
        val f = _fix.value ?: return
        addPoint(f.position, FeatureKind.POINT) {
            f.accuracy?.let { this[Props.ACCURACY_M] = Math.round(it * 10) / 10.0 }
        }
    }

    private fun startAveraging(seconds: Int) {
        averageJob?.cancel()
        val avg = PositionAverage()
        _averaging.value = Averaging(seconds, 0, null, 0)
        averageJob = viewModelScope.launch {
            val ticker = launch {
                for (e in 1..seconds) {
                    kotlinx.coroutines.delay(1_000)
                    _averaging.update { it?.copy(elapsed = e) }
                }
            }
            var last: java.time.Instant? = null
            kotlinx.coroutines.withTimeoutOrNull(seconds * 1_000L) {
                _fix.collect { f ->
                    if (f == null || f.time == last) return@collect
                    last = f.time
                    avg.add(f.position, f.accuracy?.toDouble())
                    _averaging.update { it?.copy(result = avg.result(), seen = avg.seen) }
                }
            }
            ticker.cancel()
            finishAveraging()
        }
    }

    /** Places the averaged point now, with what has been collected so far. */
    fun finishAveraging() {
        val a = _averaging.value ?: return
        _averaging.value = null
        val r = a.result
        if (r == null) {
            _notice.value = if (a.seen == 0) "没有收到定位，未打点" else "定位精度都不够好（差于 30 米），未打点"
        } else {
            addPoint(r.position, FeatureKind.POINT) {
                r.accuracy?.let { this[Props.ACCURACY_M] = Math.round(it * 10) / 10.0 }
                this[Props.GPS_SAMPLES] = r.used
                this[Props.GPS_SPREAD_M] = Math.round(r.spread * 10) / 10.0
            }
        }
        averageJob?.cancel()
        averageJob = null
    }

    fun cancelAveraging() {
        averageJob?.cancel()
        averageJob = null
        _averaging.value = null
    }

    /** Starts moving or reshaping the selected record on the map. */
    fun startEdit() {
        val f = selected.value ?: return
        when (val e = VertexEdit.of(f)) {
            is VertexEdit.Edit.No -> _notice.value = e.reason
            is VertexEdit.Edit.Move -> {
                _selected.value = null
                _mode.value = MapMode.Draw(f.kind, listOf(e.position), editing = f.id, single = true)
            }
            is VertexEdit.Edit.Reshape -> {
                _selected.value = null
                _mode.value = MapMode.Draw(f.kind, e.vertices, editing = f.id, ring = e.closed)
            }
        }
    }

    /** Removes the picked vertex of the shape being drawn. */
    fun removePicked() = _mode.update {
        if (it is MapMode.Draw && it.canRemovePicked) it.change(it.vertices.filterIndexed { i, _ -> i != it.picked }) else it
    }

    fun startDraw(kind: FeatureKind) {
        _selected.value = null
        _mode.value = MapMode.Draw(kind)
    }

    /** Starts measuring distance; the sketch is not saved unless the user asks. */
    fun startMeasure() {
        _selected.value = null
        _mode.value = MapMode.Draw(FeatureKind.LINE, measureOnly = true)
    }

    /** Switches a measurement between distance (line) and area (polygon), keeping the vertices. */
    fun toggleMeasureKind() = _mode.update {
        if (it is MapMode.Draw && it.measureOnly) {
            it.copy(kind = if (it.closed) FeatureKind.LINE else FeatureKind.POLYGON, ring = !it.closed)
        } else it
    }

    /** The crosshair button: moves the picked vertex (or the point) there, otherwise adds a vertex. */
    fun addVertex(p: LngLat) = _mode.update {
        if (it !is MapMode.Draw) it
        else if (it.single) it.change(listOf(p))
        else if (it.picked != null) it.change(it.vertices.toMutableList().also { v -> v[it.picked] = p })
        else if (it.editing != null) it.change(VertexEdit.insert(it.vertices, p, it.closed))
        else it.change(it.vertices + p)
    }

    fun undoVertex() = _mode.update {
        if (it is MapMode.Draw && it.undo.isNotEmpty()) it.copy(vertices = it.undo.last(), undo = it.undo.dropLast(1), picked = null) else it
    }

    fun cancelDraw() {
        _mode.value = MapMode.Browse
    }

    fun finishDraw() {
        val m = _mode.value as? MapMode.Draw ?: return
        if (!m.canFinish) return
        _mode.value = MapMode.Browse
        m.editing?.let { id -> return saveShape(id, m) }
        viewModelScope.launch {
            val p = project.value ?: projects.ensureCurrent()
            val geometry = if (m.closed) Geometry.Polygon.of(m.vertices) else Geometry.LineString(m.vertices)
            val name = p.config.numbering.next(m.kind, features.all(p.id).map { it.title })
            val f = features.create(p.id, m.kind, geometry) {
                this[Props.TITLE] = name
            }
            _selected.value = f.id
        }
    }

    private fun saveShape(id: String, m: MapMode.Draw) {
        viewModelScope.launch {
            val f = features.get(id) ?: return@launch
            val geometry = when {
                m.single -> {
                    val was = (f.geometry as? Geometry.Point)?.position
                    val to = m.vertices.single()
                    // A nudge keeps the measured height; a real move leaves it to be measured again.
                    val alt = was?.alt?.takeIf { Geodesy.distance(was, to) <= 30.0 }
                    Geometry.Point(LngLat(to.lng, to.lat, alt))
                }
                m.closed -> Geometry.Polygon.of(m.vertices)
                else -> Geometry.LineString(m.vertices)
            }
            if (geometry == f.geometry) return@launch
            features.edit(id, geometry) {
                if (m.single) {
                    // These described the original fix, not a position set by hand.
                    remove(Props.ACCURACY_M); remove(Props.GPS_SAMPLES); remove(Props.GPS_SPREAD_M)
                    if ((geometry as? Geometry.Point)?.position?.alt == null) remove(Props.ALT_SOURCE)
                }
            }
            _selected.value = id
            _notice.value = if (m.single) "已移动「${f.title ?: "记录"}」，原定位精度不再保留" else "已更新「${f.title ?: "记录"}」的形状，可在记录的修改历史里恢复"
        }
    }

    fun deleteSelected() {
        val id = _selected.value ?: return
        _selected.value = null
        viewModelScope.launch { features.delete(id) }
    }

    val isRecording: StateFlow<Boolean> = tracking.state.map { it is TrackingState.Recording }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun ticker() = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(Instant.now())
            kotlinx.coroutines.delay(15_000)
        }
    }

    private companion object {
        val STALE_AFTER: Duration = Duration.ofMinutes(2)
    }
}
