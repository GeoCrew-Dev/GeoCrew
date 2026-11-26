package org.geocrew.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.geocrew.nativelib.GeoCrewNative

/** The app's only activity (ADR 0016): every screen is a Compose destination inside it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GeoCrewTheme { Skeleton() } }
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

@Composable
private fun Skeleton() {
    // Loading GeoCrew-Native here shows the AAR works on the phone.
    val versions = remember { runCatching { GeoCrewNative.versions() }.getOrElse { it.toString() } }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.skeleton_status), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.native_versions, versions), style = MaterialTheme.typography.bodySmall)
        }
    }
}
