package com.auramusic.app

import com.auramusic.core.database.PlaybackPreferencesRepository
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.PlaybackState
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.model.SleepTimerState
import com.auramusic.core.model.Track
import com.auramusic.core.player.AuraPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coordinador modular encargado de la cola de reproducción, controles de transporte,
 * velocidad de reproducción, velocidad/tono de voz, Crossfade y temporizador de apagado.
 */
class AuraPlaybackCoordinator(
    private val scope: CoroutineScope,
    private val playerController: AuraPlayerController,
    private val playbackPreferencesRepository: PlaybackPreferencesRepository,
    private val audioEffectsStateProvider: () -> AudioEffectsState,
    private val onEffectsAdjustedByManualTuning: (AudioEffectsState) -> Unit,
    private val onPlaybackTuningChanged: (PlaybackTuning) -> Unit = {},
) {
    val playbackState: StateFlow<PlaybackState> = playerController.playbackState

    val sleepTimerState: StateFlow<SleepTimerState> = playerController.sleepTimerState

    val playbackTuning: StateFlow<PlaybackTuning> = playbackPreferencesRepository.playbackTuning
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = PlaybackTuning(),
        )

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        playerController.playQueue(tracks, startIndex)
    }

    fun playShuffled(tracks: List<Track>) {
        playerController.playShuffled(tracks)
    }

    fun playNext(track: Track) {
        playerController.playNext(track)
    }

    fun addToQueue(track: Track) {
        playerController.addToQueue(track)
    }

    fun playQueueItemAt(index: Int) {
        playerController.playQueueItemAt(index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playerController.moveQueueItem(fromIndex, toIndex)
    }

    fun removeFromQueue(index: Int) {
        playerController.removeFromQueue(index)
    }

    fun clearUpcomingQueue() {
        playerController.clearUpcomingQueue()
    }

    fun togglePlayPause() = playerController.togglePlayPause()

    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    fun skipNext() = playerController.skipToNext()

    fun skipPrevious() = playerController.skipToPrevious()

    fun toggleShuffle() = playerController.toggleShuffle()

    fun cycleRepeat() = playerController.cycleRepeatMode()

    fun setCrossfadeEnabled(enabled: Boolean) {
        val nextTuning = playbackTuning.value.copy(crossfadeEnabled = enabled).normalized()
        playerController.syncPlaybackTuning(nextTuning)
        onPlaybackTuningChanged(nextTuning)
        scope.launch {
            playbackPreferencesRepository.setCrossfadeEnabled(enabled)
        }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val clamped = PlaybackTuningHelper.clampCrossfadeSeconds(seconds)
        val nextTuning = playbackTuning.value.copy(crossfadeSeconds = clamped).normalized()
        playerController.syncPlaybackTuning(nextTuning)
        onPlaybackTuningChanged(nextTuning)
        scope.launch {
            playbackPreferencesRepository.setCrossfadeSeconds(clamped)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clampedSpeed = PlaybackTuningHelper.clampPlaybackSpeed(speed)
        val nextEffects = disableSlowedIfNeeded()
        val currentTuning = playbackTuning.value
        val nextTuning = if (currentTuning.linkPitchToSpeed) {
            currentTuning.copy(
                playbackSpeed = clampedSpeed,
                playbackPitch = PlaybackTuningHelper.clampPlaybackPitch(clampedSpeed),
            )
        } else {
            currentTuning.copy(playbackSpeed = clampedSpeed)
        }.normalized()
        applyResolvedTuningImmediately(nextTuning, nextEffects)
        playerController.syncPlaybackTuning(nextTuning)
        onPlaybackTuningChanged(nextTuning)
        scope.launch {
            playbackPreferencesRepository.setPlaybackSpeed(clampedSpeed)
            if (currentTuning.linkPitchToSpeed) {
                playbackPreferencesRepository.setPlaybackPitch(nextTuning.playbackPitch)
            }
        }
    }

    fun setPlaybackPitch(pitch: Float) {
        val clampedPitch = PlaybackTuningHelper.clampPlaybackPitch(pitch)
        val nextEffects = disableSlowedIfNeeded()
        val currentTuning = playbackTuning.value
        val wasLinked = currentTuning.linkPitchToSpeed
        val nextTuning = currentTuning.copy(
            playbackPitch = clampedPitch,
            linkPitchToSpeed = false,
        ).normalized()
        applyResolvedTuningImmediately(nextTuning, nextEffects)
        playerController.syncPlaybackTuning(nextTuning)
        onPlaybackTuningChanged(nextTuning)
        scope.launch {
            if (wasLinked) {
                playbackPreferencesRepository.setLinkPitchToSpeed(false)
            }
            playbackPreferencesRepository.setPlaybackPitch(clampedPitch)
        }
    }

    fun setLinkPitchToSpeed(linked: Boolean) {
        val nextEffects = disableSlowedIfNeeded()
        val currentTuning = playbackTuning.value
        val syncedPitch = if (linked) {
            PlaybackTuningHelper.clampPlaybackPitch(currentTuning.playbackSpeed)
        } else {
            currentTuning.playbackPitch
        }
        val nextTuning = currentTuning.copy(
            linkPitchToSpeed = linked,
            playbackPitch = syncedPitch,
        ).normalized()
        applyResolvedTuningImmediately(nextTuning, nextEffects)
        playerController.syncPlaybackTuning(nextTuning)
        onPlaybackTuningChanged(nextTuning)
        scope.launch {
            playbackPreferencesRepository.setLinkPitchToSpeed(linked)
            if (linked) {
                playbackPreferencesRepository.setPlaybackPitch(syncedPitch)
            }
        }
    }

    fun resetPlaybackSpeedAndPitch() {
        val nextEffects = disableSlowedIfNeeded()
        val nextTuning = playbackTuning.value.copy(
            playbackSpeed = PlaybackTuning.DEFAULT_PLAYBACK_SPEED,
            playbackPitch = PlaybackTuning.DEFAULT_PLAYBACK_PITCH,
            linkPitchToSpeed = false,
        ).normalized()
        applyResolvedTuningImmediately(nextTuning, nextEffects)
        playerController.syncPlaybackTuning(nextTuning)
        onPlaybackTuningChanged(nextTuning)
        scope.launch {
            playbackPreferencesRepository.setLinkPitchToSpeed(false)
            playbackPreferencesRepository.setPlaybackSpeed(PlaybackTuning.DEFAULT_PLAYBACK_SPEED)
            playbackPreferencesRepository.setPlaybackPitch(PlaybackTuning.DEFAULT_PLAYBACK_PITCH)
        }
    }

    fun startSleepTimerMinutes(minutes: Int, fadeOutOnFinish: Boolean) {
        playerController.startSleepTimerMinutes(minutes, fadeOutOnFinish)
    }

    fun setSleepTimerEndOfTrack(fadeOutOnFinish: Boolean) {
        playerController.setSleepTimerEndOfTrack(fadeOutOnFinish)
    }

    fun setSleepTimerEndOfQueue(fadeOutOnFinish: Boolean) {
        playerController.setSleepTimerEndOfQueue(fadeOutOnFinish)
    }

    fun addSleepTimerMinutes(deltaMinutes: Int) {
        playerController.addSleepTimerMinutes(deltaMinutes)
    }

    fun cancelSleepTimer() {
        playerController.cancelSleepTimer()
    }

    private fun disableSlowedIfNeeded(): AudioEffectsState {
        val currentEffects = audioEffectsStateProvider()
        if (!currentEffects.slowedEnabled) return currentEffects
        val nextPresetId = if (currentEffects.reverbEnabled ||
            currentEffects.spatial8dEnabled ||
            currentEffects.crossfeedEnabled
        ) {
            AudioEffectsState.PRESET_CUSTOM_ID
        } else {
            AudioEffectsState.PRESET_OFF_ID
        }
        val updated = currentEffects.copy(
            slowedEnabled = false,
            activePresetId = nextPresetId,
        )
        onEffectsAdjustedByManualTuning(updated)
        return updated
    }

    private fun applyResolvedTuningImmediately(
        tuning: PlaybackTuning,
        effects: AudioEffectsState,
    ) {
        val resolved = PlaybackTuningHelper.resolvePlaybackParameters(tuning, effects)
        playerController.setPlaybackParameters(resolved.speed, resolved.pitch)
    }
}
