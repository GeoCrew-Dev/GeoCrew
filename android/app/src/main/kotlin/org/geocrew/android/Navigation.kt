package org.geocrew.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.geocrew.feature.compass.CompassScreen
import org.geocrew.feature.compass.TARGET_FEATURE_ARG
import org.geocrew.feature.map.MapScreen
import org.geocrew.feature.record.FEATURE_ID_ARG
import org.geocrew.feature.record.FeatureDetailScreen
import org.geocrew.feature.record.RecordListScreen
import org.geocrew.feature.settings.SettingsScreen
import org.geocrew.feature.team.TeamScreen
import org.geocrew.feature.transfer.TransferScreen

/** Top-level destinations. New features register their routes here. */
object Routes {
    const val MAP = "map"
    const val SETTINGS = "settings"
    const val RECORDS = "records"
    const val FEATURE = "feature/{$FEATURE_ID_ARG}"
    fun feature(id: String) = "feature/$id"

    const val TOOLS = "tools"
    const val TRANSFER = "transfer"
    const val TEAM = "team"
    const val COMPASS = "compass?$TARGET_FEATURE_ARG={$TARGET_FEATURE_ARG}"
    fun compassFor(featureId: String) = "compass?$TARGET_FEATURE_ARG=$featureId"

    /** Saved-state key the map reads to focus a feature. */
    const val FOCUS = "focus"
}

/** Returns to the map and asks it to show [featureId]. */
private fun NavHostController.showOnMap(featureId: String) {
    getBackStackEntry(Routes.MAP).savedStateHandle[Routes.FOCUS] = featureId
    popBackStack(Routes.MAP, inclusive = false)
}

@Composable
fun GeoCrewNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAP) {
        composable(Routes.MAP) { entry ->
            val focus by entry.savedStateHandle.getStateFlow<String?>(Routes.FOCUS, null)
                .collectAsStateWithLifecycle()
            MapScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenFeature = { nav.navigate(Routes.feature(it)) },
                onOpenRecords = { nav.navigate(Routes.RECORDS) { launchSingleTop = true } },
                onOpenTools = { nav.navigate(Routes.TOOLS) { launchSingleTop = true } },
                onOpenTeam = { nav.navigate(Routes.TEAM) { launchSingleTop = true } },
                focusFeatureId = focus,
                onFocusHandled = { entry.savedStateHandle[Routes.FOCUS] = null },
            )
        }
        composable(Routes.TEAM) {
            TeamScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.RECORDS) {
            RecordListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.feature(it)) },
                onImportExport = { nav.navigate(Routes.TRANSFER) },
            )
        }
        composable(Routes.FEATURE, arguments = listOf(navArgument(FEATURE_ID_ARG) { type = NavType.StringType })) {
            FeatureDetailScreen(
                onBack = { nav.popBackStack() },
                onShowOnMap = { nav.showOnMap(it) },
                onMeasureAttitude = { nav.navigate(Routes.compassFor(it)) },
            )
        }
        composable(Routes.TOOLS) {
            ToolsScreen(builtInTools, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) })
        }
        composable(Routes.TRANSFER) {
            TransferScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.COMPASS,
            arguments = listOf(navArgument(TARGET_FEATURE_ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val forFeature = entry.arguments?.getString(TARGET_FEATURE_ARG) != null
            CompassScreen(
                onBack = { nav.popBackStack() },
                onSaved = { id ->
                    // Measuring for an open record returns to it; a new point opens its record.
                    nav.popBackStack()
                    if (!forFeature) nav.navigate(Routes.feature(id))
                },
            )
        }
    }
}
