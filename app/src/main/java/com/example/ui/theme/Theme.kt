package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Emerald500,
    onPrimary = Slate950,
    primaryContainer = Emerald900,
    onPrimaryContainer = Emerald100,
    secondary = Sky500,
    onSecondary = Slate950,
    secondaryContainer = Sky900,
    onSecondaryContainer = Sky100,
    tertiary = Amber500,
    onTertiary = Slate950,
    tertiaryContainer = Amber900,
    onTertiaryContainer = Amber100,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate300,
    outline = Slate700,
    error = Crimson600,
    onError = Color.White,
    errorContainer = Crimson900,
    onErrorContainer = Crimson100
)

private val LightColorScheme = lightColorScheme(
    primary = Emerald600,
    onPrimary = Color.White,
    primaryContainer = Emerald100,
    onPrimaryContainer = Emerald900,
    secondary = Sky600,
    onSecondary = Color.White,
    secondaryContainer = Sky100,
    onSecondaryContainer = Sky900,
    tertiary = Amber600,
    onTertiary = Color.White,
    tertiaryContainer = Amber100,
    onTertiaryContainer = Amber900,
    background = Slate100,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate50,
    onSurfaceVariant = Slate700,
    outline = Slate300,
    error = Crimson600,
    onError = Color.White,
    errorContainer = Crimson100,
    onErrorContainer = Crimson900
)

@Composable
fun ControlAccesoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
