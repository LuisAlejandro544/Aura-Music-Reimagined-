package com.auramusic.core.player

import android.content.Intent
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.PlaybackTuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Contrato desacoplado para que `:app` suministre al servicio Media3 los flujos reactivos
 * de Crossfade/velocidad, efectos Rust, preferencias de auriculares/AVRCP, ruta de salida activa
 * y el Intent de apertura del reproductor sin acoplar `:core:player` con `:core:database` ni `:app`.
 */
interface AuraPlaybackTuningProvider {
    fun observePlaybackTuning(): Flow<PlaybackTuning>

    fun observeAudioEffects(): Flow<AudioEffectsState> = emptyFlow()

    fun observeHeadphonePreferences(): Flow<HeadphonePreferences> = emptyFlow()

    fun observeAudioOutputRoute(): Flow<AudioOutputRouteState> = emptyFlow()

    fun createOpenPlayerIntent(): Intent? = null

    fun onSyncEqualizer(state: EqualizerState) {}

    fun onSyncAudioEffects(state: AudioEffectsState) {}

    fun onSyncPlaybackTuning(tuning: PlaybackTuning) {}

    fun onSyncHeadphonePreferences(prefs: HeadphonePreferences) {}
}
