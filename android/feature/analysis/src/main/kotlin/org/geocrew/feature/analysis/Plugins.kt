package org.geocrew.feature.analysis

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.geocrew.core.gis.ExternalTool
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analysis tools from other apps (geocrew-protocol `docs/plugins.md`). A
 * plugin declares an activity for [ACTION] with a [META_TOOL] meta-data
 * pointing at a raw JSON descriptor; the tool runs by starting that activity
 * with the input GeoJSON and a file to write the result to.
 */
@Singleton
class PluginTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val host: PluginHost,
) {
    /** Installed plugins' tools, read fresh each time (apps come and go). Broken ones are skipped. */
    fun load(): List<ExternalTool> {
        val pm = context.packageManager
        val found = runCatching {
            pm.queryIntentActivities(Intent(ACTION), PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        return found.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            if (ai.packageName == context.packageName) return@mapNotNull null
            val res = ai.metaData?.getInt(META_TOOL, 0)?.takeIf { it != 0 } ?: return@mapNotNull null
            runCatching {
                val text = pm.getResourcesForApplication(ai.applicationInfo).openRawResource(res).use { s ->
                    s.readBytes().takeIf { it.size <= MAX_DESCRIPTOR }?.decodeToString() ?: error("descriptor too large")
                }
                val label = ai.applicationInfo.loadLabel(pm).toString().take(30)
                ExternalTool(ExternalTool.parseDescriptor(text), ai.packageName, label, host).also {
                    host.register(it.id, ai.packageName, ai.name)
                }
            }.onFailure { Log.w(TAG, "skipping plugin ${ai.packageName}: ${it.message}") }.getOrNull()
        }.distinctBy { it.id }
    }

    companion object {
        const val ACTION = "org.geocrew.plugin.action.RUN_TOOL"
        const val META_TOOL = "org.geocrew.plugin.TOOL"
        private const val MAX_DESCRIPTOR = 64 * 1024
        private const val TAG = "PluginTools"
    }
}

/**
 * Hands a run to the plugin's activity and blocks the tool's worker thread
 * until the screen reports the result. The analysis screen collects
 * [request], starts it, and passes the activity result to [finish].
 */
@Singleton
class PluginHost @Inject constructor(@ApplicationContext private val context: Context) : ExternalTool.Runner {
    data class Request(val id: String, val intent: Intent)

    private val targets = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    @Volatile private var pending: Pair<String, CompletableFuture<Int>>? = null

    internal fun register(toolId: String, packageName: String, activity: String) {
        targets[toolId] = packageName to activity
    }

    override fun run(tool: ExternalTool, input: String, cancelled: () -> Boolean): String {
        val (pkg, activity) = targets[tool.id] ?: throw IllegalArgumentException("找不到提供这个工具的应用，可能已被卸载")
        val dir = File(context.cacheDir, "plugin").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > STALE_MS) it.delete() }
        val id = UUID.randomUUID().toString()
        val inFile = File(dir, "$id-in.geojson").apply { writeText(input) }
        val outFile = File(dir, "$id-out.geojson").apply { writeText("") }
        val authority = "${context.packageName}.files"
        val inUri: Uri = FileProvider.getUriForFile(context, authority, inFile)
        val outUri: Uri = FileProvider.getUriForFile(context, authority, outFile)
        val intent = Intent(PluginTools.ACTION).apply {
            setClassName(pkg, activity)
            setDataAndType(inUri, "application/geo+json")
            putExtra(EXTRA_OUTPUT, outUri)
            putExtra(EXTRA_API, ExternalTool.API_VERSION)
            // Both grants travel with the intent's clip data.
            clipData = ClipData.newRawUri("input", inUri).apply { addItem(ClipData.Item(outUri)) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val done = CompletableFuture<Int>()
        pending = id to done
        _request.value = Request(id, intent)
        try {
            var code: Int? = null
            while (code == null) {
                if (cancelled()) throw org.geocrew.core.gis.ToolCancelled()
                code = try {
                    done.get(250, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    null
                }
            }
            val text = outFile.takeIf { it.length() in 1..MAX_OUTPUT }?.readText()
            return when {
                code == Activity.RESULT_OK && text != null -> text
                code == Activity.RESULT_OK -> throw IllegalArgumentException(
                    if (outFile.length() > MAX_OUTPUT) "插件返回的结果太大" else "插件没有写出结果",
                )
                text != null -> text // a plugin may report an error in the file and cancel
                else -> throw IllegalArgumentException(if (code == LAUNCH_FAILED) "打不开提供这个工具的应用" else "插件已取消")
            }
        } finally {
            if (pending?.first == id) pending = null
            if (_request.value?.id == id) _request.value = null
            inFile.delete()
            outFile.delete()
        }
    }

    /** The screen took the request and is starting it; it is not offered again. */
    fun launched(id: String) {
        if (_request.value?.id == id) _request.value = null
    }

    /** The plugin's activity finished, or could not be started ([LAUNCH_FAILED]). */
    fun finish(resultCode: Int) {
        pending?.second?.complete(resultCode)
    }

    companion object {
        const val EXTRA_OUTPUT = "org.geocrew.plugin.extra.OUTPUT"
        const val EXTRA_API = "org.geocrew.plugin.extra.API"
        const val LAUNCH_FAILED = Int.MIN_VALUE
        private const val MAX_OUTPUT = 50L * 1024 * 1024
        private const val STALE_MS = 24 * 3_600_000L
    }
}
