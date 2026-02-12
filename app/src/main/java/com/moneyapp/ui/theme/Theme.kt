package com.moneyapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Ocean,
    onPrimary = Ink,
    secondary = Amber,
    onSecondary = Ink,
    surface = Mist,
    surfaceVariant = MistDark,
    onSurface = Ink,
    onSurfaceVariant = Slate
)

private val DarkColors = darkColorScheme(
    primary = Ocean,
    onPrimary = Ink,
    secondary = Amber,
    onSecondary = Ink,
    surface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFF1E293B),
    onSurface = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF94A3B8)
)

@Composable
fun MoneyAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content
    )
}
