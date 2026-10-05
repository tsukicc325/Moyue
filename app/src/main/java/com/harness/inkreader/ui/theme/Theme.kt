package com.harness.inkreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = InkBlue,
    onPrimary = PaperSurface,
    primaryContainer = InkBlueContainer,
    onPrimaryContainer = InkBlue,
    secondary = AccentWarm,
    onSecondary = PaperSurface,
    background = PaperBackground,
    onBackground = InkText,
    surface = PaperSurface,
    onSurface = InkText,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = InkTextSecondary,
    outline = PaperOutline,
)

private val DarkColors = darkColorScheme(
    primary = NightPrimary,
    onPrimary = NightBackground,
    primaryContainer = NightPrimaryContainer,
    onPrimaryContainer = NightPrimary,
    secondary = NightAccentWarm,
    onSecondary = NightBackground,
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightTextSecondary,
    outline = NightOutline,
)

@Composable
fun InkReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = InkTypography,
        content = content,
    )
}
