package com.example.firstapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Aurora,
    secondary = Coral,
    tertiary = Ember,
    background = Night,
    surface = Dusk,
    onPrimary = Ink,
    onSecondary = Ink,
    onTertiary = Ink,
    onBackground = Ice,
    onSurface = Ice
)

private val LightColorScheme = lightColorScheme(
    primary = Dusk,
    secondary = Aurora,
    tertiary = Coral,
    background = Mist,
    surface = Color(0xFFFFFFFF),
    onPrimary = Mist,
    onSecondary = Ink,
    onTertiary = Mist,
    onBackground = Ink,
    onSurface = Ink
)

@Composable
fun FirstAPPTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
