package com.kirdev.kiromindbot.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppBlack = Color(0xFF000000)
val AppSurface = Color(0xFF121214)
val AppSurfaceAlt = Color(0xFF1E1E21)
val AppDivider = Color(0xFF2B2B30)
val AppMuted = Color(0xFF9898A1)
val AppGreen = Color(0xFF45C986)
val AppWhite = Color(0xFFF4F4F6)

private val monoScheme = darkColorScheme(
    primary = AppWhite,
    onPrimary = AppBlack,
    secondary = Color(0xFFC1C1C8),
    // Selected chips use these; Material defaults would tint them purple.
    secondaryContainer = Color(0xFF3A3A40),
    onSecondaryContainer = AppWhite,
    background = AppBlack,
    onBackground = AppWhite,
    surface = AppSurface,
    onSurface = AppWhite,
    surfaceVariant = AppSurfaceAlt,
    onSurfaceVariant = AppMuted,
    outline = AppDivider
)

@Composable
fun KiroMindTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = monoScheme, typography = Typography(), content = content)
}
