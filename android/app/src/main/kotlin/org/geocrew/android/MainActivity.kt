package org.geocrew.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** The app's only activity (ADR 0016): every screen is a Compose destination inside it. */
class MainActivity : ComponentActivity() {
    private val graph get() = (application as GeoCrewApplication).graph

    // Counts returns from elsewhere (the system settings), so screens read permissions again.
    private var resumed by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GeoCrewTheme(graph.settings) { App(graph, resumed) } }
    }

    override fun onResume() {
        super.onResume()
        resumed++
    }
}

/** A screen of the app. */
private sealed interface Screen {
    val key: String

    data object Permissions : Screen { override val key = "permissions" }
    data object Home : Screen { override val key = "home" }
    data object Settings : Screen { override val key = "settings" }
    data object About : Screen { override val key = "about" }
    data class Project(val name: String) : Screen { override val key = "project:$name" }

    companion object {
        fun of(key: String): Screen = when {
            key.startsWith("project:") -> Project(key.removePrefix("project:"))
            key == Permissions.key -> Permissions
            key == Settings.key -> Settings
            key == About.key -> About
            else -> Home
        }
    }
}

/**
 * The screens as a stack, the top one shown: the permission screen first when
 * something is missing, then the projects; a project opens straight into its map.
 */
@Composable
private fun App(graph: AppGraph, resumed: Int) {
    val context = LocalContext.current
    var stack by rememberSaveable {
        mutableStateOf(listOf(if (graph.needsPermissions(context)) Screen.Permissions.key else Screen.Home.key))
    }
    val top = Screen.of(stack.last())
    fun push(screen: Screen) {
        stack = stack + screen.key
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    // The map asks about unsaved edits before it goes, so it handles back itself.
    BackHandler(enabled = stack.size > 1 && top !is Screen.Project) { pop() }

    AnimatedContent(
        targetState = top,
        transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 10 }) togetherWith fadeOut(tween(150)) },
        contentKey = { it.key },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Permissions -> PermissionScreen(graph, resumed, onDone = { stack = listOf(Screen.Home.key) })
            Screen.Home -> HomeScreen(
                graph,
                onOpen = { push(Screen.Project(it)) },
                onSettings = { push(Screen.Settings) },
                onAbout = { push(Screen.About) },
            )
            Screen.Settings -> SettingsScreen(graph, onBack = ::pop, onAbout = { push(Screen.About) })
            Screen.About -> AboutScreen(onBack = ::pop)
            is Screen.Project -> MapScreen(graph, screen.name, onBack = ::pop, onSettings = { push(Screen.Settings) })
        }
    }
}
