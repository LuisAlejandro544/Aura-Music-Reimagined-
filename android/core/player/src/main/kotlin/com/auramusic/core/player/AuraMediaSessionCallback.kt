package com.auramusic.core.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import java.io.File
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.HeadphoneRoutingHelper
import com.auramusic.core.model.MediaHookTapAction
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.SleepTimerMode
import com.auramusic.core.model.SleepTimerState
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Callback modular de [MediaSession] para `AuraPlaybackService`.
 *
 * Centraliza:
 * - Restauración de `localConfiguration` (`uri`) desde `requestMetadata.mediaUri` cuando
 *   `MediaController` envía elementos de audio por Binder entre el proceso principal y `:player`.
 * - Exposición de comandos IPC personalizados (`AuraSleepTimerContract` y `AuraPlayerIpcContract`).
 * - Recepción de comandos en caliente desde el proceso UI (`MAIN`) hacia `:player`.
 * - Detección de pulsaciones simples, dobles y triples en botones cableados (`KEYCODE_HEADSETHOOK`)
 *   y eventos Bluetooth AVRCP (`NEXT`, `PREVIOUS`, `FAST_FORWARD`, `REWIND`).
 */
@OptIn(UnstableApi::class)
internal class AuraMediaSessionCallback(
    private val scope: CoroutineScope,
    private val tuningProvider: () -> AuraPlaybackTuningProvider?,
    private val headphonePrefsProvider: () -> HeadphonePreferences,
    private val sleepTimerStateProvider: () -> SleepTimerState,
    private val sleepTimerEngine: AuraSleepTimerEngine,
    private val onSyncEqualizer: (EqualizerState) -> Unit,
    private val onSyncAudioEffects: (AudioEffectsState) -> Unit,
    private val onSyncPlaybackTuning: (PlaybackTuning) -> Unit,
    private val onSyncHeadphonePrefs: (HeadphonePreferences) -> Unit,
) : MediaSession.Callback {
    private var headsetHookTapCount = 0
    private var headsetHookDispatchJob: Job? = null

    fun release() {
        headsetHookDispatchJob?.cancel()
        headsetHookDispatchJob = null
        headsetHookTapCount = 0
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val baseResult = super.onConnect(session, controller)
        val commandsBuilder = baseResult.availableSessionCommands.buildUpon()
        AuraSleepTimerContract.supportedActions.forEach { action ->
            commandsBuilder.add(SessionCommand(action, Bundle.EMPTY))
        }
        AuraPlayerIpcContract.supportedActions.forEach { action ->
            commandsBuilder.add(SessionCommand(action, Bundle.EMPTY))
        }
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(commandsBuilder.build())
            .setAvailablePlayerCommands(baseResult.availablePlayerCommands)
            .setSessionExtras(AuraPlayerIpcContract.sleepTimerToBundle(sleepTimerStateProvider()))
            .build()
    }

    /**
     * Media3 omite `MediaItem.localConfiguration` al serializar ítems por Binder desde
     * `MediaController`; aquí reconstruimos la URI reproducible usando `requestMetadata.mediaUri`
     * para que `addMediaItem` y `replaceMediaItem` funcionen en el proceso `:player`.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFuture(resolvePlayableMediaItems(mediaItems))

    /**
     * Restaura las URIs reproducibles al establecer una cola nueva completa (`setMediaItems`)
     * preservando un índice inicial válido y la posición solicitada por el controlador.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val resolvedItems = resolvePlayableMediaItems(mediaItems)
        val safeStartIndex = if (resolvedItems.isEmpty()) {
            0
        } else {
            startIndex.coerceIn(0, resolvedItems.lastIndex)
        }
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(resolvedItems, safeStartIndex, startPositionMs),
        )
    }

    private fun resolvePlayableMediaItems(
        mediaItems: List<MediaItem>,
    ): MutableList<MediaItem> =
        mediaItems.map { item ->
            val rawUri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
            val resolvedUri = if (rawUri != null && rawUri.scheme.isNullOrBlank()) {
                val path = rawUri.path ?: rawUri.toString()
                if (path.startsWith("/")) Uri.fromFile(File(path)) else rawUri
            } else {
                rawUri
            }
            if (resolvedUri != null && item.localConfiguration?.uri != resolvedUri) {
                item.buildUpon()
                    .setUri(resolvedUri)
                    .build()
            } else {
                item
            }
        }.toMutableList()

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val provider = tuningProvider()
        when (customCommand.customAction) {
            AuraSleepTimerContract.ACTION_START_MINUTES -> {
                val minutes = args.getInt(AuraSleepTimerContract.EXTRA_MINUTES, 15)
                val fadeOut = args.getBoolean(AuraSleepTimerContract.EXTRA_FADE_OUT, true)
                sleepTimerEngine.startMinutes(minutes, fadeOut)
            }
            AuraSleepTimerContract.ACTION_SET_END_OF_TRACK -> {
                val fadeOut = args.getBoolean(AuraSleepTimerContract.EXTRA_FADE_OUT, true)
                sleepTimerEngine.setEndMode(SleepTimerMode.END_OF_TRACK, fadeOut)
            }
            AuraSleepTimerContract.ACTION_SET_END_OF_QUEUE -> {
                val fadeOut = args.getBoolean(AuraSleepTimerContract.EXTRA_FADE_OUT, true)
                sleepTimerEngine.setEndMode(SleepTimerMode.END_OF_QUEUE, fadeOut)
            }
            AuraSleepTimerContract.ACTION_ADD_MINUTES -> {
                val delta = args.getInt(AuraSleepTimerContract.EXTRA_DELTA_MINUTES, 5)
                sleepTimerEngine.addMinutes(delta)
            }
            AuraSleepTimerContract.ACTION_CANCEL -> sleepTimerEngine.cancel()
            AuraPlayerIpcContract.ACTION_SYNC_EQUALIZER -> {
                val eq = AuraPlayerIpcContract.equalizerFromBundle(args)
                onSyncEqualizer(eq)
                provider?.onSyncEqualizer(eq)
            }
            AuraPlayerIpcContract.ACTION_SYNC_AUDIO_EFFECTS -> {
                val fx = AuraPlayerIpcContract.audioEffectsFromBundle(args)
                onSyncAudioEffects(fx)
                provider?.onSyncAudioEffects(fx)
            }
            AuraPlayerIpcContract.ACTION_SYNC_PLAYBACK_TUNING -> {
                val tuning = AuraPlayerIpcContract.playbackTuningFromBundle(args)
                onSyncPlaybackTuning(tuning)
                provider?.onSyncPlaybackTuning(tuning)
            }
            AuraPlayerIpcContract.ACTION_SYNC_HEADPHONE_PREFS -> {
                val prefs = AuraPlayerIpcContract.headphonePrefsFromBundle(args)
                onSyncHeadphonePrefs(prefs)
                provider?.onSyncHeadphonePreferences(prefs)
            }
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

    private fun handleHeadsetHookTap(player: Player) {
        val prefs = headphonePrefsProvider()
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

        headsetHookDispatchJob = scope.launch {
            delay(320L)
            val finalizedCount = headsetHookTapCount
            headsetHookTapCount = 0
            executeMediaHookTap(finalizedCount, player)
        }
    }

    private fun executeMediaHookTap(tapCount: Int, player: Player) {
        when (HeadphoneRoutingHelper.resolveMediaHookTapAction(tapCount, headphonePrefsProvider())) {
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
}
