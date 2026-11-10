package dev.nova.zerotier.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NovaColors = darkColorScheme(
    primary = Color(0xFF78D6C1),
    onPrimary = Color(0xFF062D28),
    secondary = Color(0xFFB7C8F4),
    background = Color(0xFF101615),
    surface = Color(0xFF1A2220),
    surfaceVariant = Color(0xFF27312F),
    onBackground = Color(0xFFE4ECE9),
    onSurface = Color(0xFFE4ECE9),
    onSurfaceVariant = Color(0xFFA9B8B3),
    error = Color(0xFFFFB4AB),
)

@Composable
internal fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NovaColors, content = content)
}
