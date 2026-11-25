package org.geocrew.core.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.tiles.BuiltInSources
import org.geocrew.core.model.tiles.TileCatalog
import org.geocrew.core.model.tiles.TileKeys
import org.geocrew.core.model.tiles.TileSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Values baked into the build. Release builds leave [tiandituKey] empty: every
 * user brings their own key. Debug builds may carry a developer key so the map
 * works out of the box while developing.
 */
data class BuildDefaults(
    val tiandituKey: String = "",
    val userAgent: String = "GeoCrew",
    /** Release feed for in-app updates (GitHub API format); empty in builds that are updated elsewhere (app stores, F-Droid, debug). */
    val updateFeed: String = "",
    val versionName: String = "",
)

data class AppSettings(
    /** Tianditu key entered by the user on this device. */
    val tiandituKey: String = "",
    /** Selected base map, see [TileCatalog]. */
    val baseSourceId: String = BuiltInSources.default.id,
    /** Tile sources the user added. */
    val customSources: List<TileSource> = emptyList(),
    /** True once the user dismissed or finished the first-run key prompt. */
    val keyPromptDone: Boolean = false,
    val coordinateFormat: CoordinateFormat = CoordinateFormat.DECIMAL,
    /** Magnetic declination correction for the compass, degrees east positive; null = automatic. */
    val declination: Float? = null,
    /** Seconds between recorded track fixes. */
    val trackIntervalSeconds: Int = 5,
    /** Minimum metres between recorded track fixes. */
    val trackMinDistanceMeters: Int = 5,
    val currentProjectId: String? = null,
    val nickname: String = "",
    /** Send this device's position to the team while the map is open. Off until the user turns it on. */
    val sharePosition: Boolean = false,
    /** Size of the map tile cache in MB; see MapCache. */
    val mapCacheMb: Int = 200,
    /** Seconds of fixes averaged when placing a point at the GPS position; 0 places it at once. */
    val pointAverageSeconds: Int = 0,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val defaults: BuildDefaults,
) {
    private val store = context.dataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val sourcesSerializer = ListSerializer(TileSource.serializer())

    private object K {
        val KEY = stringPreferencesKey("tianditu_key")
        val SOURCE = stringPreferencesKey("base_source")
        val CUSTOM = stringPreferencesKey("custom_sources")
        val KEY_PROMPT = booleanPreferencesKey("key_prompt_done")
        val COORD = stringPreferencesKey("coordinate_format")
        val DECL = floatPreferencesKey("declination")
        val DECL_AUTO = booleanPreferencesKey("declination_auto")
        val TRACK_S = intPreferencesKey("track_interval_s")
        val TRACK_M = intPreferencesKey("track_min_distance_m")
        val PROJECT = stringPreferencesKey("current_project")
        val NICK = stringPreferencesKey("nickname")
        val SHARE = booleanPreferencesKey("share_position")
        val CACHE = intPreferencesKey("map_cache_mb")
        val AVERAGE = intPreferencesKey("point_average_s")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            tiandituKey = p[K.KEY].orEmpty(),
            baseSourceId = p[K.SOURCE] ?: BuiltInSources.default.id,
            customSources = p[K.CUSTOM]?.let { runCatching { json.decodeFromString(sourcesSerializer, it) }.getOrNull() }
                .orEmpty(),
            keyPromptDone = p[K.KEY_PROMPT] ?: false,
            coordinateFormat = p[K.COORD]?.let { runCatching { CoordinateFormat.valueOf(it) }.getOrNull() }
                ?: CoordinateFormat.DECIMAL,
            declination = if (p[K.DECL_AUTO] != false) null else p[K.DECL],
            trackIntervalSeconds = p[K.TRACK_S] ?: 5,
            trackMinDistanceMeters = p[K.TRACK_M] ?: 5,
            currentProjectId = p[K.PROJECT],
            nickname = p[K.NICK].orEmpty(),
            sharePosition = p[K.SHARE] ?: false,
            mapCacheMb = p[K.CACHE] ?: 200,
            pointAverageSeconds = p[K.AVERAGE] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** Keys usable for tile requests: the user's own, else (debug builds only) the build default. */
    fun keysOf(s: AppSettings): TileKeys = TileKeys(
        mapOf(BuiltInSources.TIANDITU_KEY_ID to s.tiandituKey.ifBlank { defaults.tiandituKey }),
    )

    fun catalogOf(s: AppSettings): TileCatalog = TileCatalog(s.customSources)

    /** True when the user has not entered a key and the build provides none either. */
    fun needsTiandituKey(s: AppSettings): Boolean = keysOf(s)[BuiltInSources.TIANDITU_KEY_ID] == null

    suspend fun setTiandituKey(key: String) { store.edit { it[K.KEY] = key.trim() } }
    suspend fun setBaseSource(id: String) { store.edit { it[K.SOURCE] = id } }
    suspend fun setKeyPromptDone() { store.edit { it[K.KEY_PROMPT] = true } }

    /** Adds or replaces (same id) a user tile source. */
    suspend fun saveCustomSource(source: TileSource) {
        store.edit { p ->
            val list = p[K.CUSTOM]?.let { runCatching { json.decodeFromString(sourcesSerializer, it) }.getOrNull() }.orEmpty()
            val updated = list.filterNot { it.id == source.id } + source.copy(custom = true)
            p[K.CUSTOM] = json.encodeToString(sourcesSerializer, updated)
        }
    }

    suspend fun deleteCustomSource(id: String) {
        store.edit { p ->
            val list = p[K.CUSTOM]?.let { runCatching { json.decodeFromString(sourcesSerializer, it) }.getOrNull() }.orEmpty()
            p[K.CUSTOM] = json.encodeToString(sourcesSerializer, list.filterNot { it.id == id })
            if (p[K.SOURCE] == id) p[K.SOURCE] = BuiltInSources.default.id
        }
    }
    suspend fun setPointAverageSeconds(s: Int) { store.edit { it[K.AVERAGE] = s.coerceIn(0, 600) } }
    suspend fun setMapCacheMb(mb: Int) { store.edit { it[K.CACHE] = mb.coerceIn(10, 4000) } }
    suspend fun setCoordinateFormat(f: CoordinateFormat) { store.edit { it[K.COORD] = f.name } }
    suspend fun setDeclination(value: Float?) {
        store.edit {
            if (value == null) {
                it[K.DECL_AUTO] = true
            } else {
                it[K.DECL_AUTO] = false
                it[K.DECL] = value
            }
        }
    }
    suspend fun setTrackInterval(seconds: Int) { store.edit { it[K.TRACK_S] = seconds.coerceIn(1, 600) } }
    suspend fun setTrackMinDistance(meters: Int) { store.edit { it[K.TRACK_M] = meters.coerceIn(0, 1000) } }
    suspend fun setCurrentProject(id: String) { store.edit { it[K.PROJECT] = id } }
    suspend fun setNickname(name: String) { store.edit { it[K.NICK] = name.trim() } }
    suspend fun setSharePosition(on: Boolean) { store.edit { it[K.SHARE] = on } }
}
