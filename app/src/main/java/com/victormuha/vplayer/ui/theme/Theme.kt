package com.victormuha.vplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFCC00),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A3000),
    onPrimaryContainer = Color(0xFFFFE082),
    secondary = Color(0xFFE0C875),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1AFFFFFF.toInt()),
    onSurfaceVariant = Color(0xFFB3FFFFFF.toInt()),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    error = Color(0xFFFF5252)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFFF9900),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFFFFE0B2),
    onPrimaryContainer = Color(0xFF4A3400),
    secondary = Color(0xFF7A6A3A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    error = Color(0xFFC62828)
)

private val Typography = Typography()

@Composable
fun VplayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
