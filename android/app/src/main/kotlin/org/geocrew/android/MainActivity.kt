package org.geocrew.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.geocrew.core.ui.GeoCrewTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GeoCrewTheme {
                GeoCrewNavHost()
                org.geocrew.feature.settings.UpdatePrompt()
            }
        }
    }
}
