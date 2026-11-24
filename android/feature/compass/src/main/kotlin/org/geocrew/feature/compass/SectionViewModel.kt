package org.geocrew.feature.compass

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.MeasuredSection
import org.geocrew.core.model.Props
import org.geocrew.core.model.SectionLayer
import org.geocrew.core.model.TraverseLeg
import org.geocrew.core.model.measuredSection
import org.geocrew.core.sensors.LocationSource
import javax.inject.Inject

/** Navigation argument: the id of a section record to open straight away. */
const val SECTION_FEATURE_ARG = "feature"

/** Text input of one traverse leg of a section. */
data class SectionLegInput(val azimuth: String = "", val slope: String = "0", val distance: String = "") {
    fun parse(): TraverseLeg? {
        val a = azimuth.toDoubleOrNull()?.takeIf { it in 0.0..360.0 } ?: return null
        val s = slope.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
        val d = distance.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return TraverseLeg(a, s, d)
    }

    companion object {
        fun of(l: TraverseLeg) = SectionLegInput(l.azimuth.short(), l.slopeAngle.short(), l.slopeDistance.short())
    }
}

/** Text input of one layer; [dipDirection] and [dip] are both blank when not measured. */
data class SectionLayerInput(
    val no: String,
    val top: String = "",
    val lithology: String = "",
    val dipDirection: String = "",
    val dip: String = "",
    val note: String = "",
) {
    fun attitude(): Attitude? {
        val dd = dipDirection.toDoubleOrNull()?.takeIf { it in 0.0..360.0 } ?: return null
        val d = dip.toDoubleOrNull()?.takeIf { it in 0.0..90.0 } ?: return null
        return Attitude.of(dd, d)
    }

    fun parse(): SectionLayer? {
        val t = top.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return SectionLayer(no.trim(), t, lithology.trim(), attitude(), note.trim())
    }

    companion object {
        fun of(l: SectionLayer) = SectionLayerInput(
            l.no, l.top.short(), l.lithology,
            l.attitude?.dipDirection?.short().orEmpty(), l.attitude?.dip?.short().orEmpty(), l.note,
        )
    }
}

/** Where the traverse starts on the map. */
data class SectionStart(val position: LngLat, val label: String)

/** A section being edited; [id] is null until it is first saved. */
data class SectionDraft(
    val id: String? = null,
    val title: String = "",
    val legs: List<SectionLegInput> = listOf(SectionLegInput()),
    val layers: List<SectionLayerInput> = listOf(SectionLayerInput("1")),
    val start: SectionStart? = null,
) {
    fun section() = MeasuredSection(legs.mapNotNull { it.parse() }, layers.mapNotNull { it.parse() })

    /** Next layer number: one more than the last numeric number. */
    fun nextNo(): String = (layers.lastOrNull()?.no?.trim()?.toIntOrNull()?.plus(1) ?: (layers.size + 1)).toString()
}

/** A record the start point or a layer's attitude can be taken from. */
data class SectionPick(val id: String, val title: String, val position: LngLat?, val attitude: Attitude?)

