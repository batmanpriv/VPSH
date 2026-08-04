package com.batman.vpsh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Color0 = androidx.compose.ui.graphics.Color(0xFF06120C)

private val VpshDarkColors = darkColorScheme(
    primary = VpshPrimary,
    onPrimary = Color0,
    secondary = VpshSecondary,
    tertiary = VpshAccent,
    background = VpshBackground,
    onBackground = VpshTextPrimary,
    surface = VpshSurface,
    onSurface = VpshTextPrimary,
    surfaceVariant = VpshSurfaceAlt,
    onSurfaceVariant = VpshTextSecondary,
    error = VpshError
)

@Composable
fun VpshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VpshDarkColors,
        typography = VpshTypography,
        content = content
    )
}
