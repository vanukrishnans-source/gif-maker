package com.vanu.gifmaker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Pink = Color(0xFFFF5C9A)
val Teal = Color(0xFF2EC4B6)
val Amber = Color(0xFFFFB84D)
val Danger = Color(0xFFFF5C7A)
val SurfaceDark = Color(0xFF0D0D0F)
val CardDark = Color(0xFF1A1A1F)
val CardElevated = Color(0xFF24242B)
val TextPrimary = Color(0xFFF2F2F5)
val TextSecondary = Color(0xFFA0A0AB)

private val DarkColors = darkColorScheme(
    primary = Pink,
    onPrimary = Color.Black,
    secondary = Teal,
    onSecondary = Color.Black,
    background = SurfaceDark,
    onBackground = TextPrimary,
    surface = CardDark,
    onSurface = TextPrimary,
    surfaceVariant = CardElevated,
    onSurfaceVariant = TextSecondary,
    secondaryContainer = Color(0xFF4A2335),
    onSecondaryContainer = TextPrimary,
    error = Danger,
    onError = Color.White,
)

@Composable
fun GifMakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
