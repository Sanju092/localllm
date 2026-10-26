package com.localllm.server.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF4ADE80)
val Amber = Color(0xFFFBBF24)
val Red = Color(0xFFF87171)

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF052E16),
    secondary = Color(0xFF60A5FA),
    background = Color(0xFF0F1115),
    surface = Color(0xFF0F1115),
    surfaceVariant = Color(0xFF1A1D24),
    surfaceContainer = Color(0xFF171A20),
    onSurface = Color(0xFFE5E7EB),
    onSurfaceVariant = Color(0xFF9CA3AF),
    error = Red,
)

@Composable
fun LocalLLMServerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
