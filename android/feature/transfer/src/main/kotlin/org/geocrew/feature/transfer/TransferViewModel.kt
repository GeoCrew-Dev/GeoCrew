package org.geocrew.feature.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.PhotoStore
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.TrackRepository
import org.geocrew.core.model.applyAssays
import org.geocrew.core.formats.ExportContext
import org.geocrew.core.formats.FeatureFormat
import org.geocrew.core.formats.SampleLabels
import org.geocrew.core.formats.FeatureFormats
import org.geocrew.core.formats.FieldReport
import org.geocrew.core.formats.ImportContext
import org.geocrew.core.formats.ImportResult
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.SeekGuide
import org.geocrew.core.model.photoInfo
import org.geocrew.core.ui.KindUi
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** A parsed file waiting for the user to confirm the import. */
data class ImportPreview(val fileName: String, val format: FeatureFormat, val result: ImportResult) {
    val counts: Map<FeatureKind, Int> get() = result.features.groupingBy { it.kind }.eachCount()
}

/** A lab result sheet matched against the project's samples, waiting for confirmation. */
data class AssayPreview(val fileName: String, val plan: org.geocrew.core.model.AssayPlan)

sealed interface TransferMessage {
    data class Info(val text: String) : TransferMessage
    data class Error(val text: String) : TransferMessage
}

