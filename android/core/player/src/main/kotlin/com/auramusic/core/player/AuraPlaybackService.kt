package com.auramusic.core.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.auramusic.core.model.AudioEffectsState
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * Contrato desacoplado para que `:app` suministre al servicio Media3 el flujo reactivo
 * de configuración de Crossfade, velocidad y efectos Reverb/Slowed guardado en DataStore
 * y el Intent de apertura del reproductor sin acoplar `:core:player` con `:core:database` ni `:app`.
 */
interface AuraPlaybackTuningProvider {
    fun observePlaybackTuning(): Flow<PlaybackTuning>
    fun observeAudioEffects(): Flow<AudioEffectsState> = emptyFlow()
    fun createOpenPlayerIntent(): Intent? = null
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
    private var currentEffects = AudioEffectsState()
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
                outgoingFadePlayer?.playbackParameters = PlaybackParameters(
                    playbackParameters.speed,
                    playbackParameters.pitch,
                )
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

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply {
                    setSmallIcon(R.drawable.ic_aura_notification)
                },
        )

        val sessionPlayer = SessionControlForwardingPlayer(player)
        val sessionBuilder = MediaSession.Builder(this, sessionPlayer)
        val launchIntent = (applicationContext as? AuraPlaybackTuningProvider)
            ?.createOpenPlayerIntent()
            ?: packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                1001,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            sessionBuilder.setSessionActivity(pendingIntent)
        }

        mediaSession = sessionBuilder.build()

        observeTuningPreferences()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun applyResolvedPlaybackParameters() {
        val resolved = PlaybackTuningHelper.resolvePlaybackParameters(currentTuning, currentEffects)
        primaryPlayer?.let { player ->
            val current = player.playbackParameters
            if (kotlin.math.abs(current.speed - resolved.speed) > 0.005f ||
                kotlin.math.abs(current.pitch - resolved.pitch) > 0.005f
            ) {
                player.playbackParameters = PlaybackParameters(resolved.speed, resolved.pitch)
            }
        }
        if (activeCrossfadeJob?.isActive == true) {
            outgoingFadePlayer?.playbackParameters = PlaybackParameters(resolved.speed, resolved.pitch)
        }
    }

    private fun observeTuningPreferences() {
        val provider = applicationContext as? AuraPlaybackTuningProvider ?: return
        serviceScope.launch {
            provider.observePlaybackTuning().collect { tuning ->
                val normalized = tuning.normalized()
                currentTuning = normalized
                applyResolvedPlaybackParameters()

                if (normalized.crossfadeEnabled) {
                    startCrossfadeMonitorIfNeeded()
                } else {
                    stopCrossfadeMonitor()
                    cancelActiveCrossfade("disabled")
                }
            }
        }
        serviceScope.launch {
            provider.observeAudioEffects().collect { effects ->
                currentEffects = effects.normalized()
                applyResolvedPlaybackParameters()
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
                val pitch = primary.playbackParameters.pitch
                    .takeIf { it.isFinite() && it >= 0.5f }
                    ?: 1.0f
                outPlayer.playbackParameters = PlaybackParameters(speed, pitch)
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

    /**
     * Envoltorio de [ForwardingPlayer] exclusivamente para la [MediaSession] del sistema Android.
     * Garantiza que los comandos de pista anterior y siguiente estén siempre expuestos para que
     * el reproductor de la barra de notificaciones / pantalla de bloqueo muestre los 3 botones
     * (`Anterior`, `Play/Pausa` y `Siguiente`) de forma simétrica incluso con 1 pista en cola,
     * sin alterar la lógica interna de Crossfade sobre `primaryPlayer`.
     */
    @OptIn(UnstableApi::class)
    private class SessionControlForwardingPlayer(
        private val wrapped: ExoPlayer,
    ) : ForwardingPlayer(wrapped) {

        private val listenerWrappers = IdentityHashMap<Player.Listener, Player.Listener>()

        private fun extendCommands(base: Player.Commands): Player.Commands {
            if (wrapped.mediaItemCount <= 0) return base
            return base.buildUpon()
                .addAll(
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                )
                .build()
        }

        override fun getAvailableCommands(): Player.Commands {
            return extendCommands(super.getAvailableCommands())
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> wrapped.mediaItemCount > 0 || super.isCommandAvailable(command)
                else -> super.isCommandAvailable(command)
            }
        }

        override fun addListener(listener: Player.Listener) {
            val wrapper = synchronized(listenerWrappers) {
                listenerWrappers.getOrPut(listener) {
                    ExtendedCommandsListener.createProxy(listener, ::extendCommands)
                }
            }
            super.addListener(wrapper)
        }

        override fun removeListener(listener: Player.Listener) {
            val wrapper = synchronized(listenerWrappers) {
                listenerWrappers.remove(listener)
            }
            super.removeListener(wrapper ?: listener)
        }

        override fun seekToNext() {
            if (wrapped.hasNextMediaItem()) {
                super.seekToNext()
            } else if (wrapped.mediaItemCount > 0) {
                wrapped.seekTo(0, 0L)
                wrapped.play()
            }
        }

        override fun seekToNextMediaItem() {
            if (wrapped.hasNextMediaItem()) {
                super.seekToNextMediaItem()
            } else if (wrapped.mediaItemCount > 0) {
                wrapped.seekTo(0, 0L)
                wrapped.play()
            }
        }

        override fun seekToPrevious() {
            if (wrapped.currentPosition > 3_500L) {
                wrapped.seekTo(0L)
            } else if (wrapped.hasPreviousMediaItem()) {
                super.seekToPrevious()
            } else if (wrapped.mediaItemCount > 0) {
                wrapped.seekTo(0L)
            }
        }

        override fun seekToPreviousMediaItem() {
            if (wrapped.currentPosition > 3_500L) {
                wrapped.seekTo(0L)
            } else if (wrapped.hasPreviousMediaItem()) {
                super.seekToPreviousMediaItem()
            } else if (wrapped.mediaItemCount > 0) {
                wrapped.seekTo(0L)
            }
        }
    }

    /**
     * Proxy dinámico de [Player.Listener] para que MediaSession reciba TODOS los callbacks
     * del reproductor (`onPlaybackStateChanged`, `onIsPlayingChanged`, `onEvents`, etc.) además
     * de extender `onAvailableCommandsChanged` con los botones Anterior/Siguiente.
     *
     * Nota técnica: se usa [Proxy] en lugar de la delegación de interfaz de Kotlin (`by`)
     * sobre `Player.Listener` porque Kotlin no delega los métodos `default` de Java (KT-18324),
     * lo que provocaba que MediaSession y el MiniPlayer quedaran congelados en `STATE_BUFFERING`.
     */
    private class ExtendedCommandsListener(
        private val delegate: Player.Listener,
        private val extend: (Player.Commands) -> Player.Commands,
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> {
                        val other = args?.firstOrNull()
                        if (other === proxy) {
                            true
                        } else if (other != null && Proxy.isProxyClass(other.javaClass)) {
                            val otherHandler = Proxy.getInvocationHandler(other) as? ExtendedCommandsListener
                            otherHandler?.delegate == delegate
                        } else {
                            delegate == other
                        }
                    }
                    "hashCode" -> delegate.hashCode()
                    "toString" -> delegate.toString()
                    else -> if (args == null) method.invoke(delegate) else method.invoke(delegate, *args)
                }
            }

            // Intercepta `onAvailableCommandsChanged` (único callback con parámetro `Player.Commands`)
            // de forma segura tanto en Debug como en Release con R8.
            val paramTypes = method.parameterTypes
            if (method.name == "onAvailableCommandsChanged" ||
                (paramTypes.size == 1 && paramTypes[0] == Player.Commands::class.java)
            ) {
                val commands = args?.firstOrNull() as? Player.Commands
                if (commands != null) {
                    delegate.onAvailableCommandsChanged(extend(commands))
                    return null
                }
            }

            return try {
                if (args == null) {
                    method.invoke(delegate)
                } else {
                    method.invoke(delegate, *args)
                }
            } catch (error: InvocationTargetException) {
                throw error.cause ?: error
            }
        }

        companion object {
            fun createProxy(
                delegate: Player.Listener,
                extend: (Player.Commands) -> Player.Commands,
            ): Player.Listener {
                return Proxy.newProxyInstance(
                    Player.Listener::class.java.classLoader,
                    arrayOf(Player.Listener::class.java),
                    ExtendedCommandsListener(delegate, extend),
                ) as Player.Listener
            }
        }
    }
}


