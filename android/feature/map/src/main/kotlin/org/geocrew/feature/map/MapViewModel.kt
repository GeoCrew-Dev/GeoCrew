package org.geocrew.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
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
import org.geocrew.core.map.MapOverlays
import org.geocrew.core.map.MyLocation
import org.geocrew.core.map.StyleBuilder
import org.geocrew.core.map.TileNetwork
import org.geocrew.core.map.TileProblem
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.MapViewport
import org.geocrew.core.model.Project
import org.geocrew.core.model.Props
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

    /** Drawing a line or polygon vertex by vertex. */
    data class Draw(val kind: FeatureKind, val vertices: List<LngLat> = emptyList()) : MapMode {
        val closed get() = kind == FeatureKind.POLYGON
        val canFinish get() = vertices.size >= if (closed) 3 else 2
    }
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
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val team: StateFlow<TeamStatus> = teamSession.status

    /** Teammates on the map; a position older than [STALE_AFTER] is drawn faded. */
    private val teamMarkers = combine(teamSession.positions, ticker()) { positions, now ->
        positions.values.mapNotNull { p ->
            // Skip anything out of range rather than trusting relayed data.
            val at = runCatching { LngLat(p.lon, p.lat, p.alt) }.getOrNull() ?: return@mapNotNull null
            TeamMarker(
                memberId = p.memberId,
                nickname = p.nickname,
                position = at,
                accuracy = p.accuracy?.toFloat(),
                stale = p.time?.let { Duration.between(it, now) > STALE_AFTER } ?: true,
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val featureList: StateFlow<List<GeoFeature>> = projects.current
        .map { it.id }
        .distinctUntilChanged()
        .flatMapLatest { features.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private val _mode = MutableStateFlow<MapMode>(MapMode.Browse)
    val mode: StateFlow<MapMode> = _mode.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selected.asStateFlow()

    val selected: StateFlow<GeoFeature?> = combine(featureList, _selected) { list, id -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val overlays: StateFlow<MapOverlays> = combine(featureList, _selected, _fix, _mode, teamMarkers) { list, sel, fix, mode, mates ->
        MapOverlays(
            features = list,
            selectedId = sel,
            me = fix?.let { MyLocation(it.position, it.accuracy, it.bearing) },
            team = mates,
            sketch = (mode as? MapMode.Draw)?.vertices.orEmpty(),
            sketchClosed = (mode as? MapMode.Draw)?.closed == true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MapOverlays())

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

    /** Remembers where the map was, so the project opens there next time. */
    fun saveView(center: LngLat, zoom: Double, bearing: Double) {
        val id = project.value?.id ?: return
        appScope.launch { projects.updateConfig(id) { it.copy(view = MapViewport(center.lng, center.lat, zoom, bearing)) } }
    }

    fun keyPromptSeen() = viewModelScope.launch { settingsRepo.setKeyPromptDone() }

    fun dismissTileProblem() = TileNetwork.clear()

    fun onMapClick(position: LngLat, featureId: String?) {
        when (val m = _mode.value) {
            is MapMode.Draw -> _mode.value = m.copy(vertices = m.vertices + position)
            MapMode.Browse -> _selected.value = featureId
        }
    }

    fun clearSelection() {
        _selected.value = null
    }

    fun select(id: String) {
        _selected.value = id
    }

    /** Adds a point at [position], e.g. the crosshair or a long press. */
    fun addPoint(position: LngLat, kind: FeatureKind = FeatureKind.POINT) = viewModelScope.launch {
        val p = project.value ?: projects.ensureCurrent()
        val n = featureList.value.count { it.kind == kind } + 1
        val f = features.create(p.id, kind, Geometry.Point(position)) {
            this[Props.TITLE] = (if (kind == FeatureKind.GEO_POINT) "D" else "P") + n.toString().padStart(3, '0')
        }
        _selected.value = f.id
    }

    /** Adds a point at the current GPS fix, keeping its altitude and accuracy. */
    fun addPointAtFix() {
        val f = _fix.value ?: return
        addPoint(f.position)
    }

    fun startDraw(kind: FeatureKind) {
        _selected.value = null
        _mode.value = MapMode.Draw(kind)
    }

    fun addVertex(p: LngLat) = _mode.update { if (it is MapMode.Draw) it.copy(vertices = it.vertices + p) else it }

    fun undoVertex() = _mode.update { if (it is MapMode.Draw) it.copy(vertices = it.vertices.dropLast(1)) else it }

    fun cancelDraw() {
        _mode.value = MapMode.Browse
    }

    fun finishDraw() {
        val m = _mode.value as? MapMode.Draw ?: return
        if (!m.canFinish) return
        _mode.value = MapMode.Browse
        viewModelScope.launch {
            val p = project.value ?: projects.ensureCurrent()
            val geometry = if (m.closed) Geometry.Polygon.of(m.vertices) else Geometry.LineString(m.vertices)
            val n = featureList.value.count { it.kind == m.kind } + 1
            val f = features.create(p.id, m.kind, geometry) {
                this[Props.TITLE] = (if (m.closed) "面" else "线") + n
            }
            _selected.value = f.id
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
