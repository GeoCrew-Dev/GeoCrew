package org.geocrew.feature.compass

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.Ellipsoid
import org.geocrew.core.model.GaussKruger
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.Geodetic
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.LocalGrid
import org.geocrew.core.sensors.LocationSource
import javax.inject.Inject

/** A point record offered as input to the converter. */
data class PointPick(val id: String, val title: String, val position: LngLat)

/**
 * Backs the coordinate converter: point records to pick from, a one-off
 * GNSS fix, and the plane-coordinate table export.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CoordinatesViewModel @Inject constructor(
    private val projects: ProjectRepository,
    features: FeatureRepository,
    private val location: LocationSource,
) : ViewModel() {
    val points: StateFlow<List<PointPick>> = projects.current
        .flatMapLatest { p -> features.observeProject(p.id) }
        .map { list ->
            list.mapNotNull { f ->
                (f.geometry as? Geometry.Point)?.position?.let { PointPick(f.id, f.title?.takeIf { it.isNotBlank() } ?: "未命名", it) }
            }.sortedBy { it.title }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The project's local map grid (Beijing 54, Xi'an 80...), if one is set up. */
    val grid: StateFlow<LocalGrid?> = projects.current.map { it.config.localGrid }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveGrid(g: LocalGrid?) = viewModelScope.launch {
        val id = projects.current.first().id
        projects.updateConfig(id) { it.copy(localGrid = g) }
        _message.value = if (g == null) "已不再使用本地坐标系" else "已保存：${g.name}"
    }

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() { _message.value = null }

    /** Takes one fix and hands it to [onFix]. */
    fun locate(onFix: (LngLat, Float?) -> Unit) {
        if (_locating.value) return
        if (!location.hasPermission()) { _message.value = "没有定位权限，请先在地图页允许定位"; return }
        if (!location.isEnabled()) { _message.value = "系统定位已关闭"; return }
        viewModelScope.launch {
            _locating.value = true
            try {
                val fix = withTimeoutOrNull(30_000) { location.fixes(intervalMillis = 1000).first() }
                if (fix == null) _message.value = "30 秒内没有拿到定位，请到开阔处再试" else onFix(fix.position, fix.accuracy)
            } finally {
                _locating.value = false
            }
        }
    }

    /**
     * Writes every point record as a CSV table of plane coordinates. All
     * points go into one zone (the one most of them fall in), so distances
     * between rows stay comparable; the zone is repeated on each row.
     */
    fun export(context: Context, uri: Uri, width: Int, ellipsoid: Ellipsoid, grid: LocalGrid? = null) {
        val list = points.value
        if (list.isEmpty()) { _message.value = "当前工程没有点记录"; return }
        if (grid != null) return exportGrid(context, uri, list, grid)
        viewModelScope.launch {
            val zone = list.groupingBy { GaussKruger.zone(it.position.lng, width) }.eachCount().maxBy { it.value }.key
            val text = buildString {
                append("名称,经度,纬度,高程,X(北),Y(东),Y(含带号),带号,中央子午线,椭球\n")
                for (p in list) {
                    val r = GaussKruger.project(p.position, width, ellipsoid, zone)
                    append(csv(p.title)).append(',')
                    append("%.8f,%.8f,".format(java.util.Locale.ROOT, p.position.lng, p.position.lat))
                    append(p.position.alt?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "").append(',')
                    append("%.3f,%.3f,%.3f,%d,%.0f,".format(java.util.Locale.ROOT, r.x, r.y, r.yWithZone, r.zone, r.centralMeridian))
                    append(csv(ellipsoid.label)).append('\n')
                }
            }
            val ok = write(context, uri, text)
            _message.value = if (ok) "已导出 ${list.size} 个点（${width}° 带第 $zone 带）" else "无法写入所选文件"
        }
    }

    /** The same table on the project's local grid, through its datum shift. */
    private fun exportGrid(context: Context, uri: Uri, list: List<PointPick>, grid: LocalGrid) {
        viewModelScope.launch {
            val text = buildString {
                append("名称,经度,纬度,高程,X(北),Y(东),Y(含带号),坐标系,转换\n")
                for (p in list) {
                    val (x, y) = grid.toGrid(Geodetic(p.position, p.position.alt ?: 0.0))
                    append(csv(p.title)).append(',')
                    append("%.8f,%.8f,".format(java.util.Locale.ROOT, p.position.lng, p.position.lat))
                    append(p.position.alt?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "").append(',')
                    append("%.3f,%.3f,%.3f,".format(java.util.Locale.ROOT, x, y, grid.zone * 1_000_000.0 + y))
                    append(csv(grid.name)).append(',').append(csv(grid.shift.describe())).append('\n')
                }
            }
            val ok = write(context, uri, text)
            _message.value = if (ok) "已导出 ${list.size} 个点（${grid.name}）" else "无法写入所选文件"
        }
    }

    private suspend fun write(context: Context, uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                // BOM so spreadsheet programs read the Chinese headers right.
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                out.write(text.toByteArray())
            } != null
        }.getOrDefault(false)
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
