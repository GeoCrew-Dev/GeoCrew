package org.geocrew.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Earthy palette: rock brown primary, vegetation green secondary. */
private val Light = lightColorScheme(
    primary = Color(0xFF8B4A1C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC9),
    onPrimaryContainer = Color(0xFF331200),
    secondary = Color(0xFF4F6538),
    secondaryContainer = Color(0xFFD1EBB2),
    tertiary = Color(0xFF1D6A8A),
    surface = Color(0xFFFFFBF8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB68D),
    onPrimary = Color(0xFF532200),
    primaryContainer = Color(0xFF6E3507),
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFB5CF98),
    secondaryContainer = Color(0xFF384D23),
    tertiary = Color(0xFF8AD0F3),
)

/** Colours used on the map, shared so legends and layers agree. */
object MapColors {
    const val POINT = "#D84315"
    const val GEO_POINT = "#6A1B9A"
    const val LINE = "#1565C0"
    const val POLYGON = "#2E7D32"
    const val TRACK = "#F9A825"
    /** Measured sections: the traverse line. */
    const val SECTION = "#795548"
    /** Geological routes: the line joining their points. */
    const val ROUTE = "#00838F"
    const val SELECTED = "#00E5FF"
    /** Geological contacts and faults (line_style), as on a printed geological map. */
    const val CONTACT = "#212121"
    const val FAULT = "#D32F2F"
    /** Kilometre grid lines. */
    const val GRID = "#5C6BC0"
    const val SHEET = "#C2185B"
    const val ME = "#1E88E5"
    const val TEAM = "#43A047"
    const val SKETCH = "#FF1744"
    /** Ring around open tasks handed to this device's member. */
    const val MY_TASK = "#FF6F00"
    /** Strike-and-dip symbols. */
    const val ATTITUDE = "#212121"
}

@Composable
fun GeoCrewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
