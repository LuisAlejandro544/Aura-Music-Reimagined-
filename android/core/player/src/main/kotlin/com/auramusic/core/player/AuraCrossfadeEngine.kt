package com.auramusic.core.player

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.model.SleepTimerHelper
import com.auramusic.core.model.SleepTimerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Motor modular de Crossfade equal-power en segundo plano para `AuraPlaybackService`.
 *
 * Mantiene un reproductor auxiliar saliente (`outgoingFadePlayer`) sin `RustEqualizerAudioProcessor`
 * ni reclamo de audio focus para no corromper las líneas de retardo IIR/ITD/Freeverb del
 * reproductor principal durante las transiciones suaves entre canciones.
 */
@OptIn(UnstableApi::class)
internal class AuraCrossfadeEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val primaryPlayerProvider: () -> ExoPlayer?,
    private val tuningProvider: () -> PlaybackTuning,
    private val sleepTimerStateProvider: () -> SleepTimerState,
) {
    private var outgoingFadePlayer: ExoPlayer? = null
    private var crossfadeMonitorJob: Job? = null
    private var activeCrossfadeJob: Job? = null
    private var preloadedMediaId: String? = null

    var isAdvancingTransition: Boolean = false
        private set

    val isCrossfadeActive: Boolean
        get() = activeCrossfadeJob?.isActive == true

    fun clearPreloadedMediaId() {
        preloadedMediaId = null
    }

    fun syncOutgoingPlaybackParameters(speed: Float, pitch: Float) {
        if (isCrossfadeActive) {
            outgoingFadePlayer?.playbackParameters = PlaybackParameters(speed, pitch)
        }
    }

    fun startMonitorIfNeeded() {
        if (!tuningProvider().crossfadeEnabled) return
        if (crossfadeMonitorJob?.isActive == true) return

        crossfadeMonitorJob = scope.launch {
            while (isActive) {
                val player = primaryPlayerProvider()
                val tuning = tuningProvider()
                if (player == null || !tuning.crossfadeEnabled) {
                    break
                }

                if (player.isPlaying && activeCrossfadeJob?.isActive != true) {
                    val durationMs = player.duration
                    val positionMs = player.currentPosition
                    val currentItem = player.currentMediaItem
                    val hasNext = player.hasNextMediaItem()
                    val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE
                    val effectiveSpeed = PlaybackTuningHelper.clampPlaybackSpeed(
                        player.playbackParameters.speed,
                    )
                    val fadeSeconds = tuning.crossfadeSeconds

                    val bypassForSleepTimer = SleepTimerHelper.shouldBypassCrossfadeForSleepTimer(
                        state = sleepTimerStateProvider(),
                        hasNextMediaItem = hasNext,
                    )

                    if (currentItem != null &&
                        hasNext &&
                        !repeatOne &&
                        !bypassForSleepTimer &&
                        durationMs > 0L
                    ) {
                        val wallRemainingMs = PlaybackTuningHelper.remainingWallClockMs(
                            durationMs = durationMs,
                            positionMs = positionMs,
                            playbackSpeed = effectiveSpeed,
                        )
                        val preloadWindowMs = fadeSeconds * 1000L + 3_500L
                        val itemId = currentItem.mediaId.ifBlank {
                            currentItem.localConfiguration?.uri?.toString().orEmpty()
                        }

                        // Prepara por adelantado la pista actual en el deck saliente antes del inicio del fade.
                        if (wallRemainingMs in 400L..preloadWindowMs && preloadedMediaId != itemId) {
                            preloadedMediaId = itemId
                            val outPlayer = getOrCreateOutgoingFadePlayer()
                            outPlayer.stop()
                            outPlayer.setMediaItem(currentItem)
                            outPlayer.prepare()
                            outPlayer.playWhenReady = false
                            outPlayer.volume = 1f
                        }

                        val shouldTrigger = PlaybackTuningHelper.shouldTriggerCrossfade(
                            crossfadeEnabled = tuning.crossfadeEnabled,
                            isPlaying = player.isPlaying,
                            isAlreadyCrossfading = activeCrossfadeJob?.isActive == true,
                            hasNextMediaItem = hasNext,
                            repeatOneEnabled = repeatOne,
                            durationMs = durationMs,
                            positionMs = positionMs,
                            playbackSpeed = effectiveSpeed,
                            crossfadeSeconds = fadeSeconds,
                        )

                        if (shouldTrigger) {
                            launchOverlapCrossfade(
                                primary = player,
                                outgoingItem = currentItem,
                                outgoingPositionMs = positionMs,
                                outgoingDurationMs = durationMs,
                                speed = effectiveSpeed,
                                configuredSeconds = fadeSeconds,
                            )
                        }
                    }
                }

                delay(150L)
            }
        }
    }

    fun stopMonitor() {
        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = null
    }

    fun cancelActive(@Suppress("UNUSED_PARAMETER") reason: String) {
        activeCrossfadeJob?.cancel()
        activeCrossfadeJob = null
        primaryPlayerProvider()?.volume = 1f
        outgoingFadePlayer?.let { out ->
            out.stop()
            out.clearMediaItems()
            out.volume = 1f
            if (!tuningProvider().crossfadeEnabled) {
                out.release()
                outgoingFadePlayer = null
            }
        }
        preloadedMediaId = null
    }

    fun releaseOutgoingIfIdle() {
        if (activeCrossfadeJob?.isActive == true) return
        outgoingFadePlayer?.let { out ->
            out.stop()
            out.clearMediaItems()
            out.release()
        }
        outgoingFadePlayer = null
        preloadedMediaId = null
    }

    fun release() {
        stopMonitor()
        cancelActive("service_destroy")
        outgoingFadePlayer?.release()
        outgoingFadePlayer = null
        preloadedMediaId = null
    }

    private fun getOrCreateOutgoingFadePlayer(): ExoPlayer {
        outgoingFadePlayer?.let { return it }
        // El reproductor auxiliar saliente NO adjunta `RustEqualizerAudioProcessor` ni reclama
        // audio focus: así nunca se corrompe la línea de retardo IIR del ecualizador del reproductor principal.
        val fadeAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val fadeLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 350, 1_000)
            .setTargetBufferBytes(2 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(fadeLoadControl)
            .setAudioAttributes(fadeAudioAttributes, false)
            .build()
            .also { created ->
                outgoingFadePlayer = created
            }
    }

    private fun launchOverlapCrossfade(
        primary: ExoPlayer,
        outgoingItem: MediaItem,
        outgoingPositionMs: Long,
        outgoingDurationMs: Long,
        speed: Float,
        configuredSeconds: Int,
    ) {
        activeCrossfadeJob?.cancel()
        activeCrossfadeJob = scope.launch {
            val outPlayer = getOrCreateOutgoingFadePlayer()
            val remainingWallMs = PlaybackTuningHelper.remainingWallClockMs(
                durationMs = outgoingDurationMs,
                positionMs = outgoingPositionMs,
                playbackSpeed = speed,
            )
            val fadeDurationMs = remainingWallMs.coerceIn(300L, configuredSeconds * 1000L)

            try {
                val itemId = outgoingItem.mediaId.ifBlank {
                    outgoingItem.localConfiguration?.uri?.toString().orEmpty()
                }
                if (preloadedMediaId != itemId || outPlayer.mediaItemCount == 0) {
                    preloadedMediaId = itemId
                    outPlayer.stop()
                    outPlayer.setMediaItem(outgoingItem)
                    outPlayer.prepare()
                }
                val pitch = primary.playbackParameters.pitch
                    .takeIf { it.isFinite() && it >= 0.5f }
                    ?: 1.0f
                outPlayer.playbackParameters = PlaybackParameters(speed, pitch)
                outPlayer.volume = 1f
                outPlayer.seekTo(outgoingPositionMs.coerceAtLeast(0L))
                outPlayer.play()

                // Adelanta el reproductor principal a la pista siguiente empezando desde volumen 0
                // mientras el deck auxiliar desvanece la cola de la canción saliente.
                isAdvancingTransition = true
                try {
                    primary.volume = 0f
                    primary.seekToNextMediaItem()
                    if (primary.playbackState == Player.STATE_IDLE) {
                        primary.prepare()
                    }
                    primary.play()
                } finally {
                    isAdvancingTransition = false
                }

                val startWallMs = SystemClock.elapsedRealtime()
                while (isActive) {
                    if (!primary.isPlaying) {
                        break
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - startWallMs
                    val progress = (elapsedMs.toFloat() / fadeDurationMs.toFloat()).coerceIn(0f, 1f)
                    val (outGain, inGain) = PlaybackTuningHelper.computeEqualPowerGains(progress)
                    outPlayer.volume = outGain
                    primary.volume = inGain
                    if (progress >= 1f) {
                        break
                    }
                    delay(35L)
                }
            } catch (_: CancellationException) {
                // Cancelación esperada al pausar, adelantar o desactivar Crossfade.
            } finally {
                primary.volume = 1f
                outPlayer.stop()
                outPlayer.clearMediaItems()
                outPlayer.volume = 1f
                preloadedMediaId = null
                if (!tuningProvider().crossfadeEnabled) {
                    outPlayer.release()
                    if (outgoingFadePlayer === outPlayer) {
                        outgoingFadePlayer = null
                    }
                }
            }
        }
    }
}
