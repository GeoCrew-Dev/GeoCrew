package org.geocrew.android

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import org.geocrew.core.data.repo.BuildDefaults
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.map.TileNetwork
import org.maplibre.android.MapLibre
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class GeoCrewApp : Application() {
    @Inject lateinit var teamSession: TeamSession

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        TileNetwork.install(userAgent())
        // Keeps the current project in sync with its workspace, if it has one.
        teamSession.start()
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
