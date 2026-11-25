package org.geocrew.feature.compass

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.formats.StratColumn
import org.geocrew.core.model.BoreLayer
import org.geocrew.core.model.Borehole
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.borehole
import org.geocrew.core.model.measuredSection
import org.geocrew.core.sensors.LocationSource
import javax.inject.Inject

/** Navigation argument: the id of a borehole record to open straight away. */
const val BOREHOLE_FEATURE_ARG = "feature"

/** Text input of one layer of a borehole log. */
data class BoreLayerInput(val no: String, val bottom: String = "", val lithology: String = "", val note: String = "") {
    fun parse(): BoreLayer? {
        val b = bottom.toDoubleOrNull()?.takeIf { it > 0 && it <= Borehole.MAX_DEPTH } ?: return null
        return BoreLayer(no.trim(), b, lithology.trim(), note.trim())
    }

    companion object {
        fun of(l: BoreLayer) = BoreLayerInput(l.no, l.bottom.short(), l.lithology, l.note)
    }
}

/** A borehole being edited; [id] is null until it is first saved. */
data class BoreholeDraft(
    val id: String? = null,
    val title: String = "",
    val collar: SectionStart? = null,
    val depth: String = "",
    val water: String = "",
    val layers: List<BoreLayerInput> = listOf(BoreLayerInput("1")),
) {
    fun hole() = Borehole(
        layers.mapNotNull { it.parse() },
        depth.toDoubleOrNull()?.takeIf { it > 0 && it <= Borehole.MAX_DEPTH },
        water.toDoubleOrNull()?.takeIf { it >= 0 && it <= Borehole.MAX_DEPTH },
    )

    fun nextNo(): String = (layers.lastOrNull()?.no?.trim()?.toIntOrNull()?.plus(1) ?: (layers.size + 1)).toString()
}

/**
 * Borehole, pit and shaft logs (钻孔 / 浅井编录) of the open project. Each is
 * a [FeatureKind.BOREHOLE] record at the collar; layers are logged by the
 * depth of their bottom, and the log prints as a borehole column.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BoreholeViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    private val location: LocationSource,
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val all = projects.current
        .flatMapLatest { p -> features.observeProject(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val holes: StateFlow<List<GeoFeature>> = all
        .map { list -> list.filter { it.kind == FeatureKind.BOREHOLE }.sortedByDescending { it.sync.updatedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Records with a position, to put the collar on. */
    val picks: StateFlow<List<SectionPick>> = all
        .map { list ->
            list.mapNotNull { f ->
                val pos = (f.geometry as? Geometry.Point)?.position ?: return@mapNotNull null
                if (f.kind == FeatureKind.BOREHOLE) return@mapNotNull null
                SectionPick(f.id, f.title?.takeIf { it.isNotBlank() } ?: "未命名", pos, null)
            }.sortedBy { it.title }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lithologies already used in the project, most common first. */
    val lithologies: StateFlow<List<String>> = all
        .map { list ->
            val used = list.flatMap { f ->
                buildList {
                    (f.properties[Props.LITHOLOGY] as? JsonPrimitive)?.contentOrNull?.let(::add)
                    when (f.kind) {
                        FeatureKind.SECTION -> f.measuredSection.layers.forEach { add(it.lithology) }
                        FeatureKind.BOREHOLE -> f.borehole.layers.forEach { add(it.lithology) }
                        else -> Unit
                    }
                }
            }.map { it.trim() }.filter { it.isNotEmpty() }
            used.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow<BoreholeDraft?>(null)
    val draft: StateFlow<BoreholeDraft?> = _draft.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    init {
        savedState.get<String>(BOREHOLE_FEATURE_ARG)?.let { id ->
            viewModelScope.launch { features.get(id)?.takeIf { it.kind == FeatureKind.BOREHOLE }?.let(::open) }
        }
    }

    fun startNew() {
        _draft.value = BoreholeDraft(title = "ZK${holes.value.size + 1}")
    }

    fun open(f: GeoFeature) {
        val h = f.borehole
        _draft.value = BoreholeDraft(
            id = f.id,
            title = f.title.orEmpty(),
            collar = (f.geometry as? Geometry.Point)?.position?.let { SectionStart(it, "已保存的孔口") },
            depth = h.depth?.short().orEmpty(),
            water = h.water?.short().orEmpty(),
            layers = h.layers.map(BoreLayerInput::of).ifEmpty { listOf(BoreLayerInput("1")) },
        )
    }

    fun close() { _draft.value = null }

    fun edit(block: (BoreholeDraft) -> BoreholeDraft) = _draft.update { it?.let(block) }

    fun collarFromPick(p: SectionPick) {
        val pos = p.position ?: return
        edit { it.copy(collar = SectionStart(pos, p.title)) }
    }

    fun collarHere() {
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
            edit { it.copy(collar = SectionStart(fix.position, "当前位置$acc")) }
        }
    }

    fun save() {
        val d = _draft.value ?: return
        val hole = d.hole()
        if (hole.layers.isEmpty() && hole.depth == null) {
            _message.value = "至少填一个分层或终孔深度再保存"
            return
        }
        val geometry = d.collar?.let { Geometry.Point(it.position) }
        val title = d.title.trim().ifEmpty { "钻孔" }
        viewModelScope.launch {
            val saved = if (d.id == null) {
                features.create(projects.current.first().id, FeatureKind.BOREHOLE, geometry) {
                    this[Props.TITLE] = title
                    put(Props.BOREHOLE, hole.toJson())
                }
            } else {
                features.edit(d.id, geometry) {
                    this[Props.TITLE] = title
                    put(Props.BOREHOLE, hole.toJson())
                }
            }
            if (saved != null) _draft.update { it?.copy(id = saved.id) }
            _message.value = if (geometry == null) "已保存（没有孔口位置，不会显示在地图上）" else "已保存，孔口显示在地图上"
        }
    }

    fun columnName(f: GeoFeature): String {
        val name = (f.title?.trim()?.ifEmpty { null } ?: "钻孔").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "$name-钻孔柱状图.pdf"
    }

    /** Draws [f] as a borehole column (A4 PDF) into [uri]. */
    fun exportColumn(f: GeoFeature, uri: Uri) {
        viewModelScope.launch {
            val c = StratColumn.borehole(f.title?.trim()?.ifEmpty { null } ?: "钻孔", f.borehole)
            if (c.rows.isEmpty()) {
                _message.value = "这个钻孔还没有分层"
                return@launch
            }
            val pages = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { StratColumnPdf().write(c, it) } ?: error("无法写入")
                }
            }.getOrElse {
                _message.value = "柱状图没有生成：${it.message ?: it::class.simpleName}"
                return@launch
            }
            _message.value = "已生成钻孔柱状图，比例尺 1:${c.scale}，共 $pages 页"
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            features.delete(id)
            if (_draft.value?.id == id) _draft.value = null
        }
    }
}
