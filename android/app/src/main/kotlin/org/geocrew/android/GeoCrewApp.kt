package org.geocrew.android

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.geocrew.core.data.di.ApplicationScope
import org.geocrew.core.data.repo.BuildDefaults
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.data.repo.StorageMaintenance
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.map.MapCache
import org.geocrew.core.map.TileNetwork
import org.maplibre.android.MapLibre
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class GeoCrewApp : Application() {
    @Inject lateinit var teamSession: TeamSession
    @Inject lateinit var storage: StorageMaintenance
    @Inject @field:ApplicationScope lateinit var appScope: CoroutineScope
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        TileNetwork.install(userAgent())
        // Keeps the current project in sync with its workspace, if it has one.
        teamSession.start()
        appScope.launch { runCatching { storage.sweepPhotos() } }
        appScope.launch {
            settings.settings.map { it.mapCacheMb }.distinctUntilChanged().collect { MapCache.setSize(this@GeoCrewApp, it) }
        }
    }
}

internal fun userAgent() = "GeoCrew/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; +https://github.com/GeoCrew-Dev)"

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun buildDefaults() = BuildDefaults(tiandituKey = BuildConfig.DEV_TIANDITU_KEY, userAgent = userAgent())
}
