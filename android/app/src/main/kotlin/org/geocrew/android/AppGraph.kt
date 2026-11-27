package org.geocrew.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.geocrew.core.action.ActionRegistry
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Outcome
import org.geocrew.core.files.FileActions
import org.geocrew.storage.NativeData
import org.geocrew.storage.Probe
import org.geocrew.storage.Projects
import org.geocrew.storage.RasterActions
import org.geocrew.storage.Workspace
import org.maplibre.android.camera.CameraPosition

/** A layer to show in full, or one feature of it ([fid]), which the map then opens. */
data class Zoom(val layer: String, val fid: Long? = null)

/** The application: it keeps the app's parts for as long as the process lives. */
class GeoCrewApplication : Application() {
    val graph by lazy { AppGraph(this) }
}

/**
 * The app's parts, put together by hand (ADR 0016: no injection framework): the
 * settings, the action registry every screen goes through (ADR 0010) and the
 * workspace of projects.
 */
class AppGraph(private val app: Application) {
    val settings = AppSettings(app)
    val registry = ActionRegistry()

    /** The GPS track being recorded or paused, whichever screen is showing. */
    val tracks = TrackRecorder(app)

    /** Where each project's map was looking, for the next visit in this session. */
    val cameras = HashMap<String, CameraPosition>()

    /** What the map shows when it comes back, as asked from the layers page or a table; it is taken once. */
    var zoomTo: Zoom? = null
    private var workspace: Workspace? = null
    private var workspaceRoot: File? = null

    /** Whether projects can go in /sdcard/GeoCrew, read and written by path (ADR 0002). */
    val hasAllFilesAccess: Boolean get() = Environment.isExternalStorageManager()

    fun hasLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Whether the permission screen has something to ask; once everything is granted it is skipped. */
    fun needsPermissions(context: Context): Boolean =
        (!hasAllFilesAccess && !settings.ownFolderChosen) || (!hasLocation(context) && !settings.locationAsked)

    /**
     * The GeoCrew folder: the shared one with all-files access, else the app's
     * own. A user who chose the app's own folder keeps it, so no project drops
     * out of sight. Fixed once [start] has run.
     */
    val root: File
        get() = workspaceRoot ?: if (settings.ownFolderChosen || !hasAllFilesAccess) {
            File(app.getExternalFilesDir(null), "GeoCrew")
        } else {
            @Suppress("DEPRECATION") // with all-files access the shared storage is read by path
            File(Environment.getExternalStorageDirectory(), "GeoCrew")
        }

    /** Gets the native library and the workspace ready, once. It blocks: call it off the main thread. */
    @Synchronized
    fun start(): Workspace {
        workspace?.let { return it }
        NativeData.setup(app)
        val folder = root
        val ws = Workspace(Projects(folder, File(app.noBackupFilesDir, "journals")))
        ws.actions.forEach(registry::register)
        // The file manager reaches all of the shared storage with all-files access, else the GeoCrew folder;
        // the open project's files stay as they are.
        @Suppress("DEPRECATION") // with all-files access the shared storage is read by path
        val storage = { if (hasAllFilesAccess) Environment.getExternalStorageDirectory() else folder }
        // Files are probed in a process of their own (ADR 0003).
        FileActions(folder, top = storage, busy = { ws.current?.folder }, probe = { Probe.inspect(app, it.path) }).actions.forEach(registry::register)
        // Rasters go under a project's layers, probed the same way first.
        RasterActions(ws, folder) { Probe.inspect(app, it.path) }.actions.forEach(registry::register)
        workspaceRoot = folder
        workspace = ws
        return ws
    }

    /** Runs an action for the user, off the main thread, once the workspace is ready. */
    suspend fun invoke(action: String, params: JsonObject = JsonObject(emptyMap())): Outcome =
        withContext(Dispatchers.IO) {
            runCatching { start() }.exceptionOrNull()?.let { return@withContext Outcome.Failed(START_FAILED, it.message ?: it.toString()) }
            registry.invoke(Caller.USER, action, params)
        }

    /**
     * Opens [project] unless it is open already: just made, back from another
     * screen, or still open when the app comes back. Done gives the project as
     * project.current describes it.
     */
    suspend fun open(project: String): Outcome {
        val current = invoke(Workspace.CURRENT)
        val name = ((current as? Outcome.Done)?.output as? JsonObject)?.get("name") as? JsonPrimitive
        return if (name?.contentOrNull == project) current else invoke(Workspace.OPEN, buildJsonObject { put("name", project) })
    }

    /** The picture of [project]'s map from when it was last left, for its card on the home screen. */
    fun thumbnail(project: String): File = File(app.filesDir, "thumbnails/$project.jpg")

    companion object {
        /** The code of an action that could not run because GeoCrew did not start. */
        const val START_FAILED = "start"
    }
}
