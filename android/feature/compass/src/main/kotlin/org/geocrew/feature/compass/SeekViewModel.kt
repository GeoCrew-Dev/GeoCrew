package org.geocrew.feature.compass

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.SeekGuide
import org.geocrew.core.model.TaskStatus
import org.geocrew.core.model.task
import org.geocrew.core.sensors.Fix
import org.geocrew.core.sensors.LocationSource
import org.geocrew.core.sensors.OrientationSource
import javax.inject.Inject

const val SEEK_FEATURE_ARG = "feature"

/** A point that can be sought; [mine] marks an open task assigned to this device's member. */
data class SeekTarget(val id: String?, val title: String, val position: LngLat, val mine: Boolean = false)

/**
 * Leads the way to a point record (an assigned sampling point, say) with
 * distance, bearing and an arrow that follows the phone's heading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeekViewModel @Inject constructor(
    savedState: SavedStateHandle,
    projects: ProjectRepository,
    private val features: FeatureRepository,
    private val location: LocationSource,
    private val orientation: OrientationSource,
    private val settings: SettingsRepository,
) : ViewModel() {
    /** Point records, open tasks of mine first. */
    val targets: StateFlow<List<SeekTarget>> = projects.current
        .flatMapLatest { p ->
            val me = p.team?.memberId
            features.observeProject(p.id).map { list ->
                list.mapNotNull { f ->
                    val pos = (f.geometry as? Geometry.Point)?.position ?: return@mapNotNull null
                    val t = f.task
                    SeekTarget(f.id, f.title?.takeIf { it.isNotBlank() } ?: "未命名", pos, mine = me != null && t?.assignee == me && t.status != TaskStatus.DONE)
                }.sortedWith(compareByDescending<SeekTarget> { it.mine }.thenBy { it.title })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _target = MutableStateFlow<SeekTarget?>(null)
    val target: StateFlow<SeekTarget?> = _target.asStateFlow()

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private val _heading = MutableStateFlow<Double?>(null)
    /** True heading of the phone's top edge. */
    val heading: StateFlow<Double?> = _heading.asStateFlow()

    val guide: StateFlow<SeekGuide?> = combine(_target, _fix, _heading) { t, f, h ->
        if (t == null || f == null) null else SeekGuide.of(f.position, t.position, h, f.accuracy?.toDouble())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hasLocationPermission: Boolean get() = location.hasPermission()
    val hasCompass: Boolean get() = orientation.available

    private var jobs = emptyList<Job>()
    @Volatile private var declination = 0.0

    init {
        val id: String? = savedState[SEEK_FEATURE_ARG]
        if (id != null) {
            viewModelScope.launch {
                val f = features.get(id) ?: return@launch
                val pos = (f.geometry as? Geometry.Point)?.position ?: return@launch
                _target.value = SeekTarget(f.id, f.title?.takeIf { it.isNotBlank() } ?: "未命名", pos)
            }
        }
    }

    fun choose(t: SeekTarget) { _target.value = t }

    fun start() {
        if (jobs.any { it.isActive }) return
        jobs = listOf(
            viewModelScope.launch {
                if (!location.hasPermission()) return@launch
                location.fixes(intervalMillis = 1000).collect { _fix.value = it }
            },
            viewModelScope.launch {
                // Declination: the manual setting, else the model value at the latest fix.
                combine(settings.settings, _fix) { s, f ->
                    s.declination?.toDouble() ?: f?.let { orientation.declinationAt(it.position) } ?: 0.0
                }.collect { declination = it }
            },
            viewModelScope.launch {
                if (!orientation.available) return@launch
                orientation.orientation().collect { o -> _heading.value = o.azimuthTrue(declination) }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }
}
