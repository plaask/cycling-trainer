package io.github.cyclingtrainer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Theme preference stored in settings. */
enum class ThemeMode(val label: String) { SYSTEM("跟随设备"), LIGHT("明亮"), DARK("暗黑") }

private val LightColors = lightColorScheme(
    primary = Color(0xFF0061A4),
    secondary = Color(0xFF535F70),
    tertiary = Color(0xFF6B5778),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    secondary = Color(0xFFBAC8DA),
    tertiary = Color(0xFFD6BEE4),
)

@Composable
fun CyclingTrainerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors
    // Sync the system bars with the forced scheme so light/dark overrides look right.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
