package com.auramusic.core.player

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.HeadphoneRoutingHelper
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano de Media3 que orquesta la reproducción en el proceso aislado `:player`.
 *
 * Delega sus responsabilidades especializadas en módulos dedicados:
 * - [AuraCrossfadeEngine]: transición equal-power y ciclo de vida del deck saliente.
 * - [AuraSleepTimerEngine]: temporizador de apagado y parada al terminar pista/cola.
 * - [SessionControlForwardingPlayer]: controles simétricos y saltos AVRCP para MediaSession.
 * - [AuraMediaSessionCallback]: comandos IPC y gestos de botones cableados/Bluetooth.
 */
class AuraPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var primaryPlayer: ExoPlayer? = null
    private val rustEqualizerProcessor = RustEqualizerAudioProcessor()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentTuning = PlaybackTuning()
    private var currentEffects = AudioEffectsState()
    private var currentHeadphonePrefs = HeadphonePreferences()
    private var currentOutputRoute = AudioOutputRouteState()
    private var previousHeadphonesConnected: Boolean? = null
    private var wasPausedByHeadphoneDisconnect = false
    private var lastPausedElapsedMs = 0L

    private lateinit var crossfadeEngine: AuraCrossfadeEngine
    private lateinit var sleepTimerEngine: AuraSleepTimerEngine
    private lateinit var sessionCallback: AuraMediaSessionCallback

    private val primaryListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            primaryPlayer?.let { sleepTimerEngine.lastObservedMediaIndex = it.currentMediaItemIndex }
            if (isPlaying) {
                wasPausedByHeadphoneDisconnect = false
                crossfadeEngine.startMonitorIfNeeded()
            } else {
                lastPausedElapsedMs = SystemClock.elapsedRealtime()
                crossfadeEngine.cancelActive("paused")
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            primaryPlayer?.let { sleepTimerEngine.lastObservedMediaIndex = it.currentMediaItemIndex }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                sleepTimerEngine.handlePlaybackEnded()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = primaryPlayer
            val previousIndex = sleepTimerEngine.lastObservedMediaIndex
            val newIndex = player?.currentMediaItemIndex ?: C.INDEX_UNSET
            sleepTimerEngine.lastObservedMediaIndex = newIndex

            if (!crossfadeEngine.isAdvancingTransition) {
                crossfadeEngine.clearPreloadedMediaId()
                crossfadeEngine.cancelActive("item_changed")
            }

            if (!crossfadeEngine.isAdvancingTransition &&
                !sleepTimerEngine.isStoppingPlayback &&
                player != null
            ) {
                sleepTimerEngine.handleAutoTransitionIfNeeded(
                    previousIndex = previousIndex,
                    newIndex = newIndex,
                    itemCount = player.mediaItemCount,
                    reasonIsAuto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    reasonIsRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                )
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (!crossfadeEngine.isAdvancingTransition && reason == Player.DISCONTINUITY_REASON_SEEK) {
                crossfadeEngine.cancelActive("manual_seek")
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            crossfadeEngine.syncOutgoingPlaybackParameters(
                speed = playbackParameters.speed,
                pitch = playbackParameters.pitch,
            )
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

        // Buffer acotado optimizado para pistas de audio locales (evita reservar ~15 MB de vídeo).
        val primaryLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 20_000, 500, 1_500)
            .setTargetBufferBytes(4 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(primaryLoadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(primaryListener)
        primaryPlayer = player

        sleepTimerEngine = AuraSleepTimerEngine(
            scope = serviceScope,
            primaryPlayerProvider = { primaryPlayer },
            isCrossfadeActive = { crossfadeEngine.isCrossfadeActive },
            onCancelActiveCrossfade = { reason -> crossfadeEngine.cancelActive(reason) },
            onPushSessionExtras = { extras -> mediaSession?.setSessionExtras(extras) },
        )

        crossfadeEngine = AuraCrossfadeEngine(
            context = this,
            scope = serviceScope,
            primaryPlayerProvider = { primaryPlayer },
            tuningProvider = { currentTuning },
            sleepTimerStateProvider = { sleepTimerEngine.currentState },
        )

        sessionCallback = AuraMediaSessionCallback(
            scope = serviceScope,
            tuningProvider = { applicationContext as? AuraPlaybackTuningProvider },
            headphonePrefsProvider = { currentHeadphonePrefs },
            sleepTimerStateProvider = { sleepTimerEngine.currentState },
            sleepTimerEngine = sleepTimerEngine,
            onSyncEqualizer = { eq ->
                serviceScope.launch(Dispatchers.Default) {
                    // Pasa la lista de ganancias directamente al puente Rust JNI (List<Float>).
                    AuraRustBridge.setEqualizerConfig(eq.isEnabled, eq.bandGainsDb)
                }
            },
            onSyncAudioEffects = { fx ->
                currentEffects = fx
                applyResolvedPlaybackParameters()
                syncEffectiveAudioEffectsToRust()
            },
            onSyncPlaybackTuning = ::applyPlaybackTuningUpdate,
            onSyncHeadphonePrefs = { prefs ->
                currentHeadphonePrefs = prefs
                primaryPlayer?.setHandleAudioBecomingNoisy(prefs.autoPauseOnDisconnect)
                syncEffectiveAudioEffectsToRust()
            },
        )

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
            .setCallback(sessionCallback)
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            crossfadeEngine.releaseOutgoingIfIdle()
        }
    }

    override fun onDestroy() {
        sessionCallback.release()
        sleepTimerEngine.release()
        crossfadeEngine.release()
        serviceScope.cancel()
        primaryPlayer?.removeListener(primaryListener)
        primaryPlayer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun applyPlaybackTuningUpdate(tuning: PlaybackTuning) {
        val normalized = tuning.normalized()
        currentTuning = normalized
        applyResolvedPlaybackParameters()
        if (normalized.crossfadeEnabled) {
            crossfadeEngine.startMonitorIfNeeded()
        } else {
            crossfadeEngine.stopMonitor()
            crossfadeEngine.cancelActive("disabled")
            crossfadeEngine.releaseOutgoingIfIdle()
        }
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
        crossfadeEngine.syncOutgoingPlaybackParameters(resolved.speed, resolved.pitch)
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
        serviceScope.launch(Dispatchers.Default) {
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
                (wasPausedByHeadphoneDisconnect ||
                    player.playbackState == Player.STATE_READY ||
                    player.playbackState == Player.STATE_IDLE)
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
            provider.observePlaybackTuning().collect(::applyPlaybackTuningUpdate)
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
}
