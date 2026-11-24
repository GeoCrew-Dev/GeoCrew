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
import org.geocrew.feature.analysis.ANALYSIS_FEATURE_ARG
import org.geocrew.feature.analysis.ANALYSIS_TOOL_ARG
import org.geocrew.feature.analysis.AnalysisScreen
import org.geocrew.core.gis.SectionTool
import org.geocrew.feature.analysis.StereonetScreen
import org.geocrew.feature.compass.CompassScreen
import org.geocrew.feature.compass.CoordinatesScreen
import org.geocrew.feature.compass.SEEK_FEATURE_ARG
import org.geocrew.feature.compass.SeekScreen
import org.geocrew.feature.compass.ThicknessScreen
import org.geocrew.feature.compass.TARGET_FEATURE_ARG
import org.geocrew.feature.map.MapScreen
import org.geocrew.feature.record.FEATURE_ID_ARG
import org.geocrew.feature.record.FeatureDetailScreen
import org.geocrew.feature.record.RecordListScreen
import org.geocrew.feature.projects.PROJECT_ID_ARG
import org.geocrew.feature.projects.ProjectSettingsScreen
import org.geocrew.feature.projects.ProjectsScreen
import org.geocrew.feature.settings.SettingsScreen
import org.geocrew.feature.team.INVITE_CODE_ARG
import org.geocrew.feature.team.INVITE_SERVER_ARG
import org.geocrew.feature.team.TeamChatScreen
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
    const val TEAM_CHAT = "team/chat"
    const val ANALYSIS = "analysis"
    const val ANALYSIS_WITH = "analysis?$ANALYSIS_TOOL_ARG={$ANALYSIS_TOOL_ARG}&$ANALYSIS_FEATURE_ARG={$ANALYSIS_FEATURE_ARG}"
    /** Opens [toolId] with [featureId] filled into its first matching record parameter. */
    fun analysis(toolId: String, featureId: String) =
        "analysis?$ANALYSIS_TOOL_ARG=${android.net.Uri.encode(toolId)}&$ANALYSIS_FEATURE_ARG=${android.net.Uri.encode(featureId)}"
    const val PROJECTS = "projects"
    const val PROJECT_SETTINGS = "project/{$PROJECT_ID_ARG}/settings"
    fun projectSettings(id: String) = "project/${android.net.Uri.encode(id)}/settings"
    const val STEREONET = "stereonet"
    const val THICKNESS = "thickness"
    const val COORDINATES = "coordinates"
    const val SUN = "sun"
    const val SEEK = "seek?$SEEK_FEATURE_ARG={$SEEK_FEATURE_ARG}"
    const val SEEK_ANY = "seek"
    fun seek(featureId: String) = "seek?$SEEK_FEATURE_ARG=$featureId"
    const val COMPASS = "compass?$TARGET_FEATURE_ARG={$TARGET_FEATURE_ARG}"
    fun compassFor(featureId: String) = "compass?$TARGET_FEATURE_ARG=$featureId"

    /** Saved-state key the map reads to focus a feature. */
    const val FOCUS = "focus"
    /** Saved-state key the map reads to show a position, as doubles [lng, lat]. */
    const val FOCUS_AT = "focus_at"
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

/** Returns to the map and asks it to show a position. */
private fun NavHostController.showOnMap(at: org.geocrew.core.model.LngLat) {
    getBackStackEntry(Routes.MAP).savedStateHandle[Routes.FOCUS_AT] = doubleArrayOf(at.lng, at.lat)
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
            val focusAt by entry.savedStateHandle.getStateFlow<DoubleArray?>(Routes.FOCUS_AT, null)
                .collectAsStateWithLifecycle()
            MapScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenFeature = { nav.navigate(Routes.feature(it)) },
                onOpenRecords = { nav.navigate(Routes.RECORDS) { launchSingleTop = true } },
                onOpenTools = { nav.navigate(Routes.TOOLS) { launchSingleTop = true } },
                onOpenTeam = { nav.navigate(Routes.TEAM_PLAIN) { launchSingleTop = true } },
                onOpenProjects = { nav.toProjects() },
                onOpenChat = { nav.navigate(Routes.TEAM_CHAT) { launchSingleTop = true } },
                focusFeatureId = focus,
                focusPosition = focusAt?.takeIf { it.size == 2 }?.let { runCatching { org.geocrew.core.model.LngLat(it[0], it[1]) }.getOrNull() },
                onFocusHandled = {
                    entry.savedStateHandle[Routes.FOCUS] = null
                    entry.savedStateHandle[Routes.FOCUS_AT] = null
                },
            )
        }
        composable(Routes.PROJECTS) {
            ProjectsScreen(
                onOpened = { nav.navigate(Routes.MAP) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onProjectSettings = { nav.navigate(Routes.projectSettings(it)) },
            )
        }
        composable(Routes.PROJECT_SETTINGS, arguments = listOf(navArgument(PROJECT_ID_ARG) { type = NavType.StringType })) {
            ProjectSettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.STEREONET) {
            StereonetScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.ANALYSIS_WITH,
            arguments = listOf(ANALYSIS_TOOL_ARG, ANALYSIS_FEATURE_ARG).map { name ->
                navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }
            },
        ) {
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
            TeamScreen(onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(Routes.TEAM_CHAT) { launchSingleTop = true } })
        }
        composable(Routes.TEAM_CHAT) {
            TeamChatScreen(
                onBack = { nav.popBackStack() },
                onOpenFeature = { nav.navigate(Routes.feature(it)) },
                onShowPosition = { nav.showOnMap(it) },
            )
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
                onSection = { nav.navigate(Routes.analysis(SectionTool().id, it)) },
                onSeek = { nav.navigate(Routes.seek(it)) },
            )
        }
        composable(Routes.THICKNESS) {
            ThicknessScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.COORDINATES) {
            CoordinatesScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SUN) {
            org.geocrew.feature.compass.SunScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.SEEK,
            arguments = listOf(navArgument(SEEK_FEATURE_ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            SeekScreen(onBack = { nav.popBackStack() }, onOpenRecord = { nav.navigate(Routes.feature(it)) })
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
