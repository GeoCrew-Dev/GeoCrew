package org.geocrew.feature.analysis

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.gis.GisTool
import org.geocrew.core.gis.ToolCancelled
import org.geocrew.core.gis.ToolInput
import org.geocrew.core.gis.ToolOutput
import org.geocrew.core.gis.ToolParam
import org.geocrew.core.gis.ToolRegistry
import org.geocrew.core.gis.numericFields
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.SyncInfo
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

data class RunState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val output: ToolOutput? = null,
    val error: String? = null,
    val saved: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
/** Optional navigation arguments: a tool id to open, and a record id for its first record parameter. */
const val ANALYSIS_TOOL_ARG = "tool"
const val ANALYSIS_FEATURE_ARG = "feature"

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    savedState: SavedStateHandle,
    tools: Set<@JvmSuppressWildcards GisTool>,
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    private val clock: Clock,
    pluginTools: PluginTools,
    /** Starts tools from other apps; the screen launches what it asks for. */
    val plugins: PluginHost,
) : ViewModel() {
    val registry = ToolRegistry(tools + pluginTools.load())

    private val _tool = MutableStateFlow<GisTool?>(null)
    val tool: StateFlow<GisTool?> = _tool.asStateFlow()

    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    private val _run = MutableStateFlow(RunState())
    val run: StateFlow<RunState> = _run.asStateFlow()

    private val all: StateFlow<List<GeoFeature>> = projects.current.map { it.id }.distinctUntilChanged()
        .flatMapLatest { features.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Features the chosen tool would read. */
    val input: StateFlow<List<GeoFeature>> = combine(all, _tool) { list, t ->
        if (t == null) emptyList() else ToolInput(list).of(t.accepts)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val fields: StateFlow<List<String>> = input.map { numericFields(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Text properties usable as categories, for non-numeric field parameters. */
    val categories: StateFlow<List<String>> = input.map { org.geocrew.core.gis.categoryFields(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Why the tool cannot run with the current values, or null. */
    val problem: StateFlow<String?> = combine(input, _tool, _values) { list, t, v ->
        t?.check(ToolInput(list, v))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var job: Job? = null

    init {
        // Opened from a record ("use as section line"): preselect the tool and the record.
        val preset = savedState.get<String>(ANALYSIS_TOOL_ARG)?.let { registry.find(it) }
        if (preset != null) {
            choose(preset)
            savedState.get<String>(ANALYSIS_FEATURE_ARG)?.let { fid ->
                preset.params.filterIsInstance<ToolParam.Feature>().firstOrNull()?.let { set(it.key, fid) }
            }
        }
    }

    fun choose(t: GisTool?) {
        cancel()
        _tool.value = t
        _values.value = emptyMap()
        _run.value = RunState()
    }

    fun set(key: String, value: String) {
        _values.value = _values.value + (key to value)
        _run.value = _run.value.copy(output = null, error = null, saved = null)
    }

    fun start() {
        val t = _tool.value ?: return
        if (_run.value.running) return
        val snapshot = ToolInput(input.value, _values.value)
        job = viewModelScope.launch {
            _run.value = RunState(running = true)
            try {
                val out = withContext(Dispatchers.Default) {
                    val ctx = coroutineContext
                    t.run(snapshot.copy(cancelled = { !ctx.isActive })) { p -> _run.value = _run.value.copy(progress = p) }
                }
                _run.value = RunState(output = out)
            } catch (_: ToolCancelled) {
                _run.value = RunState()
            } catch (e: IllegalArgumentException) {
                _run.value = RunState(error = e.message)
            } catch (e: RuntimeException) {
                _run.value = RunState(error = "分析出错：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_run.value.running) _run.value = RunState()
    }

    /** Stores the output as features of the current project, so they sync and export like any other. */
    fun save() {
        val out = _run.value.output ?: return
        if (out.features.isEmpty()) return
        viewModelScope.launch {
            val p = projects.ensureCurrent()
            val now = clock.instant()
            features.importAll(
                out.features.map { o ->
                    GeoFeature(UUID.randomUUID().toString(), p.id, o.kind, o.geometry, o.properties, now, SyncInfo(updatedAt = now))
                },
            )
            _run.value = _run.value.copy(saved = out.features.size)
        }
    }
}
