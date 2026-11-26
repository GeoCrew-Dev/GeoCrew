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

    fun markLocationAsked() {
        locationAsked = true
        prefs.edit { putBoolean(LOCATION_ASKED, true) }
    }

    private companion object {
        const val THEME = "theme"
        const val DYNAMIC = "dynamic_color"
        const val OWN_FOLDER = "own_folder"
        const val LOCATION_ASKED = "location_asked"
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
