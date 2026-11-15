package com.auramusic.core.player

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.auramusic.core.model.SleepTimerHelper
import com.auramusic.core.model.SleepTimerMode
import com.auramusic.core.model.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Motor modular del temporizador de apagado automático (*Sleep Timer*) en segundo plano.
 * Soporta cuenta atrás por minutos con atenuación suave en los últimos 3 segundos,
 * parada al finalizar la pista actual (`END_OF_TRACK`) o al completar la cola (`END_OF_QUEUE`).
 */
internal class AuraSleepTimerEngine(
    private val scope: CoroutineScope,
    private val primaryPlayerProvider: () -> ExoPlayer?,
    private val isCrossfadeActive: () -> Boolean,
    private val onCancelActiveCrossfade: (String) -> Unit,
    private val onPushSessionExtras: (Bundle) -> Unit,
) {
    var currentState: SleepTimerState = SleepTimerState()
        private set

    var isStoppingPlayback: Boolean = false
        private set

    var lastObservedMediaIndex: Int = C.INDEX_UNSET

    private var sleepTimerJob: Job? = null
    private var sleepTimerEndElapsedMs: Long = 0L

    fun startMinutes(minutes: Int, fadeOutOnFinish: Boolean) {
        val safeMinutes = SleepTimerHelper.clampCustomMinutes(minutes)
        val totalMs = safeMinutes * 60_000L
        sleepTimerJob?.cancel()
        sleepTimerEndElapsedMs = SystemClock.elapsedRealtime() + totalMs
        if (!isCrossfadeActive()) {
            primaryPlayerProvider()?.volume = 1f
        }
        publishState(
            SleepTimerState(
                mode = SleepTimerMode.DURATION,
                configuredMinutes = safeMinutes,
                totalDurationMs = totalMs,
                remainingMs = totalMs,
                fadeOutOnFinish = fadeOutOnFinish,
                endElapsedMs = sleepTimerEndElapsedMs,
            ),
            pushSessionExtras = true,
        )
        launchTicker()
    }

    fun addMinutes(deltaMinutes: Int) {
        if (currentState.mode != SleepTimerMode.DURATION) return
        val now = SystemClock.elapsedRealtime()
        val currentRemaining = (sleepTimerEndElapsedMs - now).coerceAtLeast(0L)
        val updatedRemaining = (currentRemaining + deltaMinutes * 60_000L)
            .coerceAtMost(SleepTimerHelper.MAX_CUSTOM_MINUTES * 60_000L)
        if (updatedRemaining <= 0L) {
            cancel()
            return
        }
        sleepTimerEndElapsedMs = now + updatedRemaining
        if (updatedRemaining > 3_000L && !isCrossfadeActive()) {
            primaryPlayerProvider()?.volume = 1f
        }
        val nextTotal = maxOf(currentState.totalDurationMs, updatedRemaining)
        val nextMinutes = ((updatedRemaining + 59_999L) / 60_000L).toInt()
            .coerceIn(SleepTimerHelper.MIN_CUSTOM_MINUTES, SleepTimerHelper.MAX_CUSTOM_MINUTES)
        publishState(
            currentState.copy(
                configuredMinutes = nextMinutes,
                totalDurationMs = nextTotal,
                remainingMs = updatedRemaining,
                endElapsedMs = sleepTimerEndElapsedMs,
            ),
            pushSessionExtras = true,
        )
    }

    fun setEndMode(mode: SleepTimerMode, fadeOutOnFinish: Boolean) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndElapsedMs = 0L
        if (!isCrossfadeActive()) {
            primaryPlayerProvider()?.volume = 1f
        }
        lastObservedMediaIndex = primaryPlayerProvider()?.currentMediaItemIndex ?: C.INDEX_UNSET
        publishState(
            SleepTimerState(
                mode = mode,
                fadeOutOnFinish = fadeOutOnFinish,
            ),
            pushSessionExtras = true,
        )
    }

    fun cancel() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndElapsedMs = 0L
        if (!isCrossfadeActive()) {
            primaryPlayerProvider()?.volume = 1f
        }
        publishState(SleepTimerState(), pushSessionExtras = true)
    }

    fun handlePlaybackEnded() {
        if (currentState.mode == SleepTimerMode.END_OF_TRACK ||
            currentState.mode == SleepTimerMode.END_OF_QUEUE
        ) {
            cancel()
        }
    }

    fun handleAutoTransitionIfNeeded(
        previousIndex: Int,
        newIndex: Int,
        itemCount: Int,
        reasonIsAuto: Boolean,
        reasonIsRepeat: Boolean,
    ) {
        if ((reasonIsAuto || reasonIsRepeat) &&
            SleepTimerHelper.shouldPauseOnAutoTransition(
                mode = currentState.mode,
                previousIndex = previousIndex,
                newIndex = newIndex,
                itemCount = itemCount,
                reasonIsAuto = reasonIsAuto,
                reasonIsRepeat = reasonIsRepeat,
            )
        ) {
            completeAndPause(seekBackToStartOfNewItem = true)
        }
    }

    fun release() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndElapsedMs = 0L
        AuraSleepTimerBridge.publishState(SleepTimerState())
    }

    private fun launchTicker() {
        sleepTimerJob?.cancel()
        sleepTimerJob = scope.launch {
            while (isActive && currentState.mode == SleepTimerMode.DURATION) {
                val remainingMs = (sleepTimerEndElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (remainingMs <= 0L) {
                    completeAndPause(seekBackToStartOfNewItem = false)
                    break
                }

                val player = primaryPlayerProvider()
                if (currentState.fadeOutOnFinish &&
                    remainingMs <= 3_000L &&
                    player?.isPlaying == true &&
                    !isCrossfadeActive()
                ) {
                    player.volume = (remainingMs.toFloat() / 3_000f).coerceIn(0.05f, 1f)
                }

                // Actualiza solo el estado local del servicio sin saturar Binder cada 250 ms;
                // el cliente UI calcula la cuenta atrás desde `endElapsedMs`.
                publishState(
                    currentState.copy(
                        remainingMs = remainingMs,
                        endElapsedMs = sleepTimerEndElapsedMs,
                    ),
                    pushSessionExtras = false,
                )
                delay(250L)
            }
        }
    }

    private fun completeAndPause(seekBackToStartOfNewItem: Boolean) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndElapsedMs = 0L
        isStoppingPlayback = true
        try {
            onCancelActiveCrossfade("sleep_timer_finished")
            primaryPlayerProvider()?.let { player ->
                player.pause()
                if (seekBackToStartOfNewItem && player.mediaItemCount > 0) {
                    player.seekTo(0L)
                }
                player.volume = 1f
            }
        } finally {
            isStoppingPlayback = false
        }
        publishState(SleepTimerState(), pushSessionExtras = true)
    }

    private fun publishState(state: SleepTimerState, pushSessionExtras: Boolean) {
        currentState = state
        AuraSleepTimerBridge.publishState(state)
        if (pushSessionExtras) {
            onPushSessionExtras(AuraPlayerIpcContract.sleepTimerToBundle(state))
        }
    }
}
