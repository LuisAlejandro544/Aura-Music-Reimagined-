package com.auramusic.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.material3.rememberDynamicColorScheme
import com.materialkolor.palette.rememberPaletteState
import com.materialkolor.palette.themeColorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Perfil cromático extraído de una carátula: color semilla principal y tinte profundo para degradados.
 */
internal data class CoverColorProfile(
    val seedColor: Color,
    val backdropTint: Color,
)

/**
 * Tinte animado de fondo para el degradado del reproductor, derivado de la carátula activa
 * o del tema configurado cuando los colores dinámicos están desactivados.
 */
val LocalCoverBackdropTint = compositionLocalOf { Color(0xFF281F44) }

/**
 * Caché en memoria LRU para no decodificar ni recalcular paletas al avanzar o retroceder canciones.
 * La clave incluye la fecha de modificación del archivo para invalidarse al cambiar carátula desde galería.
 */
private object CoverColorCache {
    private val cache = LruCache<String, CoverColorProfile>(64)

    fun get(key: String): CoverColorProfile? = cache.get(key)

    fun put(key: String, profile: CoverColorProfile) {
        cache.put(key, profile)
    }
}

private const val COVER_COLOR_ANIMATION_MS = 520

/**
 * Envuelve el Mini Reproductor y el Reproductor completo aplicando una paleta Material 3
 * generada a partir de la carátula actual (usando `androidx.palette:palette`,
 * `material-kolor-palette` y `material-kolor-material3`) con transiciones suaves animadas.
 *
 * Si [enabled] es `false` o no existe carátula válida, anima suavemente de regreso al tema
 * elegido por el usuario en Ajustes.
 */
@Composable
fun CoverDynamicTheme(
    coverPath: String?,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val baseColorScheme = MaterialTheme.colorScheme
    val paletteState = rememberPaletteState()

    val cacheKey = remember(coverPath) {
        if (coverPath.isNullOrBlank()) {
            null
        } else {
            val file = File(coverPath)
            if (file.exists() && file.isFile) {
                "${coverPath}#${file.lastModified()}"
            } else {
                null
            }
        }
    }

    var extractedProfile by remember(cacheKey, enabled) {
        mutableStateOf(
            if (enabled && cacheKey != null) CoverColorCache.get(cacheKey) else null,
        )
    }

    // Decodificación y cuantización estrictamente fuera del hilo principal.
    LaunchedEffect(cacheKey, enabled) {
        if (!enabled || cacheKey == null || coverPath.isNullOrBlank()) {
            extractedProfile = null
            return@LaunchedEffect
        }

        val cached = CoverColorCache.get(cacheKey)
        if (cached != null) {
            extractedProfile = cached
            return@LaunchedEffect
        }

        val bitmap = decodeSampledCoverBitmap(coverPath, maxEdgePx = 128)
        if (bitmap == null) {
            extractedProfile = null
            return@LaunchedEffect
        }

        // 1. Extracción rápida con AndroidX Palette (compatible desde Android 8.0 / API 26).
        val androidPaletteProfile = withContext(Dispatchers.Default) {
            extractAndroidPaletteProfile(bitmap)
        }

        // 2. Cuantización y puntuación Material con material-kolor-palette (off main thread).
        runCatching {
            paletteState.generate(bitmap.asImageBitmap())
        }
        val scoredSeedColor = paletteState.themeColorOrNull()

        val resolvedSeed = scoredSeedColor ?: androidPaletteProfile.seedColor
        val resolvedProfile = CoverColorProfile(
            seedColor = resolvedSeed,
            backdropTint = androidPaletteProfile.backdropTint,
        )
        CoverColorCache.put(cacheKey, resolvedProfile)
        extractedProfile = resolvedProfile
    }

    val activeProfile = if (enabled) extractedProfile else null
    val activeSeedColor = activeProfile?.seedColor ?: baseColorScheme.primary

    // 3. Generación completa del esquema tonal oscuro Material 3 con material-kolor-material3.
    val coverScheme = rememberDynamicColorScheme(
        seedColor = activeSeedColor,
        isDark = true,
        style = PaletteStyle.TonalSpot,
    )

    val targetColorScheme = if (activeProfile != null) {
        coverScheme
    } else {
        baseColorScheme
    }

    val targetBackdropTint = activeProfile?.backdropTint ?: baseColorScheme.primaryContainer

    val animatedColorScheme = animateColorScheme(targetColorScheme)
    val animatedBackdropTint by animateColorAsState(
        targetValue = targetBackdropTint,
        animationSpec = tween(durationMillis = COVER_COLOR_ANIMATION_MS),
        label = "coverBackdropTint",
    )

    CompositionLocalProvider(LocalCoverBackdropTint provides animatedBackdropTint) {
        MaterialTheme(
            colorScheme = animatedColorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

/**
 * Decodifica una versión reducida de la carátula en [Dispatchers.IO] para ahorrar memoria
 * en dispositivos de 32 y 64 bits sin afectar la nitidez de la cuantización de colores.
 */
private suspend fun decodeSampledCoverBitmap(
    filePath: String,
    maxEdgePx: Int,
): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxEdgePx ||
            bounds.outHeight / sampleSize > maxEdgePx
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(filePath, decodeOptions)
    }.getOrNull()
}

/**
 * Analiza las muestras cromáticas de [Palette] priorizando tonos vibrantes y fondos profundos.
 */
private fun extractAndroidPaletteProfile(bitmap: Bitmap): CoverColorProfile {
    val palette = Palette.from(bitmap)
        .maximumColorCount(16)
        .generate()

    val seedArgb = palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.darkVibrantSwatch?.rgb
        ?: palette.mutedSwatch?.rgb
        ?: palette.dominantSwatch?.rgb
        ?: 0xFFA78BFA.toInt()

    val backdropArgb = palette.darkVibrantSwatch?.rgb
        ?: palette.darkMutedSwatch?.rgb
        ?: palette.dominantSwatch?.rgb
        ?: seedArgb

    return CoverColorProfile(
        seedColor = Color(seedArgb),
        backdropTint = Color(backdropArgb),
    )
}

/**
 * Interpola suavemente cada rol relevante de [ColorScheme] para que los cambios de canción
 * o el encendido/apagado del ajuste produzcan transiciones fluidas sin saltos bruscos.
 */
@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = COVER_COLOR_ANIMATION_MS)

    val primary by animateColorAsState(target.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer")
    val secondary by animateColorAsState(target.secondary, spec, label = "secondary")
    val onSecondary by animateColorAsState(target.onSecondary, spec, label = "onSecondary")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer")
    val background by animateColorAsState(target.background, spec, label = "background")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "onBackground")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "onSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfaceVariant")
    val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, spec, label = "surfaceContainerLow")
    val surfaceContainer by animateColorAsState(target.surfaceContainer, spec, label = "surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, spec, label = "surfaceContainerHigh")
    val surfaceContainerHighest by animateColorAsState(target.surfaceContainerHighest, spec, label = "surfaceContainerHighest")
    val outline by animateColorAsState(target.outline, spec, label = "outline")
    val outlineVariant by animateColorAsState(target.outlineVariant, spec, label = "outlineVariant")

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = outline,
        outlineVariant = outlineVariant,
    )
}
