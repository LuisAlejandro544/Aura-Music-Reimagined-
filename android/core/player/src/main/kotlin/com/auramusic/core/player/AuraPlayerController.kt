package com.auramusic.core.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.PlaybackQueueHelper
import com.auramusic.core.model.PlaybackState
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.model.RepeatMode
import com.auramusic.core.model.SleepTimerHelper
import com.auramusic.core.model.SleepTimerMode
import com.auramusic.core.model.SleepTimerState
import com.auramusic.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

/**
 * Controlador reactivo de reproducción que conecta la UI de Jetpack Compose
 * con [AuraPlaybackService] a través de [MediaController].
 */
class AuraPlayerController(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    val sleepTimerState: StateFlow<SleepTimerState> = AuraSleepTimerBridge.sleepTimerState

    private var controller: MediaController? = null
    private var connectJob: Job? = null
    private var progressTickerJob: Job? = null
    private var sleepTimerUiTickerJob: Job? = null
    private var activeQueue: List<Track> = emptyList()
    private var latestLibraryTracks: List<Track> = emptyList()
    private var isUiAttached: Boolean = true
    private var pendingQueue: Pair<List<Track>, Int>? = null
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()
    private var previousRestartThresholdMs: Long =
        HeadphonePreferences.DEFAULT_PREVIOUS_RESTART_MS
    private var wrapQueueNavigation: Boolean = true

    private var cachedEqualizerState: EqualizerState? = null
    private var cachedAudioEffectsState: AudioEffectsState? = null
    private var cachedPlaybackTuning: PlaybackTuning? = null
    private var cachedHeadphonePrefs: HeadphonePreferences? = null

    @OptIn(UnstableApi::class)
    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            handleSessionExtras(extras)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncStateFromPlayer()
            if (isPlaying && isUiAttached) startProgressTicker() else stopProgressTicker()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateFromPlayer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncStateFromPlayer()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            syncStateFromPlayer()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncStateFromPlayer()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            syncStateFromPlayer()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                syncStateFromPlayer()
            }
        }
    }

    init {
        connectToService()
    }

    /**
     * Garantiza que la interfaz tenga una conexión activa con [AuraPlaybackService]
     * al volver a abrir la app aunque el Activity o ViewModel anterior haya sido destruido.
     */
    @OptIn(UnstableApi::class)
    fun onUiAttached() {
        isUiAttached = true
        val active = controller
        if (active == null || !active.isConnected) {
            connectToService()
        } else {
            handleSessionExtras(active.sessionExtras)
            syncStateFromPlayer()
            if (active.isPlaying) {
                startProgressTicker()
            }
        }
    }

    /**
     * Detiene los tickers visuales de progreso y temporizador cuando no hay UI visible,
     * sin destruir el [MediaController] ni interrumpir la reproducción en el proceso `:player`.
     */
    fun onUiDetached() {
        isUiAttached = false
        stopProgressTicker()
        stopSleepTimerUiTicker()
    }

    /**
     * Libera referencias temporales a la lista completa de pistas cuando el sistema solicita
     * recortar memoria y la interfaz permanece en segundo plano.
     */
    fun trimMemoryIfDetached() {
        if (!isUiAttached) {
            latestLibraryTracks = emptyList()
        }
    }

    @OptIn(UnstableApi::class)
    private fun connectToService() {
        val current = controller
        if (current != null && current.isConnected) return
        if (connectJob?.isActive == true) return

        connectJob = scope.launch {
            val token = SessionToken(
                context,
                ComponentName(context, AuraPlaybackService::class.java),
            )
            val mediaController = runCatching {
                MediaController.Builder(context, token)
                    .setListener(controllerListener)
                    .buildAsync()
                    .await()
            }.getOrNull() ?: return@launch

            controller?.removeListener(playerListener)
            controller = mediaController
            mediaController.addListener(playerListener)

            // Reenvía configuraciones locales cacheadas al proceso `:player` por si cambió o acaba de iniciar.
            cachedEqualizerState?.let {
                mediaController.sendCustomCommand(
                    SessionCommand(AuraPlayerIpcContract.ACTION_SYNC_EQUALIZER, Bundle.EMPTY),
                    AuraPlayerIpcContract.equalizerToBundle(it),
                )
            }
            cachedAudioEffectsState?.let {
                mediaController.sendCustomCommand(
                    SessionCommand(AuraPlayerIpcContract.ACTION_SYNC_AUDIO_EFFECTS, Bundle.EMPTY),
                    AuraPlayerIpcContract.audioEffectsToBundle(it),
                )
            }
            cachedPlaybackTuning?.let {
                mediaController.sendCustomCommand(
                    SessionCommand(AuraPlayerIpcContract.ACTION_SYNC_PLAYBACK_TUNING, Bundle.EMPTY),
                    AuraPlayerIpcContract.playbackTuningToBundle(it),
                )
            }
            cachedHeadphonePrefs?.let {
                mediaController.sendCustomCommand(
                    SessionCommand(AuraPlayerIpcContract.ACTION_SYNC_HEADPHONE_PREFS, Bundle.EMPTY),
                    AuraPlayerIpcContract.headphonePrefsToBundle(it),
                )
            }

            handleSessionExtras(mediaController.sessionExtras)

            val queued = pendingQueue
            if (queued != null) {
                pendingQueue = null
                applyQueueToPlayer(mediaController, queued.first, queued.second)
            }

            if (pendingActions.isNotEmpty()) {
                val actions = pendingActions.toList()
                pendingActions.clear()
                actions.forEach { action -> action(mediaController) }
            }

            syncStateFromPlayer()
            if (mediaController.isPlaying && isUiAttached) {
                startProgressTicker()
            }
        }
    }

    private inline fun withConnectedPlayer(crossinline block: (MediaController) -> Unit) {
        val player = controller
        if (player != null && player.isConnected) {
            block(player)
        } else {
            pendingActions.add { connected -> block(connected) }
            connectToService()
        }
    }

    /**
     * Carga una cola completa y comienza a reproducir la canción seleccionada.
     */
    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        activeQueue = tracks

        val player = controller
        if (player == null || !player.isConnected) {
            pendingQueue = tracks to safeIndex
            pendingActions.clear()
            connectToService()
            _playbackState.update {
                it.copy(
                    queue = tracks,
                    currentIndex = safeIndex,
                    currentTrack = tracks[safeIndex],
                )
            }
            return
        }

        pendingQueue = null
        applyQueueToPlayer(player, tracks, safeIndex)
    }

    /**
     * Carga una lista (por ejemplo de un artista) con modo aleatorio activado y empieza
     * desde una canción aleatoria perteneciente únicamente a esa lista.
     */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val startIndex = Random.nextInt(tracks.size)
        playQueue(tracks, startIndex)
        withConnectedPlayer { player ->
            player.shuffleModeEnabled = true
            syncStateFromPlayer()
        }
    }

    /**
     * Inserta una canción inmediatamente después de la pista que está sonando.
     * Si no hay cola activa, comienza a reproducirla al instante.
     */
    fun playNext(track: Track) {
        val currentPlayer = controller
        if (activeQueue.isEmpty() && (currentPlayer == null || !currentPlayer.isConnected || currentPlayer.mediaItemCount == 0)) {
            playQueue(listOf(track), 0)
            return
        }

        withConnectedPlayer { player ->
            if (activeQueue.isEmpty() && player.mediaItemCount > 0) {
                activeQueue = rebuildQueueFromPlayer(player)
            }
            if (activeQueue.isEmpty()) {
                applyQueueToPlayer(player, listOf(track), 0)
                return@withConnectedPlayer
            }

            val result = PlaybackQueueHelper.insertPlayNext(
                queue = activeQueue,
                currentIndex = player.currentMediaItemIndex,
                track = track,
            )
            activeQueue = result.queue
            player.addMediaItem(result.insertedAt.coerceIn(0, player.mediaItemCount), track.toMediaItem())
            syncStateFromPlayer()
        }
    }

    /**
     * Añade una canción al final de la cola de reproducción actual.
     * Si la cola está vacía, comienza a reproducirla al instante.
     */
    fun addToQueue(track: Track) {
        val currentPlayer = controller
        if (activeQueue.isEmpty() && (currentPlayer == null || !currentPlayer.isConnected || currentPlayer.mediaItemCount == 0)) {
            playQueue(listOf(track), 0)
            return
        }

        withConnectedPlayer { player ->
            if (activeQueue.isEmpty() && player.mediaItemCount > 0) {
                activeQueue = rebuildQueueFromPlayer(player)
            }
            if (activeQueue.isEmpty()) {
                applyQueueToPlayer(player, listOf(track), 0)
                return@withConnectedPlayer
            }

            val result = PlaybackQueueHelper.appendToQueue(
                queue = activeQueue,
                currentIndex = player.currentMediaItemIndex,
                track = track,
            )
            activeQueue = result.queue
            player.addMediaItem(track.toMediaItem())
            syncStateFromPlayer()
        }
    }

    /**
     * Salta directamente a un índice concreto dentro de la cola de reproducción activa.
     */
    fun playQueueItemAt(index: Int) {
        withConnectedPlayer { player ->
            if (activeQueue.isEmpty() && player.mediaItemCount > 0) {
                activeQueue = rebuildQueueFromPlayer(player)
            }
            if (index !in 0 until player.mediaItemCount) return@withConnectedPlayer
            player.seekToDefaultPosition(index)
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
            syncStateFromPlayer()
        }
    }

    /**
     * Reordena una canción dentro de la cola activa sin interrumpir el audio actual.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        withConnectedPlayer { player ->
            if (activeQueue.isEmpty() && player.mediaItemCount > 0) {
                activeQueue = rebuildQueueFromPlayer(player)
            }
            if (fromIndex !in activeQueue.indices ||
                toIndex !in activeQueue.indices ||
                fromIndex == toIndex
            ) {
                return@withConnectedPlayer
            }
            val result = PlaybackQueueHelper.moveItem(
                queue = activeQueue,
                currentIndex = player.currentMediaItemIndex,
                fromIndex = fromIndex,
                toIndex = toIndex,
            )
            activeQueue = result.queue
            player.moveMediaItem(fromIndex, toIndex)
            syncStateFromPlayer()
        }
    }

    /**
     * Quita una pista de la cola de reproducción actual.
     */
    fun removeFromQueue(index: Int) {
        withConnectedPlayer { player ->
            if (activeQueue.isEmpty() && player.mediaItemCount > 0) {
                activeQueue = rebuildQueueFromPlayer(player)
            }
            if (index !in activeQueue.indices || index !in 0 until player.mediaItemCount) {
                return@withConnectedPlayer
            }
            val result = PlaybackQueueHelper.removeItem(
                queue = activeQueue,
                currentIndex = player.currentMediaItemIndex,
                removeIndex = index,
            )
            activeQueue = result.queue
            player.removeMediaItem(index)
            if (activeQueue.isEmpty()) {
                player.stop()
            }
            syncStateFromPlayer()
        }
    }

    /**
     * Elimina todas las canciones programadas después de la pista actual.
     */
    fun clearUpcomingQueue() {
        withConnectedPlayer { player ->
            if (activeQueue.isEmpty() && player.mediaItemCount > 0) {
                activeQueue = rebuildQueueFromPlayer(player)
            }
            if (activeQueue.isEmpty() || player.mediaItemCount <= 1) return@withConnectedPlayer
            val currentIdx = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
            val removeStart = currentIdx + 1
            if (removeStart < player.mediaItemCount) {
                player.removeMediaItems(removeStart, player.mediaItemCount)
            }
            activeQueue = PlaybackQueueHelper.clearUpcoming(activeQueue, currentIdx).queue
            syncStateFromPlayer()
        }
    }

    private fun applyQueueToPlayer(
        player: MediaController,
        tracks: List<Track>,
        startIndex: Int,
    ) {
        activeQueue = tracks
        val mediaItems = tracks.map { it.toMediaItem() }
        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()
        syncStateFromPlayer()
    }

    fun togglePlayPause() {
        withConnectedPlayer { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                    player.prepare()
                }
                player.play()
            }
            syncStateFromPlayer()
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        _playbackState.update { it.copy(positionMs = clamped) }
        withConnectedPlayer { player ->
            player.seekTo(clamped)
            syncStateFromPlayer()
        }
    }

    fun updateHeadphoneControlTuning(
        previousRestartThresholdMs: Long,
        wrapQueueNavigation: Boolean,
    ) {
        this.previousRestartThresholdMs =
            com.auramusic.core.model.HeadphoneRoutingHelper.clampPreviousRestartThresholdMs(
                previousRestartThresholdMs,
            )
        this.wrapQueueNavigation = wrapQueueNavigation
    }

    fun skipToNext() {
        withConnectedPlayer { player ->
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.play()
                syncStateFromPlayer()
            } else if (wrapQueueNavigation && player.mediaItemCount > 0) {
                player.seekTo(0, 0L)
                player.play()
                syncStateFromPlayer()
            }
        }
    }

    fun skipToPrevious() {
        withConnectedPlayer { player ->
            if (previousRestartThresholdMs > 0L && player.currentPosition > previousRestartThresholdMs) {
                player.seekTo(0L)
            } else if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
                player.play()
            } else if (wrapQueueNavigation && player.mediaItemCount > 1) {
                player.seekTo(player.mediaItemCount - 1, 0L)
                player.play()
            } else {
                player.seekTo(0L)
            }
            syncStateFromPlayer()
        }
    }

    fun toggleShuffle() {
        withConnectedPlayer { player ->
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            syncStateFromPlayer()
        }
    }

    fun cycleRepeatMode() {
        withConnectedPlayer { player ->
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            syncStateFromPlayer()
        }
    }

    /**
     * Ajusta en tiempo real la velocidad de reproducción del servicio Media3 (0.50x a 2.00x).
     */
    fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = PlaybackTuningHelper.clampPlaybackSpeed(speed)
        _playbackState.update { it.copy(playbackSpeed = safeSpeed) }
        withConnectedPlayer { player ->
            player.setPlaybackSpeed(safeSpeed)
            syncStateFromPlayer()
        }
    }

    /**
     * Ajusta velocidad y tono/velocidad de voz en tiempo real en el servicio Media3.
     */
    fun setPlaybackParameters(speed: Float, pitch: Float) {
        val safeSpeed = PlaybackTuningHelper.clampPlaybackSpeed(speed)
        val safePitch = PlaybackTuningHelper.clampPlaybackPitch(pitch)
        _playbackState.update { it.copy(playbackSpeed = safeSpeed, playbackPitch = safePitch) }
        withConnectedPlayer { player ->
            player.playbackParameters = PlaybackParameters(safeSpeed, safePitch)
            syncStateFromPlayer()
        }
    }

    /**
     * Inicia el temporizador de apagado automático por duración en minutos.
     */
    fun startSleepTimerMinutes(minutes: Int, fadeOutOnFinish: Boolean = true) {
        val args = Bundle().apply {
            putInt(AuraSleepTimerContract.EXTRA_MINUTES, minutes)
            putBoolean(AuraSleepTimerContract.EXTRA_FADE_OUT, fadeOutOnFinish)
        }
        sendSleepTimerCommand(AuraSleepTimerContract.ACTION_START_MINUTES, args)
    }

    /**
     * Programa detener la música automáticamente al finalizar la canción actual.
     */
    fun setSleepTimerEndOfTrack(fadeOutOnFinish: Boolean = true) {
        val args = Bundle().apply {
            putBoolean(AuraSleepTimerContract.EXTRA_FADE_OUT, fadeOutOnFinish)
        }
        sendSleepTimerCommand(AuraSleepTimerContract.ACTION_SET_END_OF_TRACK, args)
    }

    /**
     * Programa detener la música al terminar todas las canciones en la cola actual.
     */
    fun setSleepTimerEndOfQueue(fadeOutOnFinish: Boolean = true) {
        val args = Bundle().apply {
            putBoolean(AuraSleepTimerContract.EXTRA_FADE_OUT, fadeOutOnFinish)
        }
        sendSleepTimerCommand(AuraSleepTimerContract.ACTION_SET_END_OF_QUEUE, args)
    }

    /**
     * Añade o resta minutos al temporizador de duración activo.
     */
    fun addSleepTimerMinutes(deltaMinutes: Int) {
        val args = Bundle().apply {
            putInt(AuraSleepTimerContract.EXTRA_DELTA_MINUTES, deltaMinutes)
        }
        sendSleepTimerCommand(AuraSleepTimerContract.ACTION_ADD_MINUTES, args)
    }

    /**
     * Cancela cualquier temporizador de apagado automático activo.
     */
    fun cancelSleepTimer() {
        sendSleepTimerCommand(AuraSleepTimerContract.ACTION_CANCEL, Bundle.EMPTY)
    }

    private fun sendSleepTimerCommand(action: String, args: Bundle) {
        withConnectedPlayer { player ->
            player.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        }
    }

    /**
     * Envía al proceso aislado `:player` el estado actualizado del ecualizador Rust.
     */
    fun syncEqualizer(state: EqualizerState) {
        cachedEqualizerState = state
        val player = controller?.takeIf { it.isConnected } ?: return
        player.sendCustomCommand(
            SessionCommand(AuraPlayerIpcContract.ACTION_SYNC_EQUALIZER, Bundle.EMPTY),
            AuraPlayerIpcContract.equalizerToBundle(state),
        )
    }

    /**
     * Envía al proceso aislado `:player` los efectos espaciales / Slowed / Reverb / Crossfeed.
     */
    fun syncAudioEffects(state: AudioEffectsState) {
        cachedAudioEffectsState = state
        val player = controller?.takeIf { it.isConnected } ?: return
        player.sendCustomCommand(
            SessionCommand(AuraPlayerIpcContract.ACTION_SYNC_AUDIO_EFFECTS, Bundle.EMPTY),
            AuraPlayerIpcContract.audioEffectsToBundle(state),
        )
    }

    /**
     * Envía al proceso aislado `:player` los ajustes de Crossfade, velocidad y tono.
     */
    fun syncPlaybackTuning(tuning: PlaybackTuning) {
        cachedPlaybackTuning = tuning
        val player = controller?.takeIf { it.isConnected } ?: return
        player.sendCustomCommand(
            SessionCommand(AuraPlayerIpcContract.ACTION_SYNC_PLAYBACK_TUNING, Bundle.EMPTY),
            AuraPlayerIpcContract.playbackTuningToBundle(tuning),
        )
    }

    /**
     * Envía al proceso aislado `:player` las preferencias de auriculares y AVRCP.
     */
    fun syncHeadphonePreferences(prefs: HeadphonePreferences) {
        cachedHeadphonePrefs = prefs
        updateHeadphoneControlTuning(prefs.previousRestartThresholdMs, prefs.wrapQueueNavigation)
        val player = controller?.takeIf { it.isConnected } ?: return
        player.sendCustomCommand(
            SessionCommand(AuraPlayerIpcContract.ACTION_SYNC_HEADPHONE_PREFS, Bundle.EMPTY),
            AuraPlayerIpcContract.headphonePrefsToBundle(prefs),
        )
    }

    private fun handleSessionExtras(extras: Bundle) {
        val remoteState = AuraPlayerIpcContract.sleepTimerFromBundle(extras)
        if (remoteState.mode == SleepTimerMode.DURATION && remoteState.endElapsedMs > 0L) {
            val remaining = SleepTimerHelper.computeRemainingMs(
                endElapsedMs = remoteState.endElapsedMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
            AuraSleepTimerBridge.publishState(remoteState.copy(remainingMs = remaining))
            if (isUiAttached && remaining > 0L) {
                startSleepTimerUiTicker(remoteState)
            } else {
                stopSleepTimerUiTicker()
            }
        } else {
            stopSleepTimerUiTicker()
            AuraSleepTimerBridge.publishState(remoteState)
        }
    }

    private fun startSleepTimerUiTicker(baseState: SleepTimerState) {
        sleepTimerUiTickerJob?.cancel()
        sleepTimerUiTickerJob = scope.launch {
            while (isActive && isUiAttached && baseState.mode == SleepTimerMode.DURATION) {
                val remaining = SleepTimerHelper.computeRemainingMs(
                    endElapsedMs = baseState.endElapsedMs,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
                AuraSleepTimerBridge.publishState(baseState.copy(remainingMs = remaining))
                if (remaining <= 0L) {
                    break
                }
                delay(500L)
            }
        }
    }

    private fun stopSleepTimerUiTicker() {
        sleepTimerUiTickerJob?.cancel()
        sleepTimerUiTickerJob = null
    }

    /**
     * Mantiene sincronizados cambios de metadata en caliente (por ejemplo, marcar Me gusta)
     * y permite reconstruir pistas si el servicio siguió sonando tras reiniciar la UI.
     */
    fun refreshTrackSnapshots(updatedTracks: List<Track>) {
        latestLibraryTracks = updatedTracks
        if (activeQueue.isEmpty()) {
            syncStateFromPlayer()
            return
        }
        val byId = updatedTracks.associateBy { it.id }
        activeQueue = activeQueue.map { track -> byId[track.id] ?: track }
        syncMediaItemsMetadata(activeQueue)
        syncStateFromPlayer()
    }

    /**
     * Sincroniza cambios de título, artista o carátula en los [MediaItem] ya cargados
     * dentro de ExoPlayer usando `replaceMediaItem` sin interrumpir el audio actual,
     * reflejando los cambios al instante en la notificación del sistema y pantalla de bloqueo.
     */
    private fun syncMediaItemsMetadata(tracks: List<Track>) {
        val player = controller?.takeIf { it.isConnected } ?: return
        val count = minOf(player.mediaItemCount, tracks.size)
        for (idx in 0 until count) {
            val track = tracks[idx]
            val existing = player.getMediaItemAt(idx)
            if (existing.mediaId != track.id.toString()) continue

            val targetMetadata = track.toMediaMetadata()
            val currentMeta = existing.mediaMetadata
            if (currentMeta.title?.toString() != targetMetadata.title?.toString() ||
                currentMeta.artist?.toString() != targetMetadata.artist?.toString() ||
                currentMeta.albumTitle?.toString() != targetMetadata.albumTitle?.toString() ||
                currentMeta.artworkUri != targetMetadata.artworkUri
            ) {
                val updatedItem = existing.buildUpon()
                    .setMediaMetadata(targetMetadata)
                    .build()
                player.replaceMediaItem(idx, updatedItem)
            }
        }
    }

    private fun syncStateFromPlayer() {
        val player = controller?.takeIf { it.isConnected } ?: return
        val speed = PlaybackTuningHelper.clampPlaybackSpeed(player.playbackParameters.speed)
        val pitch = PlaybackTuningHelper.clampPlaybackPitch(player.playbackParameters.pitch)

        if (player.mediaItemCount == 0) {
            activeQueue = emptyList()
            _playbackState.update {
                PlaybackState(
                    currentTrack = null,
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    queue = emptyList(),
                    currentIndex = -1,
                    shuffleEnabled = player.shuffleModeEnabled,
                    repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                        else -> RepeatMode.OFF
                    },
                    playbackSpeed = speed,
                    playbackPitch = pitch,
                )
            }
            return
        }

        if (activeQueue.isEmpty() && player.mediaItemCount > 0) {
            activeQueue = rebuildQueueFromPlayer(player)
        }

        val index = player.currentMediaItemIndex
        val currentTrack = activeQueue.getOrNull(index)
            ?: player.currentMediaItem?.toFallbackTrack()
        val duration = player.duration.takeIf { it > 0L } ?: currentTrack?.durationMs ?: 0L

        _playbackState.update {
            PlaybackState(
                currentTrack = currentTrack,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                queue = activeQueue,
                currentIndex = index,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                },
                playbackSpeed = speed,
                playbackPitch = pitch,
            )
        }
    }

    private fun rebuildQueueFromPlayer(player: MediaController): List<Track> {
        val byId = latestLibraryTracks.associateBy { it.id }
        return List(player.mediaItemCount) { idx ->
            val item = player.getMediaItemAt(idx)
            val id = item.mediaId.toLongOrNull()
            (if (id != null) byId[id] else null) ?: item.toFallbackTrack()
        }
    }

    private fun MediaItem.toFallbackTrack(): Track {
        val id = mediaId.toLongOrNull() ?: 0L
        val meta = mediaMetadata
        return Track(
            id = id,
            contentUri = localConfiguration?.uri?.toString().orEmpty(),
            title = meta.title?.toString().orEmpty().ifBlank { "Reproduciendo" },
            artist = meta.artist?.toString().orEmpty().ifBlank { "Artista desconocido" },
            album = meta.albumTitle?.toString().orEmpty().ifBlank { "Biblioteca local" },
            durationMs = 0L,
            coverFilePath = meta.artworkUri?.path,
            isProceduralCover = false,
            isLiked = false,
            trackFingerprint = mediaId,
        )
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch {
            while (isActive) {
                val player = controller
                if (player != null && player.isConnected && player.isPlaying) {
                    _playbackState.update { state ->
                        val duration = player.duration.takeIf { it > 0L } ?: state.durationMs
                        state.copy(
                            positionMs = player.currentPosition.coerceAtLeast(0L),
                            durationMs = duration,
                        )
                    }
                }
                delay(400L)
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = null
    }

    fun release() {
        isUiAttached = false
        stopProgressTicker()
        stopSleepTimerUiTicker()
        connectJob?.cancel()
        connectJob = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }

    private fun Track.toMediaMetadata(): MediaMetadata {
        val artworkUri: Uri? = coverFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) Uri.fromFile(file) else null
        }

        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply {
                if (artworkUri != null) setArtworkUri(artworkUri)
            }
            .build()
    }

    private fun Track.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(contentUri.toUri())
            .setMediaMetadata(toMediaMetadata())
            .build()
    }
}
