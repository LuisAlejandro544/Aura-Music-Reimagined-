package com.auramusic.feature.player

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
 * Anima la rotación del botón de tres puntos (`MoreVert`) al desplegar o cerrar un menú
 * mediante física de resorte rápida y sin rebote excesivo.
 */
@Composable
internal fun rememberPlayerMenuTriggerRotation(expanded: Boolean): Float {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(
            dampingRatio = 0.74f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "player_menu_trigger_rotation",
    )
    return rotation
}

/**
 * Revela cada fila de un `DropdownMenu` en cascada rápida usando únicamente `graphicsLayer`
 * (alpha, traslación vertical y micro-escala) para evitar parpadeos de ancho/alto en el popup.
 */
@Composable
internal fun AuraPlayerMenuItemReveal(
    index: Int,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (index > 0) {
            delay((index * 18L).coerceAtMost(108L))
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
