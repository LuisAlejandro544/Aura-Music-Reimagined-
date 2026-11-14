package com.auramusic.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.auramusic.core.model.AppAccentColor

/**
 * Sistema de tema dinámico Material 3 de Aura Music.
 * Construye una paleta oscura rica en matices y tonalidades derivadas del color
 * que el usuario elija en Ajustes, evitando el minimalismo plano.
 */
@Composable
fun AuraMusicTheme(
    accentColor: AppAccentColor,
    content: @Composable () -> Unit,
) {
    val primary = Color(accentColor.primaryArgb)
    val onPrimary = Color(accentColor.onPrimaryArgb)
    val primaryContainer = Color(accentColor.primaryContainerArgb)
    val onPrimaryContainer = Color(accentColor.onPrimaryContainerArgb)
    val secondary = Color(accentColor.secondaryArgb)
    val tintedSurface = Color(accentColor.surfaceTintedArgb)

    val colorScheme = darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onPrimary,
        secondaryContainer = primaryContainer.copy(alpha = 0.72f),
        onSecondaryContainer = onPrimaryContainer,
        background = Color(0xFF0C0B10),
        onBackground = Color(0xFFF3F1F8),
        surface = Color(0xFF121118),
        onSurface = Color(0xFFF3F1F8),
        surfaceVariant = tintedSurface,
        onSurfaceVariant = Color(0xFFB5B0C4),
        surfaceContainerLow = Color(0xFF16151E),
        surfaceContainer = tintedSurface,
        surfaceContainerHigh = Color(0xFF211F2D),
        surfaceContainerHighest = Color(0xFF2B283A),
        outline = Color(0xFF47435A),
        outlineVariant = Color(0xFF2F2C3D),
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
