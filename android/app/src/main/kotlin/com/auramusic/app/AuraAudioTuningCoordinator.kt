package com.auramusic.app

import android.content.Context
import com.auramusic.core.database.AudioEffectsPreferencesRepository
import com.auramusic.core.database.EqualizerPreferencesRepository
import com.auramusic.core.database.HeadphonePreferencesRepository
import com.auramusic.core.model.AudioEffectPreset
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.EqualizerPreset
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.HeadphoneRoutingHelper
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.player.AuraAudioOutputMonitor
import com.auramusic.core.player.AuraPlayerConfigSnapshot
import com.auramusic.core.player.AuraPlayerConfigStore
import com.auramusic.core.player.AuraPlayerController
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coordinador modular encargado del ecualizador de 10 bandas, efectos espaciales en Rust
 * (Reverb, Audio 8D, Crossfeed binaural, calidez Slowed) y preferencias de auriculares/AVRCP.
 */
class AuraAudioTuningCoordinator(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val playerController: AuraPlayerController,
    private val equalizerPreferencesRepository: EqualizerPreferencesRepository,
    private val audioEffectsPreferencesRepository: AudioEffectsPreferencesRepository,
    private val headphonePreferencesRepository: HeadphonePreferencesRepository,
    audioOutputMonitor: AuraAudioOutputMonitor,
    private val playbackTuningProvider: () -> PlaybackTuning,
    private val onPlayerConfigPersisted: (suspend () -> Unit)? = null,
) {
    val equalizerState: StateFlow<EqualizerState> = equalizerPreferencesRepository.equalizerState
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = EqualizerState(),
        )

    val audioEffects: StateFlow<AudioEffectsState> = audioEffectsPreferencesRepository.audioEffectsState
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AudioEffectsState(),
        )

    val headphonePreferences: StateFlow<HeadphonePreferences> =
        headphonePreferencesRepository.headphonePreferences
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = HeadphonePreferences(),
            )

    val audioOutputRoute: StateFlow<AudioOutputRouteState> = audioOutputMonitor.outputRoute

    fun startSyncLoops() {
        scope.launch {
            headphonePreferences.collect { prefs ->
                playerController.syncHeadphonePreferences(prefs)
            }
        }
        scope.launch {
            combine(audioEffects, audioOutputRoute, headphonePreferences) { fx, route, hp ->
                Triple(fx, route, hp)
            }.collect { (fx, route, hp) ->
                syncEffectsToRust(fx, route, hp)
            }
        }
    }

    fun persistPlayerConfigSnapshot(
        equalizer: EqualizerState = equalizerState.value,
        effects: AudioEffectsState = audioEffects.value,
        tuning: PlaybackTuning = playbackTuningProvider(),
        headphonePrefs: HeadphonePreferences = headphonePreferences.value,
    ) {
        scope.launch(Dispatchers.IO) {
            AuraPlayerConfigStore.writeSnapshot(
                context = appContext,
                snapshot = AuraPlayerConfigSnapshot(
                    equalizer = equalizer,
                    audioEffects = effects,
                    playbackTuning = tuning,
                    headphonePreferences = headphonePrefs,
                ),
            )
            onPlayerConfigPersisted?.invoke()
        }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        updateEqualizerState(equalizerState.value.copy(isEnabled = enabled))
    }

    fun setEqualizerBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until EqualizerState.BAND_COUNT) return
        val updatedBands = equalizerState.value.bandGainsDb.toMutableList()
        while (updatedBands.size < EqualizerState.BAND_COUNT) updatedBands.add(0f)
        updatedBands[bandIndex] = gainDb.coerceIn(
            EqualizerState.MIN_GAIN_DB,
            EqualizerState.MAX_GAIN_DB,
        )
        updateEqualizerState(
            equalizerState.value.copy(
                bandGainsDb = updatedBands,
                activePresetId = EqualizerState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun selectEqualizerPreset(preset: EqualizerPreset) {
        updateEqualizerState(
            equalizerState.value.copy(
                isEnabled = true,
                bandGainsDb = preset.gainsDb,
                activePresetId = preset.id,
            ),
        )
    }

    fun resetEqualizer() {
        updateEqualizerState(
            equalizerState.value.copy(
                bandGainsDb = List(EqualizerState.BAND_COUNT) { 0f },
                activePresetId = EqualizerState.PRESET_FLAT_ID,
            ),
        )
    }

    private fun updateEqualizerState(nextState: EqualizerState) {
        val normalized = nextState.normalized()
        playerController.syncEqualizer(normalized)
        persistPlayerConfigSnapshot(equalizer = normalized)
        scope.launch {
            AuraRustBridge.setEqualizerConfig(normalized.isEnabled, normalized.bandGainsDb)
            equalizerPreferencesRepository.saveEqualizerState(normalized)
        }
    }

    fun selectAudioEffectPreset(preset: AudioEffectPreset) {
        updateAudioEffectsState(AudioEffectsState.fromPreset(preset))
    }

    fun setReverbEnabled(enabled: Boolean) {
        val current = audioEffects.value
        val nextPresetId = if (!enabled &&
            !current.slowedEnabled &&
            !current.spatial8dEnabled &&
            !current.crossfeedEnabled
        ) {
            AudioEffectsState.PRESET_OFF_ID
        } else {
            AudioEffectsState.PRESET_CUSTOM_ID
        }
        updateAudioEffectsState(current.copy(reverbEnabled = enabled, activePresetId = nextPresetId))
    }

    fun setReverbRoomSize(roomSize: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                reverbEnabled = true,
                reverbRoomSize = roomSize,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setReverbDamping(damping: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                reverbEnabled = true,
                reverbDamping = damping,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setReverbWetMix(wetMix: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                reverbEnabled = true,
                reverbWetMix = wetMix,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setReverbWidth(width: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                reverbEnabled = true,
                reverbWidth = width,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSlowedEnabled(enabled: Boolean) {
        val current = audioEffects.value
        val nextPresetId = if (!enabled &&
            !current.reverbEnabled &&
            !current.spatial8dEnabled &&
            !current.crossfeedEnabled
        ) {
            AudioEffectsState.PRESET_OFF_ID
        } else {
            AudioEffectsState.PRESET_CUSTOM_ID
        }
        updateAudioEffectsState(current.copy(slowedEnabled = enabled, activePresetId = nextPresetId))
    }

    fun setSlowedSpeed(speed: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                slowedEnabled = true,
                slowedSpeed = speed,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSlowedPreservePitch(preservePitch: Boolean) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                slowedEnabled = true,
                slowedPreservePitch = preservePitch,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSlowedWarmth(warmth: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                slowedEnabled = true,
                slowedWarmth = warmth,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSpatial8dEnabled(enabled: Boolean) {
        val current = audioEffects.value
        val nextPresetId = if (!enabled &&
            !current.reverbEnabled &&
            !current.slowedEnabled &&
            !current.crossfeedEnabled
        ) {
            AudioEffectsState.PRESET_OFF_ID
        } else {
            AudioEffectsState.PRESET_CUSTOM_ID
        }
        updateAudioEffectsState(current.copy(spatial8dEnabled = enabled, activePresetId = nextPresetId))
    }

    fun setSpatial8dSpeed(speed: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                spatial8dEnabled = true,
                spatial8dSpeed = speed,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSpatial8dDepth(depth: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                spatial8dEnabled = true,
                spatial8dDepth = depth,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setCrossfeedEnabled(enabled: Boolean) {
        val current = audioEffects.value
        if (!enabled && headphonePreferences.value.autoEnableCrossfeedOnHeadphones) {
            setAutoEnableCrossfeedOnHeadphones(false)
        }
        val nextPresetId = if (!enabled &&
            !current.reverbEnabled &&
            !current.slowedEnabled &&
            !current.spatial8dEnabled
        ) {
            AudioEffectsState.PRESET_OFF_ID
        } else {
            AudioEffectsState.PRESET_CUSTOM_ID
        }
        updateAudioEffectsState(current.copy(crossfeedEnabled = enabled, activePresetId = nextPresetId))
    }

    fun setCrossfeedStrength(strength: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                crossfeedEnabled = true,
                crossfeedStrength = strength,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setCrossfeedAngle(angle: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                crossfeedEnabled = true,
                crossfeedAngle = angle,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun resetAudioEffects() {
        updateAudioEffectsState(AudioEffectsState())
    }

    fun setAutoPauseOnDisconnect(enabled: Boolean) {
        updateHeadphonePreferences { it.copy(autoPauseOnDisconnect = enabled) }
    }

    fun setAutoResumeOnConnect(enabled: Boolean) {
        updateHeadphonePreferences { it.copy(autoResumeOnConnect = enabled) }
    }

    fun setAutoDisable8dOnSpeaker(enabled: Boolean) {
        updateHeadphonePreferences { it.copy(autoDisable8dOnSpeaker = enabled) }
    }

    fun setAutoEnableCrossfeedOnHeadphones(enabled: Boolean) {
        updateHeadphonePreferences { it.copy(autoEnableCrossfeedOnHeadphones = enabled) }
    }

    fun setDoubleTapNextEnabled(enabled: Boolean) {
        updateHeadphonePreferences { it.copy(doubleTapNextEnabled = enabled) }
    }

    fun setTripleTapPreviousEnabled(enabled: Boolean) {
        updateHeadphonePreferences { it.copy(tripleTapPreviousEnabled = enabled) }
    }

    fun setPreviousRestartThresholdMs(ms: Long) {
        updateHeadphonePreferences {
            it.copy(previousRestartThresholdMs = HeadphoneRoutingHelper.clampPreviousRestartThresholdMs(ms))
        }
    }

    fun setWrapQueueNavigation(enabled: Boolean) {
        updateHeadphonePreferences { it.copy(wrapQueueNavigation = enabled) }
    }

    fun setSeekStepSeconds(seconds: Int) {
        updateHeadphonePreferences {
            it.copy(seekStepSeconds = HeadphoneRoutingHelper.clampSeekStepSeconds(seconds))
        }
    }

    private fun updateHeadphonePreferences(transform: (HeadphonePreferences) -> HeadphonePreferences) {
        val next = transform(headphonePreferences.value).normalized()
        playerController.syncHeadphonePreferences(next)
        persistPlayerConfigSnapshot(headphonePrefs = next)
        scope.launch {
            headphonePreferencesRepository.saveHeadphonePreferences(next)
        }
    }

    fun updateAudioEffectsState(nextState: AudioEffectsState) {
        val normalized = nextState.normalized()
        val resolvedParams = PlaybackTuningHelper.resolvePlaybackParameters(
            tuning = playbackTuningProvider(),
            effects = normalized,
        )
        playerController.setPlaybackParameters(resolvedParams.speed, resolvedParams.pitch)
        playerController.syncAudioEffects(normalized)
        persistPlayerConfigSnapshot(effects = normalized)
        scope.launch {
            syncEffectsToRust(normalized, audioOutputRoute.value, headphonePreferences.value)
            audioEffectsPreferencesRepository.saveAudioEffectsState(normalized)
        }
    }

    suspend fun syncEffectsToRust(
        state: AudioEffectsState,
        route: AudioOutputRouteState = audioOutputRoute.value,
        prefs: HeadphonePreferences = headphonePreferences.value,
    ) {
        val effective = HeadphoneRoutingHelper.resolveEffectiveRustEffects(
            effects = state,
            outputRoute = route,
            prefs = prefs,
        )
        AuraRustBridge.setAudioEffectsConfig(
            reverbEnabled = state.reverbEnabled,
            reverbRoomSize = state.reverbRoomSize,
            reverbDamping = state.reverbDamping,
            reverbWetMix = state.reverbWetMix,
            reverbWidth = state.reverbWidth,
            slowedWarmthEnabled = state.isSlowedWarmthActive,
            slowedWarmthAmount = state.slowedWarmth,
            spatial8dEnabled = effective.spatial8dEnabled,
            spatial8dSpeed = state.spatial8dSpeed,
            spatial8dDepth = state.spatial8dDepth,
            crossfeedEnabled = effective.crossfeedEnabled,
            crossfeedStrength = state.crossfeedStrength,
            crossfeedAngle = state.crossfeedAngle,
        )
    }
}
