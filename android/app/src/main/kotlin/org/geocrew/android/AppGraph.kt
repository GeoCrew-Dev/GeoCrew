package org.geocrew.android

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.storage.NativeData
import org.geocrew.storage.Projects
import org.geocrew.storage.Workspace

/** The application: it keeps the app's parts for as long as the process lives. */
class GeoCrewApplication : Application() {
    val graph by lazy { AppGraph(this) }
}

/**
 * The app's parts, put together by hand (ADR 0016: no injection framework): the
 * action registry every screen goes through (ADR 0010) and the workspace of
 * projects. Projects live in `/sdcard/GeoCrew` when the app has all-files access
 * (ADR 0002); without it, in the app's own folder, which goes when the app is
 * uninstalled.
 */
class AppGraph(private val app: Application) {
    val registry = ActionRegistry()
    private val prefs = app.getSharedPreferences("geocrew", Context.MODE_PRIVATE)
    private var workspace: Workspace? = null

    val hasAllFilesAccess: Boolean get() = Environment.isExternalStorageManager()

    /** The user chose to keep projects in the app's own folder for now. */
    var ownFolderChosen: Boolean
        get() = prefs.getBoolean(OWN_FOLDER, false)
        set(value) = prefs.edit { putBoolean(OWN_FOLDER, value) }

    /** The GeoCrew folder. It is fixed once [start] has run, until the app restarts. */
    val root: File
        get() = workspaceRoot ?: if (hasAllFilesAccess) {
            @Suppress("DEPRECATION") // with all-files access the shared storage is read by path
            File(Environment.getExternalStorageDirectory(), "GeoCrew")
        } else {
            File(app.getExternalFilesDir(null), "GeoCrew")
        }
    private var workspaceRoot: File? = null

    /** Gets the native library and the workspace ready, once. It blocks: call it off the main thread. */
    @Synchronized
    fun start(): Workspace {
        workspace?.let { return it }
        NativeData.setup(app)
        val folder = root
        val ws = Workspace(Projects(folder, File(app.noBackupFilesDir, "journals")))
        ws.actions.forEach(registry::register)
        workspaceRoot = folder
        workspace = ws
        return ws
    }

    /** Runs an action for the user, off the main thread. */
    suspend fun invoke(action: String, params: JsonObject = JsonObject(emptyMap())): Outcome =
        withContext(Dispatchers.IO) { registry.invoke(Caller.USER, action, params) }

    private companion object {
        const val OWN_FOLDER = "own_folder"
    }
}
