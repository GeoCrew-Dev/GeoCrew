package org.geocrew.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.AppSettings
import org.geocrew.core.data.repo.BuildDefaults
import org.geocrew.core.data.repo.OfflineBasemaps
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.tiles.TileLayer
import org.geocrew.core.model.tiles.TileSource
import org.geocrew.core.model.tiles.TileTemplates
import java.util.UUID
import javax.inject.Inject

/** Input of the "add tile source" form. */
data class CustomSourceForm(
    val name: String = "",
    val url: String = "",
    val subdomains: String = "",
    val maxZoom: String = "18",
    val tms: Boolean = false,
    val attribution: String = "",
) {
    fun error(): String? {
        if (name.isBlank()) return "请填写名称"
        val z = maxZoom.toIntOrNull()
        if (z == null || z !in 1..24) return "最大级别应为 1 到 24"
        return TileTemplates.validate(url, TileTemplates.parseSubdomains(subdomains))
    }

    fun toSource(id: String = "custom-" + UUID.randomUUID().toString().take(8)) = TileSource(
        id = id,
        name = name.trim(),
        layers = listOf(
            TileLayer(
                url = url.trim(),
                subdomains = TileTemplates.parseSubdomains(subdomains),
                maxZoom = maxZoom.toInt(),
                tms = tms,
            ),
        ),
        attribution = attribution.trim(),
        maxZoom = maxZoom.toInt(),
        custom = true,
        group = "自定义",
    )
}

/** Progress of an MBTiles import; [done] and [total] are tile counts (0 while copying the file). */
data class BasemapProgress(val done: Int, val total: Int)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val defaults: BuildDefaults,
    private val offline: OfflineBasemaps,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _importing = MutableStateFlow<BasemapProgress?>(null)
    val importing: StateFlow<BasemapProgress?> = _importing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Disk use of each offline base map, by source id. */
    private val _sizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val sizes: StateFlow<Map<String, Long>> = _sizes.asStateFlow()

    private var importJob: Job? = null

    init {
        viewModelScope.launch {
            repo.settings.map { s -> s.customSources.filter { it.offline } }.distinctUntilChanged().collect { list ->
                _sizes.value = list.associate { it.id to offline.size(it) }
            }
        }
    }

    fun consumeMessage() { _message.value = null }

    /** Unpacks an MBTiles file picked by the user into a new offline base map. */
    fun importBasemap(uri: android.net.Uri, fileName: String) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _importing.value = BasemapProgress(0, 0)
            try {
                val r = offline.import(uri, fileName.substringBeforeLast('.').ifBlank { "离线底图" }) { done, total ->
                    _importing.value = BasemapProgress(done, total)
                }
                _message.value = "已导入「${r.source.name}」，${r.source.layers.first().minZoom}–${r.source.layers.first().maxZoom} 级共 ${r.tiles} 张瓦片，可在地图的底图切换里选用" +
                    (if (r.skipped > 0) "（${r.skipped} 张无效已跳过）" else "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "导入失败：" + (e.message ?: "无法读取文件")
            } finally {
                _importing.value = null
            }
        }
    }

    fun cancelImport() { importJob?.cancel() }

    fun deleteBasemap(source: TileSource) = viewModelScope.launch { offline.delete(source) }

    /** Whether this build ships a developer key (debug builds only). */
    val hasBuildKey: Boolean get() = defaults.tiandituKey.isNotBlank()

    private val _check = MutableStateFlow<KeyCheck?>(null)
    val check: StateFlow<KeyCheck?> = _check.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun saveKey(key: String) = viewModelScope.launch {
        repo.setTiandituKey(key)
        repo.setKeyPromptDone()
        _check.value = null
    }

    /** Saves the key, then tries one tile with it. */
    fun saveAndCheck(key: String) = viewModelScope.launch {
        repo.setTiandituKey(key)
        repo.setKeyPromptDone()
        _checking.value = true
        _check.value = TileKeyChecker.checkTianditu(key, defaults.userAgent)
        _checking.value = false
    }

    fun clearKey() = saveKey("")

    fun addSource(form: CustomSourceForm) = viewModelScope.launch {
        if (form.error() == null) repo.saveCustomSource(form.toSource())
    }

    fun deleteSource(id: String) = viewModelScope.launch { repo.deleteCustomSource(id) }

    fun setCoordinateFormat(f: CoordinateFormat) = viewModelScope.launch { repo.setCoordinateFormat(f) }

    fun setDeclination(value: Float?) = viewModelScope.launch { repo.setDeclination(value) }

    fun setTrackInterval(s: Int) = viewModelScope.launch { repo.setTrackInterval(s) }

    fun setTrackMinDistance(m: Int) = viewModelScope.launch { repo.setTrackMinDistance(m) }

    fun setNickname(n: String) = viewModelScope.launch { repo.setNickname(n) }
}
