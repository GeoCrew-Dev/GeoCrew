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
import androidx.navigation.navDeepLink
import org.geocrew.core.network.Invite
import org.geocrew.feature.analysis.AnalysisScreen
import org.geocrew.feature.analysis.StereonetScreen
import org.geocrew.feature.compass.CompassScreen
import org.geocrew.feature.compass.TARGET_FEATURE_ARG
import org.geocrew.feature.map.MapScreen
import org.geocrew.feature.record.FEATURE_ID_ARG
import org.geocrew.feature.record.FeatureDetailScreen
import org.geocrew.feature.record.RecordListScreen
import org.geocrew.feature.projects.ProjectsScreen
import org.geocrew.feature.settings.SettingsScreen
import org.geocrew.feature.team.INVITE_CODE_ARG
import org.geocrew.feature.team.INVITE_SERVER_ARG
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
    /** Optional arguments come from an invite link; plain navigation uses "team". */
    const val TEAM = "team?$INVITE_SERVER_ARG={$INVITE_SERVER_ARG}&$INVITE_CODE_ARG={$INVITE_CODE_ARG}"
    const val TEAM_PLAIN = "team"
    const val ANALYSIS = "analysis"
    const val PROJECTS = "projects"
    const val STEREONET = "stereonet"
    const val COMPASS = "compass?$TARGET_FEATURE_ARG={$TARGET_FEATURE_ARG}"
    fun compassFor(featureId: String) = "compass?$TARGET_FEATURE_ARG=$featureId"

    /** Saved-state key the map reads to focus a feature. */
    const val FOCUS = "focus"
}

/** Back to the project list, which is always at the bottom of the stack. */
private fun NavHostController.toProjects() {
    if (!popBackStack(Routes.PROJECTS, inclusive = false)) navigate(Routes.PROJECTS)
}

/** Returns to the map and asks it to show [featureId]. */
private fun NavHostController.showOnMap(featureId: String) {
    getBackStackEntry(Routes.MAP).savedStateHandle[Routes.FOCUS] = featureId
    popBackStack(Routes.MAP, inclusive = false)
}

@Composable
fun GeoCrewNavHost() {
    val nav = rememberNavController()
    // Everything happens inside a project, so the app starts at the project list.
    NavHost(navController = nav, startDestination = Routes.PROJECTS) {
        composable(Routes.MAP) { entry ->
            val focus by entry.savedStateHandle.getStateFlow<String?>(Routes.FOCUS, null)
                .collectAsStateWithLifecycle()
            MapScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenFeature = { nav.navigate(Routes.feature(it)) },
                onOpenRecords = { nav.navigate(Routes.RECORDS) { launchSingleTop = true } },
                onOpenTools = { nav.navigate(Routes.TOOLS) { launchSingleTop = true } },
                onOpenTeam = { nav.navigate(Routes.TEAM_PLAIN) { launchSingleTop = true } },
                onOpenProjects = { nav.toProjects() },
                focusFeatureId = focus,
                onFocusHandled = { entry.savedStateHandle[Routes.FOCUS] = null },
            )
        }
        composable(Routes.PROJECTS) {
            ProjectsScreen(
                onOpened = { nav.navigate(Routes.MAP) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }
        composable(Routes.STEREONET) {
            StereonetScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ANALYSIS) {
            AnalysisScreen(onBack = { nav.popBackStack() }, onShowOnMap = { nav.popBackStack(Routes.MAP, inclusive = false) })
        }
        composable(
            Routes.TEAM,
            arguments = listOf(INVITE_SERVER_ARG, INVITE_CODE_ARG).map { name ->
                navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }
            },
            // Invite QR codes and shared links (protocol v0 §2) open the join form, filled in.
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "${Invite.SCHEME}://${Invite.HOST}?$INVITE_SERVER_ARG={$INVITE_SERVER_ARG}&$INVITE_CODE_ARG={$INVITE_CODE_ARG}"
                },
            ),
        ) {
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
                onProjects = { nav.toProjects() },
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
