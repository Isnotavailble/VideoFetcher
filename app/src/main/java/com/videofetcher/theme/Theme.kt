package com.videofetcher.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorPalette = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color.White,
    errorContainer = Color(0xFF1E1E1E), // Fixes the pink error card
    surfaceTint = Color.Transparent // Disables the default purple elevation glow entirely!
)

private val LightColorPalette = lightColorScheme(
    background = Color.White,
    surface = Color.White,
    onSurface = Color.Black,
    primary = Color.Black,
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFF5F5F5)
)

@Composable
fun VideoFetcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorPalette else LightColorPalette

    MaterialTheme(colorScheme = colorScheme, content = content)
}