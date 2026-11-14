package com.auramusic.core.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.auramusic.core.model.PlaybackState
import com.auramusic.core.model.RepeatMode
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

    private var controller: MediaController? = null
    private var connectJob: Job? = null
    private var progressTickerJob: Job? = null
    private var activeQueue: List<Track> = emptyList()
    private var latestLibraryTracks: List<Track> = emptyList()
    private var isUiAttached: Boolean = true
    private var pendingQueue: Pair<List<Track>, Int>? = null
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

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
    fun onUiAttached() {
        isUiAttached = true
        val active = controller
        if (active == null || !active.isConnected) {
            connectToService()
        } else {
            syncStateFromPlayer()
            if (active.isPlaying) {
                startProgressTicker()
            }
        }
    }

    /**
     * Detiene el ticker visual de progreso cuando no hay UI visible, sin destruir
     * el [MediaController] ni interrumpir la reproducción en segundo plano.
     */
    fun onUiDetached() {
        isUiAttached = false
        stopProgressTicker()
    }

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
                MediaController.Builder(context, token).buildAsync().await()
            }.getOrNull() ?: return@launch

            controller?.removeListener(playerListener)
            controller = mediaController
            mediaController.addListener(playerListener)

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

    fun skipToNext() {
        withConnectedPlayer { player ->
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.play()
                syncStateFromPlayer()
            }
        }
    }

    fun skipToPrevious() {
        withConnectedPlayer { player ->
            if (player.currentPosition > 3_500L) {
                player.seekTo(0L)
            } else if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
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
        syncStateFromPlayer()
    }

    private fun syncStateFromPlayer() {
        val player = controller?.takeIf { it.isConnected } ?: return

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
        connectJob?.cancel()
        connectJob = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }

    private fun Track.toMediaItem(): MediaItem {
        val artworkUri: Uri? = coverFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) Uri.fromFile(file) else null
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply {
                if (artworkUri != null) setArtworkUri(artworkUri)
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(contentUri.toUri())
            .setMediaMetadata(metadata)
            .build()
    }
}
