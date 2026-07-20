package com.adachi.lockdown.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF1E2027),
    onSurfaceVariant = Color(0xFFC4C6D0),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme()

@Composable
fun AdachiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
