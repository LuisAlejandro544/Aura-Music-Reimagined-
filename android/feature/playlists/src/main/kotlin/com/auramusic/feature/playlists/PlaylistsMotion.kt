package com.auramusic.feature.playlists

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * Rota con resorte el icono disparador (`MoreVert`) al desplegar el menú de opciones en playlists y artistas.
 */
@Composable
internal fun rememberPlaylistsMenuTriggerRotation(expanded: Boolean): Float {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(
            dampingRatio = 0.74f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "playlists_menu_trigger_rotation",
    )
    return rotation
}

/**
 * Entrada en cascada basada en `graphicsLayer` para los ítems de `DropdownMenu`
 * sin provocar saltos de medida del popup.
 */
@Composable
internal fun AuraPlaylistsMenuItemReveal(
    index: Int,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (index > 0) {
            delay((index * 18L).coerceAtMost(90L))
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 155,
                easing = FastOutSlowInEasing,
            ),
        )
    }
    Box(
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            translationY = (1f - p) * -14f
            val scale = 0.94f + 0.06f * p
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.88f, 0f)
        },
    ) {
        content()
    }
}
