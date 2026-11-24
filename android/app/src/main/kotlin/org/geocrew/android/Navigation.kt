package org.geocrew.android

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.geocrew.feature.map.MapScreen
import org.geocrew.feature.settings.SettingsScreen

/** Top-level destinations. New features register their routes here. */
object Routes {
    const val MAP = "map"
    const val SETTINGS = "settings"
}

@Composable
fun GeoCrewNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAP) {
        composable(Routes.MAP) {
            MapScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenFeature = { },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
