package com.auramusic.core.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.HeadphoneRoutingHelper
import com.auramusic.core.model.MediaHookTapAction
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.model.SleepTimerHelper
import com.auramusic.core.model.SleepTimerMode
import com.auramusic.core.model.SleepTimerState
import com.auramusic.core.rust.AuraRustBridge
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
}

/**
 * Servicio en primer plano de Media3 que mantiene vivo a ExoPlayer cuando la app
 * pasa a segundo plano o se apaga la pantalla, integrándose con los controles multimedia
 * del sistema Android (pantalla de bloqueo, barra de notificaciones, auriculares cableados
 * y Bluetooth AVRCP), aplicando el ecualizador de 10 bandas y efectos espaciales en Rust,
 * el control de velocidad y la transición Crossfade suave entre canciones.
 */
class AuraPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var primaryPlayer: ExoPlayer? = null
    private var outgoingFadePlayer: ExoPlayer? = null
    private val rustEqualizerProcessor = RustEqualizerAudioProcessor()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentTuning = PlaybackTuning()
    private var currentEffects = AudioEffectsState()
    private var currentHeadphonePrefs = HeadphonePreferences()
    private var currentOutputRoute = AudioOutputRouteState()
    private var previousHeadphonesConnected: Boolean? = null
    private var wasPausedByHeadphoneDisconnect = false
    private var lastPausedElapsedMs = 0L

    private var headsetHookTapCount = 0
    private var headsetHookDispatchJob: Job? = null

    private var crossfadeMonitorJob: Job? = null
    private var activeCrossfadeJob: Job? = null
    private var preloadedMediaId: String? = null
    private var internalCrossfadeAdvancing = false

    private var sleepTimerState = SleepTimerState()
    private var sleepTimerJob: Job? = null
    private var sleepTimerEndElapsedMs: Long = 0L
    private var lastObservedMediaIndex: Int = C.INDEX_UNSET
    private var internalSleepTimerStopping = false

    private val primaryListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            primaryPlayer?.let { lastObservedMediaIndex = it.currentMediaItemIndex }
            if (isPlaying) {
                wasPausedByHeadphoneDisconnect = false
                startCrossfadeMonitorIfNeeded()
            } else {
                lastPausedElapsedMs = SystemClock.elapsedRealtime()
                cancelActiveCrossfade("paused")
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            primaryPlayer?.let { lastObservedMediaIndex = it.currentMediaItemIndex }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED &&
                (sleepTimerState.mode == SleepTimerMode.END_OF_TRACK ||
                    sleepTimerState.mode == SleepTimerMode.END_OF_QUEUE)
            ) {
                cancelSleepTimer()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = primaryPlayer
            val previousIndex = lastObservedMediaIndex
            val newIndex = player?.currentMediaItemIndex ?: C.INDEX_UNSET
            lastObservedMediaIndex = newIndex

            if (!internalCrossfadeAdvancing) {
                preloadedMediaId = null
                cancelActiveCrossfade("item_changed")
            }

            if (!internalCrossfadeAdvancing && !internalSleepTimerStopping && player != null) {
                val reasonIsAuto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                val reasonIsRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if ((reasonIsAuto || reasonIsRepeat) &&
                    SleepTimerHelper.shouldPauseOnAutoTransition(
                        mode = sleepTimerState.mode,
                        previousIndex = previousIndex,
                        newIndex = newIndex,
                        itemCount = player.mediaItemCount,
                        reasonIsAuto = reasonIsAuto,
                        reasonIsRepeat = reasonIsRepeat,
                    )
                ) {
                    completeSleepTimerAndPause(seekBackToStartOfNewItem = true)
                }
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

        val sessionPlayer = SessionControlForwardingPlayer(
            wrapped = player,
            headphonePrefsProvider = { currentHeadphonePrefs },
        )
        val sessionBuilder = MediaSession.Builder(this, sessionPlayer)
            .setCallback(AuraMediaSessionCallback())
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

    private fun syncEffectiveAudioEffectsToRust() {
        val effectsSnapshot = currentEffects
        val routeSnapshot = currentOutputRoute
        val prefsSnapshot = currentHeadphonePrefs
        val effective = HeadphoneRoutingHelper.resolveEffectiveRustEffects(
            effects = effectsSnapshot,
            outputRoute = routeSnapshot,
            prefs = prefsSnapshot,
        )
        serviceScope.launch {
            AuraRustBridge.setAudioEffectsConfig(
                reverbEnabled = effectsSnapshot.reverbEnabled,
                reverbRoomSize = effectsSnapshot.reverbRoomSize,
                reverbDamping = effectsSnapshot.reverbDamping,
                reverbWetMix = effectsSnapshot.reverbWetMix,
                reverbWidth = effectsSnapshot.reverbWidth,
                slowedWarmthEnabled = effectsSnapshot.isSlowedWarmthActive,
                slowedWarmthAmount = effectsSnapshot.slowedWarmth,
                spatial8dEnabled = effective.spatial8dEnabled,
                spatial8dSpeed = effectsSnapshot.spatial8dSpeed,
                spatial8dDepth = effectsSnapshot.spatial8dDepth,
                crossfeedEnabled = effective.crossfeedEnabled,
                crossfeedStrength = effectsSnapshot.crossfeedStrength,
                crossfeedAngle = effectsSnapshot.crossfeedAngle,
            )
        }
    }

    private fun handleOutputRouteChanged(nextRoute: AudioOutputRouteState) {
        currentOutputRoute = nextRoute
        val prevConnected = previousHeadphonesConnected
        previousHeadphonesConnected = nextRoute.isHeadphonesConnected

        syncEffectiveAudioEffectsToRust()

        // Omitimos la emisión inicial al arrancar el servicio para no disparar pausa/reanudación fantasma.
        if (prevConnected == null || prevConnected == nextRoute.isHeadphonesConnected) {
            return
        }

        val player = primaryPlayer ?: return
        if (prevConnected && !nextRoute.isHeadphonesConnected) {
            val pausedJustNowByNoisy =
                !player.isPlaying && (SystemClock.elapsedRealtime() - lastPausedElapsedMs) < 1_500L
            if (player.isPlaying) {
                if (currentHeadphonePrefs.autoPauseOnDisconnect) {
                    wasPausedByHeadphoneDisconnect = true
                    player.pause()
                }
            } else if (pausedJustNowByNoisy && currentHeadphonePrefs.autoPauseOnDisconnect) {
                wasPausedByHeadphoneDisconnect = true
            }
        } else if (!prevConnected && nextRoute.isHeadphonesConnected) {
            if (currentHeadphonePrefs.autoResumeOnConnect &&
                player.mediaItemCount > 0 &&
                !player.isPlaying &&
                (wasPausedByHeadphoneDisconnect || player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_IDLE)
            ) {
                wasPausedByHeadphoneDisconnect = false
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                player.play()
            }
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
                syncEffectiveAudioEffectsToRust()
            }
        }
        serviceScope.launch {
            provider.observeHeadphonePreferences().collect { prefs ->
                val normalized = prefs.normalized()
                currentHeadphonePrefs = normalized
                primaryPlayer?.setHandleAudioBecomingNoisy(normalized.autoPauseOnDisconnect)
                syncEffectiveAudioEffectsToRust()
            }
        }
        serviceScope.launch {
            provider.observeAudioOutputRoute().collect { route ->
                handleOutputRouteChanged(route)
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

                    val bypassForSleepTimer = SleepTimerHelper.shouldBypassCrossfadeForSleepTimer(
                        state = sleepTimerState,
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

    private fun publishSleepTimer(state: SleepTimerState) {
        sleepTimerState = state
        AuraSleepTimerBridge.publishState(state)
    }

    private fun startSleepTimerDuration(minutes: Int, fadeOutOnFinish: Boolean) {
        val safeMinutes = SleepTimerHelper.clampCustomMinutes(minutes)
        val totalMs = safeMinutes * 60_000L
        sleepTimerJob?.cancel()
        sleepTimerEndElapsedMs = SystemClock.elapsedRealtime() + totalMs
        if (activeCrossfadeJob?.isActive != true) {
            primaryPlayer?.volume = 1f
        }
        publishSleepTimer(
            SleepTimerState(
                mode = SleepTimerMode.DURATION,
                configuredMinutes = safeMinutes,
                totalDurationMs = totalMs,
                remainingMs = totalMs,
                fadeOutOnFinish = fadeOutOnFinish,
            ),
        )
        launchSleepTimerTicker()
    }

    private fun launchSleepTimerTicker() {
        sleepTimerJob?.cancel()
        sleepTimerJob = serviceScope.launch {
            while (isActive && sleepTimerState.mode == SleepTimerMode.DURATION) {
                val remainingMs = (sleepTimerEndElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (remainingMs <= 0L) {
                    completeSleepTimerAndPause(seekBackToStartOfNewItem = false)
                    break
                }

                if (sleepTimerState.fadeOutOnFinish &&
                    remainingMs <= 3_000L &&
                    primaryPlayer?.isPlaying == true &&
                    activeCrossfadeJob?.isActive != true
                ) {
                    primaryPlayer?.volume = (remainingMs.toFloat() / 3_000f).coerceIn(0.05f, 1f)
                }

                publishSleepTimer(sleepTimerState.copy(remainingMs = remainingMs))
                delay(250L)
            }
        }
    }

    private fun addSleepTimerMinutes(deltaMinutes: Int) {
        if (sleepTimerState.mode != SleepTimerMode.DURATION) return
        val now = SystemClock.elapsedRealtime()
        val currentRemaining = (sleepTimerEndElapsedMs - now).coerceAtLeast(0L)
        val updatedRemaining = (currentRemaining + deltaMinutes * 60_000L)
            .coerceAtMost(SleepTimerHelper.MAX_CUSTOM_MINUTES * 60_000L)
        if (updatedRemaining <= 0L) {
            cancelSleepTimer()
            return
        }
        sleepTimerEndElapsedMs = now + updatedRemaining
        if (updatedRemaining > 3_000L && activeCrossfadeJob?.isActive != true) {
            primaryPlayer?.volume = 1f
        }
        val nextTotal = maxOf(sleepTimerState.totalDurationMs, updatedRemaining)
        val nextMinutes = ((updatedRemaining + 59_999L) / 60_000L).toInt()
            .coerceIn(SleepTimerHelper.MIN_CUSTOM_MINUTES, SleepTimerHelper.MAX_CUSTOM_MINUTES)
        publishSleepTimer(
            sleepTimerState.copy(
                configuredMinutes = nextMinutes,
                totalDurationMs = nextTotal,
                remainingMs = updatedRemaining,
            ),
        )
    }

    private fun setSleepTimerEndMode(mode: SleepTimerMode, fadeOutOnFinish: Boolean) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndElapsedMs = 0L
        if (activeCrossfadeJob?.isActive != true) {
            primaryPlayer?.volume = 1f
        }
        lastObservedMediaIndex = primaryPlayer?.currentMediaItemIndex ?: C.INDEX_UNSET
        publishSleepTimer(
            SleepTimerState(
                mode = mode,
                fadeOutOnFinish = fadeOutOnFinish,
            ),
        )
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndElapsedMs = 0L
        if (activeCrossfadeJob?.isActive != true) {
            primaryPlayer?.volume = 1f
        }
        publishSleepTimer(SleepTimerState())
    }

    private fun completeSleepTimerAndPause(seekBackToStartOfNewItem: Boolean) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndElapsedMs = 0L
        internalSleepTimerStopping = true
        try {
            cancelActiveCrossfade("sleep_timer_finished")
            primaryPlayer?.let { player ->
                player.pause()
                if (seekBackToStartOfNewItem && player.mediaItemCount > 0) {
                    player.seekTo(0L)
                }
                player.volume = 1f
            }
        } finally {
            internalSleepTimerStopping = false
        }
        publishSleepTimer(SleepTimerState())
    }

    override fun onDestroy() {
        headsetHookDispatchJob?.cancel()
        headsetHookDispatchJob = null
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        AuraSleepTimerBridge.publishState(SleepTimerState())
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

    private fun handleHeadsetHookTap(player: Player) {
        val prefs = currentHeadphonePrefs
        if (!prefs.doubleTapNextEnabled && !prefs.tripleTapPreviousEnabled) {
            headsetHookDispatchJob?.cancel()
            headsetHookTapCount = 0
            executeMediaHookTap(1, player)
            return
        }

        headsetHookTapCount += 1
        headsetHookDispatchJob?.cancel()

        val currentCount = headsetHookTapCount
        if (currentCount >= 3 && prefs.tripleTapPreviousEnabled) {
            headsetHookTapCount = 0
            executeMediaHookTap(currentCount, player)
            return
        }
        if (currentCount == 2 && !prefs.tripleTapPreviousEnabled && prefs.doubleTapNextEnabled) {
            headsetHookTapCount = 0
            executeMediaHookTap(currentCount, player)
            return
        }

        headsetHookDispatchJob = serviceScope.launch {
            delay(320L)
            val finalizedCount = headsetHookTapCount
            headsetHookTapCount = 0
            executeMediaHookTap(finalizedCount, player)
        }
    }

    private fun executeMediaHookTap(tapCount: Int, player: Player) {
        when (HeadphoneRoutingHelper.resolveMediaHookTapAction(tapCount, currentHeadphonePrefs)) {
            MediaHookTapAction.TOGGLE_PLAY_PAUSE -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                        player.prepare()
                    }
                    player.play()
                }
            }
            MediaHookTapAction.SKIP_NEXT -> player.seekToNext()
            MediaHookTapAction.SKIP_PREVIOUS -> player.seekToPrevious()
            MediaHookTapAction.NONE -> Unit
        }
    }

    /**
     * Intercepta eventos de botones físicos cableados (`KEYCODE_HEADSETHOOK`) y comandos
     * Bluetooth AVRCP (`NEXT`, `PREVIOUS`, `FAST_FORWARD`, `REWIND`) para aplicar los
     * gestos de doble/triple toque y pasos configurados por el usuario.
     */
    @OptIn(UnstableApi::class)
    private inner class AuraMediaSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val baseResult = super.onConnect(session, controller)
            val commandsBuilder = baseResult.availableSessionCommands.buildUpon()
            AuraSleepTimerContract.supportedActions.forEach { action ->
                commandsBuilder.add(SessionCommand(action, Bundle.EMPTY))
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commandsBuilder.build())
                .setAvailablePlayerCommands(baseResult.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                AuraSleepTimerContract.ACTION_START_MINUTES -> {
                    val minutes = args.getInt(AuraSleepTimerContract.EXTRA_MINUTES, 15)
                    val fadeOut = args.getBoolean(AuraSleepTimerContract.EXTRA_FADE_OUT, true)
                    startSleepTimerDuration(minutes, fadeOut)
                }
                AuraSleepTimerContract.ACTION_SET_END_OF_TRACK -> {
                    val fadeOut = args.getBoolean(AuraSleepTimerContract.EXTRA_FADE_OUT, true)
                    setSleepTimerEndMode(SleepTimerMode.END_OF_TRACK, fadeOut)
                }
                AuraSleepTimerContract.ACTION_SET_END_OF_QUEUE -> {
                    val fadeOut = args.getBoolean(AuraSleepTimerContract.EXTRA_FADE_OUT, true)
                    setSleepTimerEndMode(SleepTimerMode.END_OF_QUEUE, fadeOut)
                }
                AuraSleepTimerContract.ACTION_ADD_MINUTES -> {
                    val delta = args.getInt(AuraSleepTimerContract.EXTRA_DELTA_MINUTES, 5)
                    addSleepTimerMinutes(delta)
                }
                AuraSleepTimerContract.ACTION_CANCEL -> cancelSleepTimer()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }
            val keyEvent = IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_KEY_EVENT,
                KeyEvent::class.java,
            ) ?: return super.onMediaButtonEvent(session, controllerInfo, intent)

            val player = session.player
            return when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                        handleHeadsetHookTap(player)
                    }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                        player.seekToNext()
                    }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                        player.seekToPrevious()
                    }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                        player.seekForward()
                    }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                        player.seekBack()
                    }
                    true
                }
                else -> super.onMediaButtonEvent(session, controllerInfo, intent)
            }
        }
    }

    /**
     * Envoltorio de [ForwardingPlayer] exclusivamente para la [MediaSession] del sistema Android.
     * Garantiza que los comandos de pista anterior, siguiente y salto rápido AVRCP estén siempre
     * expuestos con el umbral de reinicio, navegación circular y paso de segundos configurados,
     * sin alterar la lógica interna de Crossfade sobre `primaryPlayer`.
     */
    @OptIn(UnstableApi::class)
    private class SessionControlForwardingPlayer(
        private val wrapped: ExoPlayer,
        private val headphonePrefsProvider: () -> HeadphonePreferences,
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
                    COMMAND_SEEK_FORWARD,
                    COMMAND_SEEK_BACK,
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
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_FORWARD,
                COMMAND_SEEK_BACK -> wrapped.mediaItemCount > 0 || super.isCommandAvailable(command)
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

        override fun getSeekForwardIncrement(): Long {
            return HeadphoneRoutingHelper.clampSeekStepSeconds(
                headphonePrefsProvider().seekStepSeconds,
            ) * 1000L
        }

        override fun getSeekBackIncrement(): Long {
            return HeadphoneRoutingHelper.clampSeekStepSeconds(
                headphonePrefsProvider().seekStepSeconds,
            ) * 1000L
        }

        override fun seekForward() {
            val incrementMs = getSeekForwardIncrement()
            val duration = wrapped.duration
            val target = if (duration > 0L) {
                (wrapped.currentPosition + incrementMs).coerceAtMost(duration)
            } else {
                wrapped.currentPosition + incrementMs
            }
            wrapped.seekTo(target)
        }

        override fun seekBack() {
            val incrementMs = getSeekBackIncrement()
            val target = (wrapped.currentPosition - incrementMs).coerceAtLeast(0L)
            wrapped.seekTo(target)
        }

        override fun seekToNext() {
            if (wrapped.hasNextMediaItem()) {
                super.seekToNext()
            } else if (headphonePrefsProvider().wrapQueueNavigation && wrapped.mediaItemCount > 0) {
                wrapped.seekTo(0, 0L)
                wrapped.play()
            }
        }

        override fun seekToNextMediaItem() {
            if (wrapped.hasNextMediaItem()) {
                super.seekToNextMediaItem()
            } else if (headphonePrefsProvider().wrapQueueNavigation && wrapped.mediaItemCount > 0) {
                wrapped.seekTo(0, 0L)
                wrapped.play()
            }
        }

        override fun seekToPrevious() {
            val prefs = headphonePrefsProvider()
            val thresholdMs = HeadphoneRoutingHelper.clampPreviousRestartThresholdMs(
                prefs.previousRestartThresholdMs,
            )
            if (thresholdMs > 0L && wrapped.currentPosition > thresholdMs) {
                wrapped.seekTo(0L)
            } else if (wrapped.hasPreviousMediaItem()) {
                super.seekToPrevious()
            } else if (prefs.wrapQueueNavigation && wrapped.mediaItemCount > 1) {
                wrapped.seekTo(wrapped.mediaItemCount - 1, 0L)
                wrapped.play()
            } else if (wrapped.mediaItemCount > 0) {
                wrapped.seekTo(0L)
            }
        }

        override fun seekToPreviousMediaItem() {
            val prefs = headphonePrefsProvider()
            val thresholdMs = HeadphoneRoutingHelper.clampPreviousRestartThresholdMs(
                prefs.previousRestartThresholdMs,
            )
            if (thresholdMs > 0L && wrapped.currentPosition > thresholdMs) {
                wrapped.seekTo(0L)
            } else if (wrapped.hasPreviousMediaItem()) {
                super.seekToPreviousMediaItem()
            } else if (prefs.wrapQueueNavigation && wrapped.mediaItemCount > 1) {
                wrapped.seekTo(wrapped.mediaItemCount - 1, 0L)
                wrapped.play()
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


