package org.geocrew.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** The app's only activity (ADR 0016): every screen is a Compose destination inside it. */
class MainActivity : ComponentActivity() {
    private val graph get() = (application as GeoCrewApplication).graph
    private var hasAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasAccess = graph.hasAllFilesAccess
        setContent { GeoCrewTheme { App(graph, hasAccess, onGrant = ::askForAllFilesAccess) } }
    }

    // The user may come back from the system settings with all-files access.
    override fun onResume() {
        super.onResume()
        hasAccess = graph.hasAllFilesAccess
    }

    private fun askForAllFilesAccess() {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)))
    }
}

@Composable
private fun App(graph: AppGraph, hasAccess: Boolean, onGrant: () -> Unit) {
    var ownFolder by remember { mutableStateOf(graph.ownFolderChosen) }
    if (hasAccess || ownFolder) {
        ProjectsRoute(graph)
    } else {
        StorageScreen(onGrant = onGrant, onOwnFolder = {
            graph.ownFolderChosen = true
            ownFolder = true
        })
    }
}

/** Material 3 with the phone's own colours (Android 12+, ADR 0002). */
@Composable
fun GeoCrewTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        isSystemInDarkTheme() -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
