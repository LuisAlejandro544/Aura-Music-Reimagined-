package com.auramusic.core.player

import com.auramusic.core.model.SleepTimerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Contrato de comandos personalizados entre `AuraPlayerController` y `AuraPlaybackService`
 * para gestionar el temporizador de apagado automático de forma segura en segundo plano.
 */
object AuraSleepTimerContract {
    const val ACTION_START_MINUTES = "com.auramusic.action.SLEEP_TIMER_START_MINUTES"
    const val ACTION_SET_END_OF_TRACK = "com.auramusic.action.SLEEP_TIMER_END_OF_TRACK"
    const val ACTION_SET_END_OF_QUEUE = "com.auramusic.action.SLEEP_TIMER_END_OF_QUEUE"
    const val ACTION_ADD_MINUTES = "com.auramusic.action.SLEEP_TIMER_ADD_MINUTES"
    const val ACTION_CANCEL = "com.auramusic.action.SLEEP_TIMER_CANCEL"

    const val EXTRA_MINUTES = "extra_minutes"
    const val EXTRA_DELTA_MINUTES = "extra_delta_minutes"
    const val EXTRA_FADE_OUT = "extra_fade_out"

    val supportedActions = listOf(
        ACTION_START_MINUTES,
        ACTION_SET_END_OF_TRACK,
        ACTION_SET_END_OF_QUEUE,
        ACTION_ADD_MINUTES,
        ACTION_CANCEL,
    )
}

/**
 * Puente reactivo en memoria que publica el estado en tiempo real del temporizador
 * ejecutado dentro de `AuraPlaybackService` para que la UI muestre la cuenta atrás exacta
 * incluso después de recrear la Activity.
 */
object AuraSleepTimerBridge {
    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    fun publishState(state: SleepTimerState) {
        _sleepTimerState.value = state
    }
}
