package com.auramusic.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Contrato desacoplado para que `:app` suministre al servicio Media3 el flujo reactivo
 * de configuración de Crossfade y velocidad guardado en DataStore sin acoplar `:core:player`
 * con `:core:database`.
 */
interface AuraPlaybackTuningProvider {
    fun observePlaybackTuning(): Flow<PlaybackTuning>
}

/**
 * Servicio en primer plano de Media3 que mantiene vivo a ExoPlayer cuando la app
 * pasa a segundo plano o se apaga la pantalla, integrándose con los controles multimedia
 * del sistema Android (pantalla de bloqueo, barra de notificaciones y Bluetooth), aplicando
 * el ecualizador de 10 bandas procesado en Rust, el control de velocidad y la transición
 * Crossfade suave entre canciones.
 */
class AuraPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var primaryPlayer: ExoPlayer? = null
    private var outgoingFadePlayer: ExoPlayer? = null
    private val rustEqualizerProcessor = RustEqualizerAudioProcessor()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentTuning = PlaybackTuning()
    private var crossfadeMonitorJob: Job? = null
    private var activeCrossfadeJob: Job? = null
    private var preloadedMediaId: String? = null
    private var internalCrossfadeAdvancing = false

    private val primaryListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startCrossfadeMonitorIfNeeded()
            } else {
                cancelActiveCrossfade("paused")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!internalCrossfadeAdvancing) {
                preloadedMediaId = null
                cancelActiveCrossfade("item_changed")
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (!internalCrossfadeAdvancing && reason == Player.DISCONTINUITY_REASON_SEEK) {
                cancelActiveCrossfade("manual_seek")
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (activeCrossfadeJob?.isActive == true) {
                outgoingFadePlayer?.playbackParameters = PlaybackParameters(playbackParameters.speed, 1.0f)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParameters: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParameters)
                    .setAudioProcessors(arrayOf(rustEqualizerProcessor))
                    .build()
            }
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(primaryListener)
        primaryPlayer = player

        mediaSession = MediaSession.Builder(this, player).build()

        observeTuningPreferences()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun observeTuningPreferences() {
        val provider = applicationContext as? AuraPlaybackTuningProvider ?: return
        serviceScope.launch {
            provider.observePlaybackTuning().collect { tuning ->
                val normalized = tuning.normalized()
                currentTuning = normalized

                val safeSpeed = normalized.playbackSpeed
                primaryPlayer?.let { player ->
                    if (kotlin.math.abs(player.playbackParameters.speed - safeSpeed) > 0.005f) {
                        player.playbackParameters = PlaybackParameters(safeSpeed, 1.0f)
                    }
                }
                if (activeCrossfadeJob?.isActive == true) {
                    outgoingFadePlayer?.playbackParameters = PlaybackParameters(safeSpeed, 1.0f)
                }

                if (normalized.crossfadeEnabled) {
                    startCrossfadeMonitorIfNeeded()
                } else {
                    stopCrossfadeMonitor()
                    cancelActiveCrossfade("disabled")
                }
            }
        }
    }

    private fun getOrCreateOutgoingFadePlayer(): ExoPlayer {
        outgoingFadePlayer?.let { return it }
        // El reproductor auxiliar saliente NO adjunta `RustEqualizerAudioProcessor` ni reclama
        // audio focus: así nunca se corrompe la línea de retardo IIR del ecualizador del reproductor principal.
        val fadeAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(this)
            .setAudioAttributes(fadeAudioAttributes, false)
            .build()
            .also { created ->
                outgoingFadePlayer = created
            }
    }

    private fun startCrossfadeMonitorIfNeeded() {
        if (!currentTuning.crossfadeEnabled) return
        if (crossfadeMonitorJob?.isActive == true) return

        crossfadeMonitorJob = serviceScope.launch {
            while (isActive) {
                val player = primaryPlayer
                if (player == null || !currentTuning.crossfadeEnabled) {
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
                    val fadeSeconds = currentTuning.crossfadeSeconds

                    if (currentItem != null &&
                        hasNext &&
                        !repeatOne &&
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
                            crossfadeEnabled = currentTuning.crossfadeEnabled,
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

    private fun stopCrossfadeMonitor() {
        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = null
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
        activeCrossfadeJob = serviceScope.launch {
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
                outPlayer.playbackParameters = PlaybackParameters(speed, 1.0f)
                outPlayer.volume = 1f
                outPlayer.seekTo(outgoingPositionMs.coerceAtLeast(0L))
                outPlayer.play()

                // Adelanta el reproductor principal a la pista siguiente empezando desde volumen 0
                // mientras el deck auxiliar desvanece la cola de la canción saliente.
                internalCrossfadeAdvancing = true
                try {
                    primary.volume = 0f
                    primary.seekToNextMediaItem()
                    if (primary.playbackState == Player.STATE_IDLE) {
                        primary.prepare()
                    }
                    primary.play()
                } finally {
                    internalCrossfadeAdvancing = false
                }

                val startWallMs = android.os.SystemClock.elapsedRealtime()
                while (isActive) {
                    if (!primary.isPlaying) {
                        break
                    }
                    val elapsedMs = android.os.SystemClock.elapsedRealtime() - startWallMs
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
            }
        }
    }

    private fun cancelActiveCrossfade(@Suppress("UNUSED_PARAMETER") reason: String) {
        activeCrossfadeJob?.cancel()
        activeCrossfadeJob = null
        primaryPlayer?.volume = 1f
        outgoingFadePlayer?.let { out ->
            out.stop()
            out.clearMediaItems()
            out.volume = 1f
        }
        preloadedMediaId = null
    }

    override fun onDestroy() {
        stopCrossfadeMonitor()
        cancelActiveCrossfade("service_destroy")
        serviceScope.cancel()
        outgoingFadePlayer?.release()
        outgoingFadePlayer = null
        primaryPlayer?.removeListener(primaryListener)
        primaryPlayer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