@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    private val tracks: TrackRepository,
    private val photoStore: PhotoStore,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _preview = MutableStateFlow<ImportPreview?>(null)
    val preview: StateFlow<ImportPreview?> = _preview.asStateFlow()

    private val _message = MutableStateFlow<TransferMessage?>(null)
    val message: StateFlow<TransferMessage?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val formats: List<FeatureFormat> = FeatureFormats.all

    fun clearMessage() {
        _message.value = null
    }

    /** Reads and parses a picked file; nothing is saved until [confirmImport]. */
    fun open(uri: Uri) = work {
        val name = displayName(uri) ?: "未命名文件"
        val type = context.contentResolver.getType(uri)
        val format = FeatureFormats.detect(name, type)?.takeIf { it.canRead }
            ?: return@work fail("认不出「$name」的格式。支持：" + formats.filter { it.canRead }.joinToString("、") { it.displayName })
        val project = projects.ensureCurrent()
        val result = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { format.read(it, ImportContext(project.id, fields = project.config.fields)) }
        } ?: return@work fail("打不开「$name」。")
        if (result.features.isEmpty()) return@work fail("「$name」里没有可导入的要素。" + result.warnings.joinToString(" "))
        _preview.value = ImportPreview(name, format, result)
    }

    fun confirmImport() = work {
        val p = _preview.value ?: return@work
        // Ids from a file are kept so re-importing updates instead of duplicating,
        // but an id already used in another project gets a fresh one.
        val incoming = p.result.features.map { f ->
            val existing = features.get(f.id)
            if (existing != null && existing.projectId != f.projectId) f.copy(id = java.util.UUID.randomUUID().toString()) else f
        }
        features.importAll(incoming)
        _preview.value = null
        _message.value = TransferMessage.Info("已导入 ${p.result.features.size} 个要素。")
    }

    fun cancelImport() {
        _preview.value = null
    }

    private val _assays = MutableStateFlow<AssayPreview?>(null)
    val assays: StateFlow<AssayPreview?> = _assays.asStateFlow()

    /** Reads a lab result sheet (CSV) and matches it to the project's samples by number; nothing is saved yet. */
    fun openAssays(uri: Uri) = work {
        val name = displayName(uri) ?: "未命名文件"
        val project = projects.ensureCurrent()
        val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            ?: return@work fail("打不开「$name」。")
        val table = try {
            org.geocrew.core.formats.AssayCsv.read(bytes)
        } catch (e: org.geocrew.core.formats.FormatException) {
            return@work fail("「$name」：${e.message}")
        }
        val plan = org.geocrew.core.model.AssayPlan.of(table, features.all(project.id))
        if (plan.records.isEmpty()) {
            return@work fail("「$name」里的 ${table.rows.size} 个样品编号在当前工程里都找不到。请确认编号和记录里登记的样品编号一致（不分大小写）。")
        }
        _assays.value = AssayPreview(name, plan)
    }

    fun confirmAssays() = work {
        val p = _assays.value ?: return@work
        var n = 0
        for (r in p.plan.records) {
            val cur = features.get(r.featureId) ?: continue
            features.edit(r.featureId) { applyAssays(cur, r) }
            n++
        }
        _assays.value = null
        _message.value = TransferMessage.Info(
            "已把 ${p.plan.sampleCount} 件样品的分析结果写入 $n 条记录（${p.plan.columns.joinToString("、")}），可在图例、统计和插值里选用。",
        )
    }

    fun cancelAssays() {
        _assays.value = null
    }

    /** Suggested file name for an export in [format]. */
    fun exportName(format: FeatureFormat): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault()).format(Instant.now())
        val what = format.fileTag?.let { "$it-" }.orEmpty()
        return "geocrew-$what$stamp.${format.extension}"
    }

    fun export(format: FeatureFormat, uri: Uri) = work {
        val project = projects.ensureCurrent()
        val list = features.all(project.id)
        if (list.isEmpty()) return@work fail("当前工程还没有要素可以导出。")
        format.nothingToWrite(list)?.let { return@work fail(it) }
        // GPX keeps per-point times for tracks; collect them up front.
        val times = list.filter { it.kind == FeatureKind.TRACK }
            .associate { t -> t.id to tracks.points(t.id).map { it.time } }
        val ctx = ExportContext(title = project.name, trackTimes = { id -> times[id]?.takeIf { it.isNotEmpty() } }, fields = project.config.fields, legend = project.config.legend)
        val result = withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { format.write(list, it, ctx) }
        } ?: return@work fail("无法写入所选位置。")
        val skipped = if (result.skipped > 0) "，${result.skipped} 个此格式不支持已跳过" else ""
        _message.value = TransferMessage.Info("已导出 ${result.written} ${format.unit}$skipped。")
    }

    fun reportName(todayOnly: Boolean): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault()).format(Instant.now())
        return "geocrew-report-$stamp${if (todayOnly) "-today" else ""}.pdf"
    }

    /** Writes the project's records as a printable field notebook (PDF). */
    fun exportReport(uri: Uri, todayOnly: Boolean, withPhotos: Boolean) = work {
        val project = projects.ensureCurrent()
        val list = features.all(project.id)
        val zone = ZoneId.systemDefault()
        val report = FieldReport.build(
            projectName = project.name,
            features = list,
            fields = project.config.fields,
            kindLabel = KindUi::label,
            zone = zone,
            coordinates = settings.settings.first().coordinateFormat,
            days = if (todayOnly) setOf(LocalDate.now(zone)) else null,
        )
        if (report.entryCount == 0) {
            return@work fail(if (todayOnly) "今天还没有记录。" else "当前工程还没有记录可以生成报告。")
        }
        val info = list.associate { it.id to it.photoInfo }
        val pdf = FieldReportPdf(
            photo = { sha, px -> if (withPhotos) photoStore.decodeUpright(sha, px) else null },
            caption = { id, sha ->
                info[id]?.get(sha)?.azimuth?.let { "方向 %.0f°（%s）".format(it, SeekGuide.direction(it)) }
            },
            photosPerEntry = if (withPhotos) 3 else 0,
        )
        val pages = withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { pdf.write(report, it) }
        } ?: return@work fail("无法写入所选位置。")
        _message.value = TransferMessage.Info("已生成报告：${report.entryCount} 条记录，共 $pages 页。")
    }

    fun labelsName(): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault()).format(Instant.now())
        return "geocrew-labels-$stamp.pdf"
    }

    /** Prints every sample of the project as labels on A4 sheets (3 × 8). */
    fun exportLabels(uri: Uri) = work {
        val project = projects.ensureCurrent()
        val labels = SampleLabels.build(project.name, features.all(project.id), settings.settings.first().coordinateFormat)
        if (labels.isEmpty()) return@work fail("当前工程还没有登记样品。在记录详情的「样品」里添加。")
        val pages = withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { SampleLabelPdf().write(labels, it) }
        } ?: return@work fail("无法写入所选位置。")
        _message.value = TransferMessage.Info("已生成 ${labels.size} 张样品标签，共 $pages 页（A4，每页 3 × 8）。")
    }

    private fun fail(text: String) {
        _message.value = TransferMessage.Error(text)
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try {
            block()
        } catch (e: Exception) {
            fail("出错了：${e.message ?: e.javaClass.simpleName}")
        } finally {
            _busy.value = false
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
}
