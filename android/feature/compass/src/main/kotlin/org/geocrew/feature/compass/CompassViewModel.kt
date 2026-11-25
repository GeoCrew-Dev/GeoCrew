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
import org.geocrew.core.model.nextName
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Plunge
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
    /** Line along the phone's long edge, averaged like [plane]; null on old sensor stacks. */
    val line: Plunge? = null,
    val lineSpread: Double = 0.0,
)

/** What the compass measures: a plane (bedding, joint, fault) or a line (lineation, striation, hinge). */
enum class CompassMode { PLANE, LINE }

/** Kinds of linear structure offered when saving a line (stored as [Props.LINEATION_TYPE]). */
val LINEATION_TYPES = listOf("线理", "擦痕", "褶皱枢纽", "交面线", "拉伸线理")

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

    private val _mode = MutableStateFlow(CompassMode.PLANE)
    val mode: StateFlow<CompassMode> = _mode.asStateFlow()

    /** Frozen line measurement waiting to be saved, in [CompassMode.LINE]. */
    private val _heldLine = MutableStateFlow<Plunge?>(null)
    val heldLine: StateFlow<Plunge?> = _heldLine.asStateFlow()

    private val _lineType = MutableStateFlow(LINEATION_TYPES.first())
    val lineType: StateFlow<String> = _lineType.asStateFlow()

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    /** Declination in use and where it came from, for display. */
    private val _declination = MutableStateFlow(0.0 to "未知（无定位）")
    val declination: StateFlow<Pair<Double, String>> = _declination.asStateFlow()

    private val _saved = MutableStateFlow<String?>(null)
    /** Id of the feature the last measurement was saved to. */
    val saved: StateFlow<String?> = _saved.asStateFlow()

    private val window = ArrayDeque<Attitude>()
    private val lineWindow = ArrayDeque<Plunge>()
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
                    val axis = o.axisTrue(decl)
                    if (axis != null) {
                        lineWindow.addLast(axis)
                        while (lineWindow.size > WINDOW) lineWindow.removeFirst()
                    }
                    val line = Plunge.mean(lineWindow.toList())
                    _reading.value = CompassReading(
                        o.azimuthTrue(decl), mean, Attitude.spread(window.toList(), mean), o.isUnreliable,
                        line, line?.let { Plunge.spread(lineWindow.toList(), it) } ?: 0.0,
                    )
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

    fun setMode(m: CompassMode) {
        _mode.value = m
        release()
    }

    fun setLineType(t: String) { _lineType.value = t }

    fun hold() {
        when (_mode.value) {
            CompassMode.PLANE -> _held.value = _reading.value?.plane
            CompassMode.LINE -> _heldLine.value = _reading.value?.line
        }
    }

    fun release() {
        _held.value = null
        _heldLine.value = null
    }

    /** Writes the held attitude to the target feature, or creates a geological point at the current fix. */
    fun save() = viewModelScope.launch {
        val a = _held.value
        val l = _heldLine.value
        if (a == null && l == null) return@launch
        val type = _lineType.value
        val write: org.geocrew.core.model.PropertiesBuilder.() -> Unit = {
            a?.writeTo(this)
            if (l != null) {
                l.writeTo(this)
                this[Props.LINEATION_TYPE] = type
            }
        }
        val target = targetId
        if (target != null) {
            features.edit(target) { write() }
            _saved.value = target
            return@launch
        }
        val f = _fix.value
        val p = projects.ensureCurrent()
        val name = p.config.nextName(FeatureKind.GEO_POINT, features.all(p.id).map { it.title })
        val created = features.create(p.id, FeatureKind.GEO_POINT, f?.let { Geometry.Point(it.position) }) {
            this[Props.TITLE] = name
            f?.accuracy?.let { this[Props.ACCURACY_M] = Math.round(it * 10) / 10.0 }
            write()
        }
        _saved.value = created.id
    }

    fun savedHandled() {
        _saved.value = null
        release()
    }

    override fun onCleared() = stop()

    private companion object {
        const val WINDOW = 15
    }
}
