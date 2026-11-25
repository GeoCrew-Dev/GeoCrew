package org.geocrew.feature.compass

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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.model.nextName
import org.geocrew.core.model.BearingFix
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry

import org.geocrew.core.model.Props
import org.geocrew.core.sensors.Fix
import org.geocrew.core.sensors.LocationSource
import org.geocrew.core.sensors.OrientationSource
import javax.inject.Inject

/** Which way the bearings go: from known stations to the target, or from you to known landmarks. */
enum class FixMode(val label: String, val help: String) {
    FORWARD("前方交会", "在两三个已知点上分别量向目标的方位，求目标位置。适合过不去的露头、对岸的崖壁。"),
    RESECTION("后方交会", "站在要定的地方，量向两三个已知地物（山顶、塔、已定的点）的方位，求自己的位置。适合 GPS 信号差的沟谷。"),
}

/** One sight: a known point and the bearing read on it, as typed. */
data class Sight(
    val point: SeekTarget? = null,
    val bearing: String = "",
)

data class FixUi(
    val mode: FixMode = FixMode.FORWARD,
    val sights: List<Sight> = listOf(Sight(), Sight()),
    /** True when the typed bearings are magnetic and need the declination added. */
    val magnetic: Boolean = true,
    val declination: Double = 0.0,
    val result: BearingFix.Result? = null,
    /** Why there is no result, when the sights are filled in but don't give one. */
    val problem: String? = null,
)

/**
 * Fixes a point by compass bearings: forward intersection to a target seen
 * from known stations, or resection of where you stand from known landmarks.
 * Bearings can be typed or read off the phone's compass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BearingFixViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    private val location: LocationSource,
    private val orientation: OrientationSource,
    private val settings: SettingsRepository,
) : ViewModel() {
    /** Point records that can serve as stations or landmarks. */
    val points: StateFlow<List<SeekTarget>> = projects.current
        .flatMapLatest { p ->
            features.observeProject(p.id).map { list ->
                list.mapNotNull { f ->
                    val pos = (f.geometry as? Geometry.Point)?.position ?: return@mapNotNull null
                    SeekTarget(f.id, f.title?.takeIf { it.isNotBlank() } ?: "未命名", pos)
                }.sortedBy { it.title }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val coordinateFormat: StateFlow<CoordinateFormat> = settings.settings.map { it.coordinateFormat }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoordinateFormat.DECIMAL)

    private val _ui = MutableStateFlow(FixUi())
    val ui: StateFlow<FixUi> = _ui.asStateFlow()

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private val _heading = MutableStateFlow<Double?>(null)
    /** Magnetic bearing the phone is sighting along now: the back camera's look, or the top edge when flat. */
    val heading: StateFlow<Double?> = _heading.asStateFlow()

    private val _saved = MutableStateFlow<String?>(null)
    val saved: StateFlow<String?> = _saved.asStateFlow()

    val hasCompass: Boolean get() = orientation.available
    val hasLocationPermission: Boolean get() = location.hasPermission()

    private var jobs = emptyList<Job>()

    fun start() {
        if (jobs.any { it.isActive }) return
        jobs = listOf(
            viewModelScope.launch {
                if (!location.hasPermission()) return@launch
                location.fixes(intervalMillis = 2000).collect { _fix.value = it }
            },
            viewModelScope.launch {
                combine(settings.settings, _fix) { s, f -> s.declination?.toDouble() ?: f?.let { orientation.declinationAt(it.position) } ?: 0.0 }
                    .collect { d -> update { copy(declination = d) } }
            },
            viewModelScope.launch {
                if (!orientation.available) return@launch
                orientation.orientation().collect { o -> _heading.value = o.lookMagnetic ?: o.azimuthMagnetic }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    fun setMode(m: FixMode) = update { copy(mode = m) }
    fun setMagnetic(on: Boolean) = update { copy(magnetic = on) }
    fun addSight() = update { copy(sights = sights + Sight()) }
    fun removeSight(i: Int) = update { if (sights.size > 2) copy(sights = sights.filterIndexed { k, _ -> k != i }) else this }
    fun setPoint(i: Int, p: SeekTarget) = edit(i) { copy(point = p) }
    fun setBearing(i: Int, text: String) = edit(i) { copy(bearing = text) }

    /** The current GPS fix as a station (forward intersection only). */
    fun useHere(i: Int) {
        val f = _fix.value ?: return
        setPoint(i, SeekTarget(null, "我的位置（±${f.accuracy?.let { "%.0f".format(it) } ?: "?"} m）", f.position))
    }

    /** Fills in the bearing the phone is sighting along now, in the reference the inputs use. */
    fun readCompass(i: Int) {
        val m = _heading.value ?: return
        val shown = if (_ui.value.magnetic) m else Geodesy.normalizeDegrees(m + _ui.value.declination)
        setBearing(i, "%.0f".format(shown))
    }

    fun save() = viewModelScope.launch {
        val u = _ui.value
        val r = u.result ?: return@launch
        val p = projects.ensureCurrent()
        val name = p.config.nextName(FeatureKind.GEO_POINT, features.all(p.id).map { it.title })
        val how = u.sights.filter { it.point != null }.joinToString("；") { "${it.point!!.title} ${it.bearing.trim()}°" }
        val created = features.create(p.id, FeatureKind.GEO_POINT, Geometry.Point(r.position)) {
            this[Props.TITLE] = name
            this[Props.NOTE] = "${u.mode.label}定点（${if (u.magnetic) "磁方位，已改正磁偏角 ${"%.1f".format(u.declination)}°" else "真方位"}）：$how"
            // The worst miss is about as far off as the point may be; never better than a few metres.
            this[Props.ACCURACY_M] = Math.round(maxOf(r.worstMiss * 2, 5.0) * 10) / 10.0
        }
        _saved.value = created.id
    }

    fun savedHandled() { _saved.value = null }

    private fun edit(i: Int, block: Sight.() -> Sight) = update {
        copy(sights = sights.mapIndexed { k, s -> if (k == i) s.block() else s })
    }

    private fun update(block: FixUi.() -> FixUi) {
        _ui.update { solve(it.block()) }
    }

    private fun solve(u: FixUi): FixUi {
        val read = u.sights.mapNotNull { s ->
            val p = s.point ?: return@mapNotNull null
            val b = s.bearing.trim().replace("°", "").toDoubleOrNull()?.takeIf { it in 0.0..360.0 } ?: return@mapNotNull null
            p.position to Geodesy.normalizeDegrees(if (u.magnetic) b + u.declination else b)
        }
        if (read.size < 2) return u.copy(result = null, problem = null)
        val r = when (u.mode) {
            FixMode.FORWARD -> BearingFix.intersect(read.map { BearingFix.Ray(it.first, it.second) })
            FixMode.RESECTION -> BearingFix.resection(read)
        }
        return u.copy(result = r, problem = if (r == null) "几条视线几乎平行，交不出点。换一个方向差得多的点再量。" else null)
    }

}
