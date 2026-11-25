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
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.GeoRoute
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Props
import org.geocrew.core.model.geoRoute
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Navigation argument: the id of a route record to open straight away. */
const val ROUTE_FEATURE_ARG = "feature"

/** A route being edited; [id] is null until it is first saved. [date] is typed text (yyyy-mm-dd). */
data class RouteDraft(
    val id: String? = null,
    val title: String = "",
    val date: String = "",
    val route: GeoRoute = GeoRoute(),
) {
    fun parsedDate(): LocalDate? = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
}

/**
 * Geological routes (地质路线) of the open project: the day's observation
 * points in walking order with the description between them, saved as a
 * [FeatureKind.ROUTE] record whose line joins the points.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RouteViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()

    private val all = projects.current
        .flatMapLatest { p -> features.observeProject(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val byId: StateFlow<Map<String, GeoFeature>> = all.map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val routes: StateFlow<List<GeoFeature>> = all
        .map { list ->
            list.filter { it.kind == FeatureKind.ROUTE && !it.deleted }
                .sortedWith(compareByDescending<GeoFeature> { it.geoRoute.date }.thenByDescending { it.sync.updatedAt })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Point records that can be added as stops, newest first. */
    val candidates: StateFlow<List<GeoFeature>> = all
        .map { list -> list.filter(GeoRoute::isStop).sortedByDescending { it.createdAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow<RouteDraft?>(null)
    val draft: StateFlow<RouteDraft?> = _draft.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    init {
        savedState.get<String>(ROUTE_FEATURE_ARG)?.let { id ->
            viewModelScope.launch { features.get(id)?.takeIf { it.kind == FeatureKind.ROUTE }?.let(::open) }
        }
    }

    /** A new route for today, already holding today's points in the order they were made. */
    fun startNew() {
        val today = LocalDate.now(zone)
        val list = all.value
        val taken = routes.value.flatMap { r -> r.geoRoute.stops.map { it.featureId } }.toSet()
        val stops = GeoRoute.dayStops(list, today, zone).map { it.id }.filterNot { it in taken }
        _draft.value = RouteDraft(
            title = nextNumber(routes.value.mapNotNull { it.title }),
            date = today.toString(),
            route = GeoRoute(date = today).plus(stops),
        )
        if (stops.isNotEmpty()) _message.value = "已按记录顺序填入今天的 ${stops.size} 个点，可以调整"
    }

    fun open(f: GeoFeature) {
        val r = f.geoRoute
        _draft.value = RouteDraft(f.id, f.title.orEmpty(), r.date?.toString().orEmpty(), r)
    }

    fun close() { _draft.value = null }

    fun edit(block: (RouteDraft) -> RouteDraft) = _draft.update { it?.let(block) }

    fun editRoute(block: (GeoRoute) -> GeoRoute) = edit { it.copy(route = block(it.route)) }

    /** Adds the points of [day] that the route lacks, in the order they were made. */
    fun fillDay() {
        val d = _draft.value ?: return
        val day = d.parsedDate() ?: LocalDate.now(zone)
        val ids = GeoRoute.dayStops(all.value, day, zone).map { it.id }.filterNot(d.route::contains)
        if (ids.isEmpty()) {
            _message.value = "$day 没有其他新记的点"
            return
        }
        editRoute { it.plus(ids) }
        _message.value = "加入了 ${ids.size} 个点"
    }

    fun save() {
        val d = _draft.value ?: return
        val date = if (d.date.isBlank()) null else d.parsedDate() ?: run {
            _message.value = "日期请写成 2026-11-24 这样"
            return
        }
        val route = d.route.copy(date = date)
        if (route.stops.isEmpty() && route.summary.isBlank()) {
            _message.value = "至少加一个点或写一段小结再保存"
            return
        }
        val path = route.path { byId.value[it] }
        val geometry = when {
            path.size >= 2 -> Geometry.LineString(path)
            path.size == 1 -> Geometry.Point(path[0])
            else -> null
        }
        val title = d.title.trim().ifEmpty { "地质路线" }
        viewModelScope.launch {
            val saved = if (d.id == null) {
                features.create(projects.current.first().id, FeatureKind.ROUTE, geometry) {
                    this[Props.TITLE] = title
                    put(Props.ROUTE, route.toJson())
                }
            } else {
                features.replace(d.id, geometry) {
                    this[Props.TITLE] = title
                    put(Props.ROUTE, route.toJson())
                }
            }
            if (saved != null) _draft.update { it?.copy(id = saved.id, route = route) }
            _message.value = if (geometry == null) "已保存（点都没有位置，不会显示在地图上）" else "已保存，地图上显示为一条路线"
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            features.delete(id)
            if (_draft.value?.id == id) _draft.value = null
        }
    }

    companion object {
        /** L001, L002…: one more than the largest number after an L, keeping its width. */
        internal fun nextNumber(titles: List<String>): String {
            val re = Regex("^L(\\d+)$", RegexOption.IGNORE_CASE)
            val nums = titles.mapNotNull { re.find(it.trim())?.groupValues?.get(1) }
            val width = nums.maxOfOrNull { it.length } ?: 3
            val next = (nums.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1
            return "L" + next.toString().padStart(width, '0')
        }
    }
}
