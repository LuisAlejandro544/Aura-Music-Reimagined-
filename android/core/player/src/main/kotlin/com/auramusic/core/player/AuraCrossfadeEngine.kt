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
                        val preloadWindowMs = fadeSeconds * 1000L + 4_000L
                        val itemId = currentItem.mediaId.ifBlank {
                            currentItem.localConfiguration?.uri?.toString().orEmpty()
                        }

                        // Prepara por adelantado la pista actual en el deck saliente (silenciado) antes del inicio del fade.
                        if (wallRemainingMs in 400L..preloadWindowMs && preloadedMediaId != itemId) {
                            preloadedMediaId = itemId
                            val pitch = player.playbackParameters.pitch
                                .takeIf { it.isFinite() && it >= 0.5f }
                                ?: 1.0f
                            val outPlayer = getOrCreateOutgoingFadePlayer()
                            outPlayer.stop()
                            outPlayer.setMediaItem(currentItem)
                            outPlayer.playbackParameters = PlaybackParameters(effectiveSpeed, pitch)
                            outPlayer.volume = 0f
                            outPlayer.playWhenReady = false
                            outPlayer.prepare()
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

                delay(100L)
            }
        }
    }

    fun stopMonitor() {
        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = null
    }

    fun cancelActive(
        @Suppress("UNUSED_PARAMETER") reason: String,
    ) {
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

            try {
                val itemId = outgoingItem.mediaId.ifBlank {
                    outgoingItem.localConfiguration?.uri?.toString().orEmpty()
                }
                if (preloadedMediaId != itemId ||
                    outPlayer.mediaItemCount == 0 ||
                    outPlayer.playbackState == Player.STATE_IDLE ||
                    outPlayer.playbackState == Player.STATE_ENDED
                ) {
                    preloadedMediaId = itemId
                    outPlayer.stop()
                    outPlayer.setMediaItem(outgoingItem)
                    outPlayer.volume = 0f
                    outPlayer.playWhenReady = false
                    outPlayer.prepare()
                }

                val pitch = primary.playbackParameters.pitch
                    .takeIf { it.isFinite() && it >= 0.5f }
                    ?: 1.0f
                outPlayer.playbackParameters = PlaybackParameters(speed, pitch)

                // 1. Esperamos a que el deck saliente quede listo antes de cortar el reproductor principal.
                val readyDeadlineMs = SystemClock.elapsedRealtime() + 600L
                while (isActive &&
                    outPlayer.playbackState != Player.STATE_READY &&
                    SystemClock.elapsedRealtime() < readyDeadlineMs
                ) {
                    if (!primary.playWhenReady ||
                        primary.playbackState == Player.STATE_IDLE ||
                        primary.playbackState == Player.STATE_ENDED
                    ) {
                        return@launch
                    }
                    delay(15L)
                }
                if (outPlayer.playbackState != Player.STATE_READY) {
                    return@launch
                }

                // 2. Sincronizamos el deck saliente (en silencio) a la posición en vivo antes del relevo.
                val livePosMs = primary.currentPosition.coerceAtLeast(outgoingPositionMs)
                val seekLeadTrackMs = (80L * speed).toLong()
                val maxSafeSeekMs = (outgoingDurationMs - 250L).coerceAtLeast(0L)
                val handoffPosMs = (livePosMs + seekLeadTrackMs).coerceIn(0L, maxSafeSeekMs)
                outPlayer.volume = 0f
                outPlayer.seekTo(handoffPosMs)
                outPlayer.play()

                val playWarmupDeadlineMs = SystemClock.elapsedRealtime() + 450L
                while (isActive &&
                    !(outPlayer.isPlaying && outPlayer.playbackState == Player.STATE_READY) &&
                    SystemClock.elapsedRealtime() < playWarmupDeadlineMs
                ) {
                    if (!primary.playWhenReady ||
                        primary.playbackState == Player.STATE_IDLE ||
                        primary.playbackState == Player.STATE_ENDED
                    ) {
                        return@launch
                    }
                    val liveRemainingWall = PlaybackTuningHelper.remainingWallClockMs(
                        durationMs = outgoingDurationMs,
                        positionMs = primary.currentPosition,
                        playbackSpeed = speed,
                    )
                    if (liveRemainingWall in 0L..299L) {
                        break
                    }
                    delay(12L)
                }

                if (!primary.playWhenReady ||
                    primary.playbackState == Player.STATE_IDLE ||
                    primary.playbackState == Player.STATE_ENDED
                ) {
                    return@launch
                }

                // 3. Relevo limpio: el deck saliente asume volumen 1.0 y el reproductor principal
                // avanza al siguiente tema empezando en volumen 0.0.
                outPlayer.volume = 1f
                primary.volume = 0f
                isAdvancingTransition = true
                try {
                    primary.seekToNextMediaItem()
                    if (primary.playbackState == Player.STATE_IDLE) {
                        primary.prepare()
                    }
                    primary.play()
                } finally {
                    isAdvancingTransition = false
                }

                // 4. Esperamos brevemente a que la nueva pista termine de abrir/bufferizar sin
                // cancelar el crossfade ni cortar la canción saliente mientras tanto.
                val incomingReadyDeadlineMs = SystemClock.elapsedRealtime() + 1_200L
                while (isActive &&
                    !primary.isPlaying &&
                    SystemClock.elapsedRealtime() < incomingReadyDeadlineMs
                ) {
                    if (!primary.playWhenReady ||
                        primary.playbackState == Player.STATE_IDLE ||
                        primary.playbackState == Player.STATE_ENDED ||
                        outPlayer.playbackState == Player.STATE_ENDED
                    ) {
                        break
                    }
                    delay(16L)
                }

                // 5. Calculamos la duración real restante del deck saliente para completar el fundido
                // equal-power antes de que termine su audio.
                val outRemainingWallMs = PlaybackTuningHelper.remainingWallClockMs(
                    durationMs = outgoingDurationMs,
                    positionMs = outPlayer.currentPosition.coerceAtLeast(0L),
                    playbackSpeed = speed,
                )
                val fadeDurationMs = if (outRemainingWallMs > 0L) {
                    minOf(configuredSeconds * 1000L, outRemainingWallMs).coerceAtLeast(300L)
                } else {
                    (configuredSeconds * 1000L).coerceAtLeast(300L)
                }

                val startWallMs = SystemClock.elapsedRealtime()
                while (isActive) {
                    if (!primary.playWhenReady ||
                        primary.playbackState == Player.STATE_IDLE ||
                        primary.playbackState == Player.STATE_ENDED
                    ) {
                        break
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - startWallMs
                    val progress = (elapsedMs.toFloat() / fadeDurationMs.toFloat()).coerceIn(0f, 1f)
                    val (outGain, inGain) = PlaybackTuningHelper.computeEqualPowerGains(progress)
                    outPlayer.volume = if (outPlayer.playbackState == Player.STATE_ENDED) 0f else outGain
                    primary.volume = inGain
                    if (progress >= 1f) {
                        break
                    }
                    delay(16L)
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