/**
 * Measured sections (实测剖面) of the open project: lists them, edits one at
 * a time and saves it as a [FeatureKind.SECTION] record whose geometry is the
 * traverse drawn from its start point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SectionViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    private val location: LocationSource,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val all = projects.current
        .flatMapLatest { p -> features.observeProject(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sections: StateFlow<List<GeoFeature>> = all
        .map { list -> list.filter { it.kind == FeatureKind.SECTION }.sortedByDescending { it.sync.updatedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val picks: StateFlow<List<SectionPick>> = all
        .map { list ->
            list.mapNotNull { f ->
                val pos = (f.geometry as? Geometry.Point)?.position
                val att = f.attitude
                if (pos == null && att == null) null
                else SectionPick(f.id, f.title?.takeIf { it.isNotBlank() } ?: "未命名", pos, att)
            }.sortedBy { it.title }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lithologies already used in the project, most common first, as input suggestions. */
    val lithologies: StateFlow<List<String>> = all
        .map { list ->
            val used = list.flatMap { f ->
                buildList {
                    (f.properties[Props.LITHOLOGY] as? JsonPrimitive)?.contentOrNull?.let(::add)
                    if (f.kind == FeatureKind.SECTION) f.measuredSection.layers.forEach { add(it.lithology) }
                }
            }.map { it.trim() }.filter { it.isNotEmpty() }
            used.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow<SectionDraft?>(null)
    val draft: StateFlow<SectionDraft?> = _draft.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    init {
        savedState.get<String>(SECTION_FEATURE_ARG)?.let { id ->
            viewModelScope.launch { features.get(id)?.takeIf { it.kind == FeatureKind.SECTION }?.let(::open) }
        }
    }

    fun startNew() {
        val n = sections.value.size + 1
        _draft.value = SectionDraft(title = "剖面 $n")
    }

    fun open(f: GeoFeature) {
        val s = f.measuredSection
        val start = when (val g = f.geometry) {
            is Geometry.LineString -> g.positions.firstOrNull()
            is Geometry.Point -> g.position
            else -> null
        }
        _draft.value = SectionDraft(
            id = f.id,
            title = f.title.orEmpty(),
            legs = s.legs.map(SectionLegInput::of).ifEmpty { listOf(SectionLegInput()) },
            layers = s.layers.map(SectionLayerInput::of).ifEmpty { listOf(SectionLayerInput("1")) },
            start = start?.let { SectionStart(it, "已保存的起点") },
        )
    }

    fun close() { _draft.value = null }

    fun edit(block: (SectionDraft) -> SectionDraft) = _draft.update { it?.let(block) }

    fun startFromPick(p: SectionPick) {
        val pos = p.position ?: return
        edit { it.copy(start = SectionStart(pos, p.title)) }
    }

    fun startHere() {
        viewModelScope.launch {
            if (!location.hasPermission() || !location.isEnabled()) {
                _message.value = "定位不可用：请打开定位并允许同勘使用"
                return@launch
            }
            val fix = withTimeoutOrNull(15_000) { location.fixes(intervalMillis = 1000).first() }
            if (fix == null) {
                _message.value = "15 秒内没有拿到定位，可以改用一条记录的位置"
                return@launch
            }
            val acc = fix.accuracy?.let { String.format(java.util.Locale.ROOT, " ±%.0f m", it) }.orEmpty()
            edit { it.copy(start = SectionStart(fix.position, "当前位置$acc")) }
        }
    }

    /** Saves the draft as a record; without a start point the record has no geometry. */
    fun save() {
        val d = _draft.value ?: return
        val section = d.section()
        if (section.legs.isEmpty() && section.layers.isEmpty()) {
            _message.value = "至少填一段导线或一个分层再保存"
            return
        }
        val geometry = d.start?.let { s ->
            val path = section.path(s.position)
            if (path.size >= 2) Geometry.LineString(path) else Geometry.Point(s.position)
        }
        val title = d.title.trim().ifEmpty { "实测剖面" }
        viewModelScope.launch {
            val saved = if (d.id == null) {
                features.create(projects.current.first().id, FeatureKind.SECTION, geometry) {
                    this[Props.TITLE] = title
                    put(Props.SECTION, section.toJson())
                }
            } else {
                features.edit(d.id, geometry) {
                    this[Props.TITLE] = title
                    put(Props.SECTION, section.toJson())
                }
            }
            if (saved != null) _draft.update { it?.copy(id = saved.id) }
            _message.value = if (geometry == null) "已保存（没有起点，不会显示在地图上）" else "已保存，地图上显示为一条剖面线"
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            features.delete(id)
            if (_draft.value?.id == id) _draft.value = null
        }
    }
}

internal fun Double.short(): String =
    if (this == Math.rint(this)) String.format(java.util.Locale.ROOT, "%.0f", this)
    else String.format(java.util.Locale.ROOT, "%.2f", this).trimEnd('0').trimEnd('.')
