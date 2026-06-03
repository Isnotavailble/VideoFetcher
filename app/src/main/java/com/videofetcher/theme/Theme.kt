package com.videofetcher.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFFFEFEFE), // Slightly off-white to bypass OS Force-Dark inversion
    onPrimary = Color(0xFF010101), // Slightly off-black
    background = Color.Black,
    surface = Color(0xFF121212),
    onSurface = Color(0xFFFEFEFE),
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color(0xFFFEFEFE),
    errorContainer = Color(0xFF1E1E1E), // Fixes the pink error card
    error = Color.Red,
    tertiary = Color(0xFF00C853), // Green for success/completion
    surfaceTint = Color.Transparent // Disables the default purple elevation glow entirely!
)

private val LightColorPalette = lightColorScheme(
    background = Color(0xFFFEFEFE), // Slightly off-white to bypass OS Force-Dark inversion
    surface = Color(0xFFFEFEFE),
    onSurface = Color(0xFF010101), // Slightly off-black
    primary = Color(0xFF010101),
    onPrimary = Color(0xFFFEFEFE),
    secondaryContainer = Color(0xFFF5F5F5),
    error = Color.Red,
    tertiary = Color(0xFF00C853)
)

@Composable
fun VideoFetcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorPalette else LightColorPalette

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}