package com.auramusic.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.PlaybackState
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel coordinador principal de Aura Music.
 * Conecta de forma reactiva los flujos de Room, DataStore, AudioImporter y ExoPlayer,
 * manteniendo todas las operaciones pesadas en coroutines fuera del hilo principal.
 */
class AuraViewModel(
    private val container: AuraAppContainer,
) : ViewModel() {

    val accentColor: StateFlow<AppAccentColor> = container.themePreferencesRepository.accentColor
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AppAccentColor.AMETHYST,
        )

    val tracks: StateFlow<List<Track>> = container.musicRepository.observeAllTracks()
        .onEach { updated -> container.playerController.refreshTrackSnapshots(updated) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    val likedTracks: StateFlow<List<Track>> = container.musicRepository.observeLikedTracks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    val likedCount: StateFlow<Int> = container.musicRepository.observeLikedCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = 0,
        )

    val playlists: StateFlow<List<Playlist>> = container.musicRepository.observePlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    val playbackState: StateFlow<PlaybackState> = container.playerController.playbackState

    val importProgress: StateFlow<ImportProgress> = container.audioImporter.progress

    val rustEngineInfo: String = container.rustEngineInfo

    fun observePlaylistName(playlistId: Long): Flow<String?> =
        container.musicRepository.observePlaylistName(playlistId)

    fun observeTracksForPlaylist(playlistId: Long): Flow<List<Track>> =
        container.musicRepository.observeTracksForPlaylist(playlistId)

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch {
            container.audioImporter.importDocumentUris(
                uris = uris,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
        }
    }

    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            container.audioImporter.importFolderTree(
                treeUri = treeUri,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
        }
    }

    fun dismissImportMessage() {
        container.audioImporter.clearLastMessage()
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        container.playerController.playQueue(tracks, startIndex)
    }

    fun togglePlayPause() = container.playerController.togglePlayPause()

    fun seekTo(positionMs: Long) = container.playerController.seekTo(positionMs)

    fun skipNext() = container.playerController.skipToNext()

    fun skipPrevious() = container.playerController.skipToPrevious()

    fun toggleShuffle() = container.playerController.toggleShuffle()

    fun cycleRepeat() = container.playerController.cycleRepeatMode()

    fun toggleLike(trackId: Long) {
        viewModelScope.launch {
            container.musicRepository.toggleLike(trackId)
        }
    }

    fun deleteTrack(trackId: Long) {
        viewModelScope.launch {
            container.musicRepository.deleteTrack(trackId)
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            container.musicRepository.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            container.musicRepository.deletePlaylist(playlistId)
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            container.musicRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val playlistId = container.musicRepository.createPlaylist(name)
            container.musicRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            container.musicRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun selectAccentColor(color: AppAccentColor) {
        viewModelScope.launch {
            container.themePreferencesRepository.setAccentColor(color)
        }
    }

    override fun onCleared() {
        super.onCleared()
        container.playerController.release()
    }

    class Factory(
        private val container: AuraAppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuraViewModel(container) as T
        }
    }
}
