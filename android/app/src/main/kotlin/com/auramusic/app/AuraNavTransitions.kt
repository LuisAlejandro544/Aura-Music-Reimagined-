package com.auramusic.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry

/**
 * Clasificación de transiciones entre apartados de Aura Music para evitar
 * el fundido lento predeterminado (700 ms) de `NavHost` y dar respuesta física inmediata.
 */
internal enum class AuraRouteTransitionKind {
    TOP_TAB,
    MODAL_SHEET_RISE,
    SHARED_AXIS_X,
}

internal object AuraNavTransitions {
    private val SmoothDecelerateEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    private val StandardMotionEasing = FastOutSlowInEasing

    private const val TAB_DURATION_MS = 190
    private const val AXIS_X_ENTER_MS = 240
    private const val AXIS_X_EXIT_MS = 200
    private const val SHEET_ENTER_MS = 260
    private const val SHEET_EXIT_MS = 210

    private val topTabRoutes = setOf(
        AuraRoutes.LIBRARY,
        AuraRoutes.PLAYLISTS,
        AuraRoutes.SETTINGS,
    )

    private val audioSuiteRoutes = setOf(
        AuraRoutes.EQUALIZER,
        AuraRoutes.AUDIO_EFFECTS,
        AuraRoutes.QUEUE,
    )

    fun classify(
        fromRoute: String?,
        toRoute: String?,
    ): AuraRouteTransitionKind {
        if (fromRoute in topTabRoutes && toRoute in topTabRoutes) {
            return AuraRouteTransitionKind.TOP_TAB
        }
        val involvesPlayerSheet = (toRoute == AuraRoutes.PLAYER && fromRoute !in audioSuiteRoutes) ||
            (fromRoute == AuraRoutes.PLAYER && toRoute !in audioSuiteRoutes)
        if (involvesPlayerSheet) {
            return AuraRouteTransitionKind.MODAL_SHEET_RISE
        }
        return AuraRouteTransitionKind.SHARED_AXIS_X
    }

