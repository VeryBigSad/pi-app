package io.github.verybigsad.pimobile.session

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SessionLightColors = lightColorScheme(
    primary = Color(0xFF4C43C4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E0FF),
    onPrimaryContainer = Color(0xFF17105E),
    secondary = Color(0xFF006C4C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF8CF8C8),
    onSecondaryContainer = Color(0xFF002116),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F7FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE6E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
)

private val SessionDarkColors = darkColorScheme(
    primary = Color(0xFFC8C2FF),
    onPrimary = Color(0xFF251A92),
    primaryContainer = Color(0xFF3B32AA),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = Color(0xFF70DBAD),
    onSecondary = Color(0xFF003827),
    secondaryContainer = Color(0xFF005138),
    onSecondaryContainer = Color(0xFF8CF8C8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF48454E),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
)

enum class SessionThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Composable
fun SessionTheme(
    mode: SessionThemeMode = SessionThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        SessionThemeMode.SYSTEM -> isSystemInDarkTheme()
        SessionThemeMode.LIGHT -> false
        SessionThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) SessionDarkColors else SessionLightColors,
        content = content,
    )
}
