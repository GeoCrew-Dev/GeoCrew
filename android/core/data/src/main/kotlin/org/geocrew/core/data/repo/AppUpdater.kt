package org.geocrew.core.data.repo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.geocrew.core.data.di.ApplicationScope
import org.geocrew.core.model.AppRelease
import org.geocrew.core.model.AppReleases
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Where an in-app update stands. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** [latest] is null when nothing has been published yet. */
    data class UpToDate(val latest: String?) : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val done: Long, val total: Long) : UpdateState
    /** Downloaded, hash and signature checked; the system installer does the rest. */
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/**
 * Checks the release feed, downloads the APK, verifies it and hands it to
 * the system installer, which asks the user to confirm (Android never
 * installs silently).
 *
 * The APK is only offered when its SHA-256 matches the release's
 * SHA256SUMS, it is this app (same package), its versionCode is higher, and
 * it is signed by the same key as the installed app. Builds without a feed
 * (store builds, debug) never check.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val defaults: BuildDefaults,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _autoCheck = MutableStateFlow(prefs.getBoolean(AUTO, true))

    /** Check once a day when the app starts. */
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()
    private var job: Job? = null
    private val dir get() = File(context.cacheDir, "updates")

    /** False in builds that are updated by a store. */
    val available: Boolean get() = defaults.updateFeed.isNotBlank()
    val currentVersion: String get() = defaults.versionName

    fun setAutoCheck(on: Boolean) {
        prefs.edit().putBoolean(AUTO, on).apply()
        _autoCheck.value = on
    }

    /** The version the user chose to skip in the start-up prompt. */
    fun skipped(): String = prefs.getString(SKIPPED, "").orEmpty()

    fun skip(release: AppRelease) {
        prefs.edit().putString(SKIPPED, release.version).apply()
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    /**
     * The daily check at start-up. Returns quietly on failure (no network in
     * the field is normal) and does not offer a version the user skipped.
     */
    fun checkOnStart() {
        if (!available || !_autoCheck.value) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(CHECKED_AT, 0) < DAY) return
        check(quiet = true)
    }

    /** Checks now; [quiet] hides failures and skipped versions. */
    fun check(quiet: Boolean = false) {
        if (!available || job?.isActive == true) return
        job = scope.launch {
            if (!quiet) _state.value = UpdateState.Checking
            _state.value = try {
                val r = withContext(Dispatchers.IO) { latest() }
                prefs.edit().putLong(CHECKED_AT, System.currentTimeMillis()).apply()
                when {
                    r == null -> if (quiet) UpdateState.Idle else UpdateState.UpToDate(null)
                    !AppReleases.newer(r.version, currentVersion) -> if (quiet) UpdateState.Idle else UpdateState.UpToDate(r.version)
                    quiet && r.version == skipped() -> UpdateState.Idle
                    r.apk == null || r.sums == null -> if (quiet) UpdateState.Idle else UpdateState.Failed("版本 ${r.version} 没有附带安装包或校验文件，请到发布页下载", r)
                    else -> UpdateState.Available(r)
                }
            } catch (e: IOException) {
                if (quiet) UpdateState.Idle else UpdateState.Failed("检查更新失败：" + (e.message ?: "网络错误"))
            }
        }
    }

    private fun latest(): AppRelease? {
        val req = Request.Builder().url(defaults.updateFeed)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", defaults.userAgent)
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("发布信息返回 ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("发布信息为空")
            return AppReleases.parse(body)
        }
    }

    /** Downloads and verifies [release]'s APK. */
    fun download(release: AppRelease) {
        if (job?.isActive == true && _state.value is UpdateState.Downloading) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = try {
                UpdateState.Ready(release, fetch(release))
            } catch (e: IOException) {
                UpdateState.Failed(e.message ?: "下载失败", release)
            } catch (e: SecurityException) {
                UpdateState.Failed(e.message ?: "安装包校验失败", release)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }

    private suspend fun fetch(release: AppRelease): File {
        val name = AppReleases.apkName(release.version)
        val sums = get(release.sums ?: throw IOException("没有校验文件")).use { it.body?.string().orEmpty() }
        val want = AppReleases.sumFor(sums, name) ?: throw SecurityException("校验文件里没有 $name")
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name != name) it.delete() }
        val file = File(dir, name)
        val part = File(dir, "$name.part")
        val digest = MessageDigest.getInstance("SHA-256")
        get(release.apk ?: throw IOException("没有安装包")).use { resp ->
            val body = resp.body ?: throw IOException("下载为空")
            val total = body.contentLength()
            var done = 0L
            var shown = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (currentCoroutineContext().isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > MAX_APK) throw IOException("安装包过大")
                        if (done - shown > 256 * 1024) {
                            shown = done
                            _state.value = UpdateState.Downloading(release, done, total)
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != want) {
            part.delete()
            throw SecurityException("安装包校验值不符，已删除。请稍后重试或到发布页下载")
        }
        verifyPackage(part, release)
        file.delete()
        if (!part.renameTo(file)) throw IOException("无法保存安装包")
        return file
    }

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url).header("User-Agent", defaults.userAgent).build(),
    ).execute().also { if (!it.isSuccessful) { it.close(); throw IOException("下载返回 ${it.code}") } }

    /** Same app, newer build, same signing key as the installed one. */
    private fun verifyPackage(apk: File, release: AppRelease) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: run {
            apk.delete(); throw SecurityException("安装包无法解析")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) {
            apk.delete(); throw SecurityException("安装包不是同勘（${archive.packageName}）")
        }
        if (archive.longVersionCode <= installed.longVersionCode) {
            apk.delete(); throw SecurityException("安装包的版本不比当前新")
        }
        val mine = signers(installed.signingInfo, history = true)
        val theirs = signers(archive.signingInfo, history = false)
        if (theirs.isEmpty() || theirs.none { it in mine }) {
            apk.delete()
            throw SecurityException("安装包的签名和已装的同勘不一致，没有安装。若你装的是自己编译或其他渠道的版本，请到发布页手动下载 ${release.version}")
        }
    }

    private fun signers(info: android.content.pm.SigningInfo?, history: Boolean): Set<String> {
        info ?: return emptySet()
        val sigs: Array<Signature> = when {
            info.hasMultipleSigners() -> info.apkContentsSigners
            history -> info.signingCertificateHistory
            else -> info.apkContentsSigners ?: info.signingCertificateHistory
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /**
     * Opens the system installer for a checked APK, or first the setting
     * that lets GeoCrew install apps; returns false in that case so the
     * caller can tell the user to come back and tap install again.
     */
    fun install(file: File): Boolean {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    private companion object {
        const val AUTO = "auto_check"
        const val SKIPPED = "skipped"
        const val CHECKED_AT = "checked_at"
        const val DAY = 20 * 3600 * 1000L
        const val MAX_APK = 300L * 1024 * 1024
    }
}