    fun enterTransition(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
    ): EnterTransition {
        val from = scope.initialState.destination.route
        val to = scope.targetState.destination.route
        return when (classify(from, to)) {
            AuraRouteTransitionKind.TOP_TAB ->
                fadeIn(tween(TAB_DURATION_MS, easing = StandardMotionEasing)) +
                    scaleIn(
                        initialScale = 0.975f,
                        animationSpec = tween(TAB_DURATION_MS, easing = SmoothDecelerateEasing),
                    )

            AuraRouteTransitionKind.MODAL_SHEET_RISE ->
                if (to == AuraRoutes.PLAYER) {
                    slideInVertically(
                        animationSpec = tween(SHEET_ENTER_MS, easing = SmoothDecelerateEasing),
                        initialOffsetY = { fullHeight -> (fullHeight * 0.18f).toInt() },
                    ) + fadeIn(tween(SHEET_ENTER_MS, easing = SmoothDecelerateEasing))
                } else {
                    scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(SHEET_EXIT_MS, easing = SmoothDecelerateEasing),
                    ) + fadeIn(tween(SHEET_EXIT_MS, easing = StandardMotionEasing))
                }

            AuraRouteTransitionKind.SHARED_AXIS_X ->
                slideInHorizontally(
                    animationSpec = tween(AXIS_X_ENTER_MS, easing = SmoothDecelerateEasing),
                    initialOffsetX = { fullWidth -> (fullWidth * 0.22f).toInt() },
                ) + fadeIn(tween(AXIS_X_ENTER_MS, easing = StandardMotionEasing))
        }
    }

    fun exitTransition(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
    ): ExitTransition {
        val from = scope.initialState.destination.route
        val to = scope.targetState.destination.route
        return when (classify(from, to)) {
            AuraRouteTransitionKind.TOP_TAB ->
                fadeOut(tween(TAB_DURATION_MS - 30, easing = FastOutLinearInEasing)) +
                    scaleOut(
                        targetScale = 0.985f,
                        animationSpec = tween(TAB_DURATION_MS, easing = StandardMotionEasing),
                    )

            AuraRouteTransitionKind.MODAL_SHEET_RISE ->
                if (from == AuraRoutes.PLAYER) {
                    slideOutVertically(
                        animationSpec = tween(SHEET_EXIT_MS, easing = StandardMotionEasing),
                        targetOffsetY = { fullHeight -> (fullHeight * 0.22f).toInt() },
                    ) + fadeOut(tween(SHEET_EXIT_MS, easing = FastOutLinearInEasing))
                } else {
                    scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(SHEET_ENTER_MS, easing = StandardMotionEasing),
                    ) + fadeOut(tween(SHEET_ENTER_MS - 40, easing = FastOutLinearInEasing))
                }

            AuraRouteTransitionKind.SHARED_AXIS_X ->
                slideOutHorizontally(
                    animationSpec = tween(AXIS_X_EXIT_MS, easing = StandardMotionEasing),
                    targetOffsetX = { fullWidth -> -(fullWidth * 0.10f).toInt() },
                ) + scaleOut(
                    targetScale = 0.97f,
                    animationSpec = tween(AXIS_X_EXIT_MS, easing = StandardMotionEasing),
                ) + fadeOut(tween(AXIS_X_EXIT_MS - 30, easing = FastOutLinearInEasing))
        }
    }

    fun popEnterTransition(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
    ): EnterTransition {
        val from = scope.initialState.destination.route
        val to = scope.targetState.destination.route
        return when (classify(from, to)) {
            AuraRouteTransitionKind.TOP_TAB ->
                fadeIn(tween(TAB_DURATION_MS, easing = StandardMotionEasing)) +
                    scaleIn(
                        initialScale = 0.975f,
                        animationSpec = tween(TAB_DURATION_MS, easing = SmoothDecelerateEasing),
                    )

            AuraRouteTransitionKind.MODAL_SHEET_RISE ->
                if (to == AuraRoutes.PLAYER) {
                    slideInVertically(
                        animationSpec = tween(SHEET_ENTER_MS, easing = SmoothDecelerateEasing),
                        initialOffsetY = { fullHeight -> (fullHeight * 0.18f).toInt() },
                    ) + fadeIn(tween(SHEET_ENTER_MS, easing = SmoothDecelerateEasing))
                } else {
                    scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(SHEET_EXIT_MS, easing = SmoothDecelerateEasing),
                    ) + fadeIn(tween(SHEET_EXIT_MS, easing = StandardMotionEasing))
                }

            AuraRouteTransitionKind.SHARED_AXIS_X ->
                slideInHorizontally(
                    animationSpec = tween(AXIS_X_ENTER_MS, easing = SmoothDecelerateEasing),
                    initialOffsetX = { fullWidth -> -(fullWidth * 0.10f).toInt() },
                ) + scaleIn(
                    initialScale = 0.97f,
                    animationSpec = tween(AXIS_X_ENTER_MS, easing = SmoothDecelerateEasing),
                ) + fadeIn(tween(AXIS_X_ENTER_MS, easing = StandardMotionEasing))
        }
    }

    fun popExitTransition(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
    ): ExitTransition {
        val from = scope.initialState.destination.route
        val to = scope.targetState.destination.route
        return when (classify(from, to)) {
            AuraRouteTransitionKind.TOP_TAB ->
                fadeOut(tween(TAB_DURATION_MS - 30, easing = FastOutLinearInEasing)) +
                    scaleOut(
                        targetScale = 0.985f,
                        animationSpec = tween(TAB_DURATION_MS, easing = StandardMotionEasing),
                    )

            AuraRouteTransitionKind.MODAL_SHEET_RISE ->
                if (from == AuraRoutes.PLAYER) {
                    slideOutVertically(
                        animationSpec = tween(SHEET_EXIT_MS, easing = StandardMotionEasing),
                        targetOffsetY = { fullHeight -> (fullHeight * 0.22f).toInt() },
                    ) + fadeOut(tween(SHEET_EXIT_MS, easing = FastOutLinearInEasing))
                } else {
                    scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(SHEET_EXIT_MS, easing = StandardMotionEasing),
                    ) + fadeOut(tween(SHEET_EXIT_MS - 30, easing = FastOutLinearInEasing))
                }

            AuraRouteTransitionKind.SHARED_AXIS_X ->
                slideOutHorizontally(
                    animationSpec = tween(AXIS_X_EXIT_MS, easing = StandardMotionEasing),
                    targetOffsetX = { fullWidth -> (fullWidth * 0.22f).toInt() },
                ) + fadeOut(tween(AXIS_X_EXIT_MS - 30, easing = FastOutLinearInEasing))
        }
    }
}
