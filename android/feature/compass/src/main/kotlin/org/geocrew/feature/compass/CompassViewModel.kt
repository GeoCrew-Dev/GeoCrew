package org.geocrew.feature.compass

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.sensors.Fix
import org.geocrew.core.sensors.LocationSource
import org.geocrew.core.sensors.OrientationSource
import javax.inject.Inject

/** Route argument: when present, the measurement is written to this feature. */
const val TARGET_FEATURE_ARG = "target"

/** Live reading of the compass, already corrected to true north. */
data class CompassReading(
    val azimuth: Double,
    val plane: Attitude,
    /** Max deviation (°) of recent readings from their mean; small = phone held still. */
    val spread: Double,
    val unreliable: Boolean,
)

@HiltViewModel
class CompassViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val orientation: OrientationSource,
    private val location: LocationSource,
    private val settings: SettingsRepository,
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
) : ViewModel() {
    val targetId: String? = savedState[TARGET_FEATURE_ARG]

    val available get() = orientation.available

    private val _reading = MutableStateFlow<CompassReading?>(null)
    val reading: StateFlow<CompassReading?> = _reading.asStateFlow()

    /** Frozen measurement waiting to be saved. */
    private val _held = MutableStateFlow<Attitude?>(null)
    val held: StateFlow<Attitude?> = _held.asStateFlow()

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    /** Declination in use and where it came from, for display. */
    private val _declination = MutableStateFlow(0.0 to "未知（无定位）")
    val declination: StateFlow<Pair<Double, String>> = _declination.asStateFlow()

    private val _saved = MutableStateFlow<String?>(null)
    /** Id of the feature the last measurement was saved to. */
    val saved: StateFlow<String?> = _saved.asStateFlow()

    private val window = ArrayDeque<Attitude>()
    private var jobs = emptyList<Job>()

    fun start() {
        if (jobs.any { it.isActive }) return
        jobs = listOf(
            viewModelScope.launch {
                orientation.orientation().collect { o ->
                    val decl = _declination.value.first
                    val plane = o.planeTrue(decl)
                    window.addLast(plane)
                    while (window.size > WINDOW) window.removeFirst()
                    val mean = Attitude.mean(window.toList()) ?: plane
                    _reading.value = CompassReading(o.azimuthTrue(decl), mean, Attitude.spread(window.toList(), mean), o.isUnreliable)
                }
            },
            viewModelScope.launch {
                if (!location.hasPermission()) return@launch
                location.fixes(intervalMillis = 2000).collect { f ->
                    _fix.value = f
                    updateDeclination(f)
                }
            },
            viewModelScope.launch {
                val manual = settings.settings.first().declination
                if (manual != null) _declination.value = manual.toDouble() to "手动设置"
            },
        )
    }

    private suspend fun updateDeclination(f: Fix) {
        val manual = settings.settings.first().declination
        _declination.value = if (manual != null) {
            manual.toDouble() to "手动设置"
        } else {
            orientation.declinationAt(f.position) to "按当前位置自动计算"
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    fun hold() {
        _held.value = _reading.value?.plane
    }

    fun release() {
        _held.value = null
    }

    /** Writes the held attitude to the target feature, or creates a geological point at the current fix. */
    fun save() = viewModelScope.launch {
        val a = _held.value ?: return@launch
        val target = targetId
        if (target != null) {
            features.edit(target) { a.writeTo(this) }
            _saved.value = target
            return@launch
        }
        val f = _fix.value
        val p = projects.ensureCurrent()
        val n = features.all(p.id).count { it.kind == FeatureKind.GEO_POINT } + 1
        val created = features.create(p.id, FeatureKind.GEO_POINT, f?.let { Geometry.Point(it.position) }) {
            this[Props.TITLE] = "D" + n.toString().padStart(3, '0')
            f?.accuracy?.let { this[Props.ACCURACY_M] = Math.round(it * 10) / 10.0 }
            a.writeTo(this)
        }
        _saved.value = created.id
    }

    fun savedHandled() {
        _saved.value = null
        _held.value = null
    }

    override fun onCleared() = stop()

    private companion object {
        const val WINDOW = 15
    }
}
