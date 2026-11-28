package org.geocrew.android

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** How the app is themed. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The app's own settings, kept in its preferences. They are Compose state, so
 * the screens change as soon as a setting does.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("geocrew", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: ThemeMode.SYSTEM)
        private set

    var dynamicColor by mutableStateOf(prefs.getBoolean(DYNAMIC, true))
        private set

    /** The user chose to keep projects in the app's own folder rather than give all-files access. */
    var ownFolderChosen by mutableStateOf(prefs.getBoolean(OWN_FOLDER, false))
        private set

    /** The permission screen has asked for location once; it does not ask again by itself. */
    var locationAsked by mutableStateOf(prefs.getBoolean(LOCATION_ASKED, false))
        private set

    /** The kind of feature the add sheet chose last: point, line or area (ADR 0017). */
    var addKind by mutableStateOf(LayerKind.entries.firstOrNull { it.name == prefs.getString(ADD_KIND, null) } ?: LayerKind.POINT)
        private set

    fun changeAddKind(kind: LayerKind) {
        addKind = kind
        prefs.edit { putString(ADD_KIND, kind.name) }
    }

    /** The permission screen has dealt with notifications: asked for them, or skipped them (the first track asks). */
    var notificationsAsked by mutableStateOf(prefs.getBoolean(NOTIFICATIONS_ASKED, false))
        private set

    /** The permission screen has dealt with background running: asked for it, or skipped it (the first track asks). */
    var batteryAsked by mutableStateOf(prefs.getBoolean(BATTERY_ASKED, false))
        private set

    /** The phone's own question about background running has come up once; no track brings it up again. */
    var batteryPrompted by mutableStateOf(prefs.getBoolean(BATTERY_PROMPTED, false))
        private set

    /** The permission screen has asked for the camera once; a photo asks again when taken. */
    var cameraAsked by mutableStateOf(prefs.getBoolean(CAMERA_ASKED, false))
        private set

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit { putString(THEME, mode.name) }
    }

    fun setDynamic(on: Boolean) {
        dynamicColor = on
        prefs.edit { putBoolean(DYNAMIC, on) }
    }

    fun chooseOwnFolder() {
        ownFolderChosen = true
        prefs.edit { putBoolean(OWN_FOLDER, true) }
    }

    /** Whether the map keeps north up: two fingers no longer turn it. */
    var rotationLocked by mutableStateOf(prefs.getBoolean(ROTATION_LOCKED, false))
        private set

    fun lockRotation(on: Boolean) {
        rotationLocked = on
        prefs.edit { putBoolean(ROTATION_LOCKED, on) }
    }

    /** Whether drawing snaps to the vertices and edges of features near the crosshair. */
    var snapping by mutableStateOf(prefs.getBoolean(SNAPPING, true))
        private set

    fun changeSnapping(on: Boolean) {
        snapping = on
        prefs.edit { putBoolean(SNAPPING, on) }
    }

    /** The tools on the map's toolbar, in order (MapTool); the default until the user changes it. */
    var toolbar by mutableStateOf(prefs.getString(TOOLBAR, null)?.split(',')?.mapNotNull(MapTool::of)?.distinct() ?: MapTool.DEFAULT)
        private set

    fun changeToolbar(tools: List<MapTool>) {
        toolbar = tools.distinct()
        prefs.edit { putString(TOOLBAR, toolbar.joinToString(",") { it.id }) }
    }

    /** The most the map's tile cache may hold, in MB (MapCache). */
    var mapCacheMb by mutableStateOf(prefs.getInt(MAP_CACHE, MapCache.DEFAULT_MB))
        private set

    fun changeMapCache(mb: Int) {
        mapCacheMb = mb
        prefs.edit { putInt(MAP_CACHE, mb) }
    }

    /** How the file manager lists a folder. */
    var fileSort by mutableStateOf(FileSort.entries.firstOrNull { it.name == prefs.getString(FILE_SORT, null) } ?: FileSort.NAME)
        private set

    fun sortFilesBy(sort: FileSort) {
        fileSort = sort
        prefs.edit { putString(FILE_SORT, sort.name) }
    }

    /** CRSs picked lately (AUTH:CODE), newest first, for the CRS picker. */
    var recentCrs by mutableStateOf(prefs.getString(RECENT_CRS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty())
        private set

    /** Puts [srs] first among the recently used; definitions (WKT, PROJ strings) are not kept. */
    fun usedCrs(srs: String) {
        if (!Regex("[A-Za-z0-9_]+:[A-Za-z0-9_]+").matches(srs)) return
        recentCrs = (listOf(srs) + recentCrs.filterNot { it.equals(srs, ignoreCase = true) }).take(RECENT_CRS_KEPT)
        prefs.edit { putString(RECENT_CRS, recentCrs.joinToString(",")) }
    }

    /** Which Tianditu map project [projectId] shows (img, vec or ter); imagery by default. */
    fun basemapKind(projectId: String): String = prefs.getString(BASEMAP_KIND + projectId, null) ?: "img"

    /** Whether the map labels each feature of a team's project [projectId] with who last edited it. */
    fun editorsShown(projectId: String): Boolean = prefs.getBoolean(EDITORS_SHOWN + projectId, false)

    fun showEditors(projectId: String, on: Boolean) {
        prefs.edit { putBoolean(EDITORS_SHOWN + projectId, on) }
    }

    fun setBasemapKind(projectId: String, kind: String) {
        prefs.edit { putString(BASEMAP_KIND + projectId, kind) }
    }

    fun markLocationAsked() {
        locationAsked = true
        prefs.edit { putBoolean(LOCATION_ASKED, true) }
    }

    fun markNotificationsAsked() {
        notificationsAsked = true
        prefs.edit { putBoolean(NOTIFICATIONS_ASKED, true) }
    }

    fun markBatteryAsked() {
        batteryAsked = true
        prefs.edit { putBoolean(BATTERY_ASKED, true) }
    }

    fun markBatteryPrompted() {
        batteryPrompted = true
        prefs.edit { putBoolean(BATTERY_PROMPTED, true) }
    }

    fun markCameraAsked() {
        cameraAsked = true
        prefs.edit { putBoolean(CAMERA_ASKED, true) }
    }

    /**
     * The layer (by id) that points of project [projectId] go into without
     * asking, as the user chose "remember"; null to ask each time. Kept on this
     * phone only: it is the user's habit, not the project's.
     */
    fun placingLayer(projectId: String): String? = prefs.getString(PLACING_LAYER + projectId, null)

    fun setPlacingLayer(projectId: String, layerId: String?) {
        prefs.edit { if (layerId == null) remove(PLACING_LAYER + projectId) else putString(PLACING_LAYER + projectId, layerId) }
    }

    private companion object {
        const val THEME = "theme"
        const val PLACING_LAYER = "placing_layer:"
        const val DYNAMIC = "dynamic_color"
        const val OWN_FOLDER = "own_folder"
        const val LOCATION_ASKED = "location_asked"
        const val CAMERA_ASKED = "camera_asked"
        const val NOTIFICATIONS_ASKED = "notifications_asked"
        const val ADD_KIND = "add_kind"
        const val BATTERY_ASKED = "battery_asked"
        const val BATTERY_PROMPTED = "battery_prompted"
        const val BASEMAP_KIND = "basemap_kind:"
        const val EDITORS_SHOWN = "editors_shown:"
        const val ROTATION_LOCKED = "rotation_locked"
        const val SNAPPING = "snapping"
        const val RECENT_CRS = "recent_crs"
        const val RECENT_CRS_KEPT = 8
        const val FILE_SORT = "file_sort"
        const val TOOLBAR = "toolbar"
        const val MAP_CACHE = "map_cache_mb"
    }
}

/**
 * The app's language: the system's by default; Chinese for Chinese, English for
 * everything else. Android 13 and later can set it for the app alone.
 */
object AppLanguage {
    enum class Choice(val tag: String) { SYSTEM(""), CHINESE("zh"), ENGLISH("en") }

    val canChoose: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun current(context: Context): Choice {
        if (!canChoose) return Choice.SYSTEM
        val tag = context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language
        return Choice.entries.firstOrNull { it.tag.isNotEmpty() && it.tag == tag } ?: Choice.SYSTEM
    }

    /** Sets the app's language; Android then makes the screens again in it. */
    fun set(context: Context, choice: Choice) {
        if (!canChoose) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (choice == Choice.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(choice.tag)
    }

    /** The language the app speaks now: "zh" or "en", for names it writes into new projects. */
    fun speaking(context: Context): String =
        if (context.resources.configuration.locales[0]?.language == "zh") "zh" else "en"
}
