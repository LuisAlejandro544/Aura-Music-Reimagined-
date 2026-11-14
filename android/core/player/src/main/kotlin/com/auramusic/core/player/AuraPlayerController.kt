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
    private var progressTickerJob: Job? = null
    private var activeQueue: List<Track> = emptyList()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncStateFromPlayer()
            if (isPlaying) startProgressTicker() else stopProgressTicker()
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
    }

    init {
        connectToService()
    }

    private fun connectToService() {
        scope.launch {
            val token = SessionToken(
                context,
                ComponentName(context, AuraPlaybackService::class.java),
            )
            val mediaController = runCatching {
                MediaController.Builder(context, token).buildAsync().await()
            }.getOrNull() ?: return@launch

            controller = mediaController
            mediaController.addListener(playerListener)
            syncStateFromPlayer()
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
        if (player == null) {
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

        val mediaItems = tracks.map { it.toMediaItem() }
        player.setMediaItems(mediaItems, safeIndex, 0L)
        player.prepare()
        player.play()
        syncStateFromPlayer()
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE && activeQueue.isNotEmpty()) {
                player.prepare()
            }
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        val clamped = positionMs.coerceAtLeast(0L)
        player.seekTo(clamped)
        _playbackState.update { it.copy(positionMs = clamped) }
    }

    fun skipToNext() {
        val player = controller ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        }
    }

    fun skipToPrevious() {
        val player = controller ?: return
        if (player.currentPosition > 3_500L) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            player.play()
        } else {
            player.seekTo(0L)
        }
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * Mantiene sincronizados cambios de metadata en caliente (por ejemplo, marcar Me gusta).
     */
    fun refreshTrackSnapshots(updatedTracks: List<Track>) {
        if (activeQueue.isEmpty()) return
        val byId = updatedTracks.associateBy { it.id }
        activeQueue = activeQueue.map { track -> byId[track.id] ?: track }
        syncStateFromPlayer()
    }

    private fun syncStateFromPlayer() {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        val currentTrack = activeQueue.getOrNull(index)
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

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch {
            while (isActive) {
                val player = controller
                if (player != null && player.isPlaying) {
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
        stopProgressTicker()
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
