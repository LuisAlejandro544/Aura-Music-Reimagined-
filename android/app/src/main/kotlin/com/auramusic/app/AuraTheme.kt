package com.auramusic.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.auramusic.core.model.AppAccentColor

/**
 * Escala tipográfica propia y equilibrada para Aura Music.
 * Garantiza legibilidad cómoda y evita desbordamientos independientemente del tamaño
 * de fuente configurado en los ajustes del teléfono.
 */
private val AuraTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.5.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * Sistema de tema dinámico Material 3 de Aura Music.
 * Construye una paleta oscura rica en matices y tonalidades derivadas del color
 * que el usuario elija en Ajustes, fijando `fontScale = 1.0f` para mantener los diseños estables.
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

    val currentDensity = LocalDensity.current
    val controlledDensity = Density(
        density = currentDensity.density,
        fontScale = 1.0f,
    )

    CompositionLocalProvider(LocalDensity provides controlledDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AuraTypography,
            content = content,
        )
    }
}
