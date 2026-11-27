package org.geocrew.android

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.geocrew.storage.Projects

/** The app's only activity (ADR 0016): every screen is a Compose destination inside it. */
class MainActivity : ComponentActivity() {
    private val graph get() = (application as GeoCrewApplication).graph

    // Counts returns from elsewhere (the system settings), so screens read permissions again.
    private var resumed by mutableIntStateOf(0)

    // Files another app opened with GeoCrew or shared to it, until the app has taken them in.
    private var received by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh start: brought back after the system stopped it, the files were taken in already.
        if (savedInstanceState == null) received = receivedUris(intent)
        setContent { GeoCrewTheme(graph.settings) { App(graph, resumed, received, onReceived = { received = emptyList() }) } }
    }

    // Running already (one instance: singleTask), the app takes what another app sends now.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receivedUris(intent).takeIf { it.isNotEmpty() }?.let { received = it }
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
    // The file manager, at [folder] when given, else at the GeoCrew folder.
    data class Files(val folder: String = "") : Screen { override val key = if (folder.isEmpty()) "files" else "files:$folder" }
    data class Project(val name: String) : Screen { override val key = "project:$name" }
    data class ProjectSettings(val name: String) : Screen { override val key = "project-settings:$name" }
    data class Layers(val name: String) : Screen { override val key = "project-layers:$name" }

    // The layer's name comes last: it may hold any character, the project's no "/".
    data class Table(val name: String, val layer: String) : Screen { override val key = "project-table:$name/$layer" }

    companion object {
        fun of(key: String): Screen = when {
            key.startsWith("project:") -> Project(key.removePrefix("project:"))
            key.startsWith("project-settings:") -> ProjectSettings(key.removePrefix("project-settings:"))
            key.startsWith("project-layers:") -> Layers(key.removePrefix("project-layers:"))
            key.startsWith("project-table:") -> key.removePrefix("project-table:").split("/", limit = 2).let { Table(it[0], it.getOrElse(1) { "" }) }
            key == Permissions.key -> Permissions
            key == Settings.key -> Settings
            key == About.key -> About
            key == "files" -> Files()
            key.startsWith("files:") -> Files(key.removePrefix("files:"))
            else -> Home
        }
    }
}

/**
 * The screens as a stack, the top one shown: the permission screen first when
 * something is missing, then the projects; a project opens straight into its map.
 */
@Composable
private fun App(graph: AppGraph, resumed: Int, received: List<Uri>, onReceived: () -> Unit) {
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

    // Back to the project's map, with what it should show.
    fun backToMap(zoom: Zoom) {
        graph.zoomTo = zoom
        val map = stack.indexOfLast { Screen.of(it) is Screen.Project }
        if (map >= 0) stack = stack.take(map + 1)
    }

    // Files another app opened with GeoCrew or shared to it (once the permissions are settled): copied into
    // the GeoCrew folder, as GDAL reads files by path, and shown there, where they can go into a project.
    var receiving by remember { mutableStateOf(false) }
    LaunchedEffect(received, top is Screen.Permissions) {
        if (received.isEmpty() || top is Screen.Permissions) return@LaunchedEffect
        val folder = File(graph.root, Projects.IMPORTS)
        receiving = true
        val got = try {
            withContext(Dispatchers.IO) { receive(context, received, folder) }
        } finally {
            receiving = false
        }
        val words = listOfNotNull(
            context.resources.getQuantityString(R.plurals.files_received, got.files.size, got.files.size).takeIf { got.files.isNotEmpty() },
            context.resources.getQuantityString(R.plurals.files_receive_failed, got.failed, got.failed).takeIf { got.failed > 0 },
        )
        graph.notice = words.joinToString(" · ")
        val screen = Screen.Files(folder.path)
        if (stack.last() != screen.key) push(screen)
        // Last, and with nothing after it to wait on: taking them clears [received], which starts this effect over.
        onReceived()
    }
    if (receiving) ReceivingDialog()

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
                onFiles = { push(Screen.Files()) },
                onAbout = { push(Screen.About) },
            )
            Screen.Settings -> SettingsScreen(graph, onBack = ::pop, onAbout = { push(Screen.About) })
            Screen.About -> AboutScreen(onBack = ::pop)
            is Screen.Files -> FilesScreen(
                graph, onBack = ::pop,
                start = screen.folder.ifEmpty { null },
                // A project a file went into, from the file manager opened at home: its map, with home under it.
                onOpenProject = { stack = listOf(Screen.Home.key, Screen.Project(it).key) },
            )
            is Screen.Project -> MapScreen(
                graph, screen.name, onBack = ::pop,
                onSettings = { push(Screen.Settings) },
                onProjectSettings = { push(Screen.ProjectSettings(screen.name)) },
                onLayers = { push(Screen.Layers(screen.name)) },
                onFiles = { push(Screen.Files()) },
            )
            is Screen.ProjectSettings -> ProjectSettingsScreen(graph, screen.name, onBack = ::pop)
            is Screen.Layers -> LayersScreen(
                graph, screen.name, onBack = ::pop,
                onZoom = { backToMap(Zoom(it)) },
                onTable = { push(Screen.Table(screen.name, it)) },
                onZoomTo = { backToMap(Zoom("", bounds = it)) },
                onAddFile = { push(Screen.Files()) },
            )
            is Screen.Table -> AttributeTableScreen(graph, screen.name, screen.layer, onBack = ::pop, onFeature = { backToMap(Zoom(screen.layer, it)) })
        }
    }
}

// While files from another app are copied in: a big one takes a while.
@Composable
private fun ReceivingDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Spacer(Modifier.width(20.dp))
                Text(stringResource(R.string.files_receiving), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
